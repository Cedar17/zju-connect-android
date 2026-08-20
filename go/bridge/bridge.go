// Package core is the Android-facing façade for zju-connect.
//
// Keep this surface constrained to gomobile-compatible values: String and
// callback interfaces. Do not expose upstream clients, credentials, sessions,
// file paths, or file descriptors here without a reviewed lifecycle design.
package core

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/mythologyli/zju-connect/client/atrust/auth"
)

const (
	schemaVersion                       = 1
	authenticationSnapshotSchemaVersion = 1
	maxAuthenticationSnapshotSize       = 64 * 1024
	zjuAtrustServer                     = "vpn.zju.edu.cn"
	zjuAtrustServerPort                 = 443
)

// BridgeListener is implemented by Kotlin. Event payloads use versioned JSON
// so later Go changes do not leak complex Go types across the mobile boundary.
type BridgeListener interface {
	OnEvent(eventJSON string)
}

type authInfoResponse struct {
	SchemaVersion int    `json:"schemaVersion"`
	Type          string `json:"type"`
	Code          string `json:"code,omitempty"`
	Message       string `json:"message"`
	AuthMethods   any    `json:"authMethods,omitempty"`
}

type authenticationStartRequest struct {
	Server   string `json:"server"`
	Port     int    `json:"port"`
	DeviceID string `json:"deviceId"`
}

type authenticationSubmission struct {
	Action      string `json:"action"`
	AuthType    string `json:"authType,omitempty"`
	LoginDomain string `json:"loginDomain,omitempty"`
	Username    string `json:"username,omitempty"`
	Password    string `json:"password,omitempty"`
	Phone       string `json:"phone,omitempty"`
	SMSCode     string `json:"smsCode,omitempty"`
	Captcha     string `json:"captcha,omitempty"`
	Token       string `json:"token,omitempty"`
}

type authenticationEvent struct {
	SchemaVersion int             `json:"schemaVersion"`
	Type          string          `json:"type"`
	State         string          `json:"state"`
	Code          string          `json:"code,omitempty"`
	Message       string          `json:"message"`
	Stage         string          `json:"stage,omitempty"`
	Cause         string          `json:"cause,omitempty"`
	DurationMs    int64           `json:"durationMs,omitempty"`
	ChallengeKind string          `json:"challengeKind,omitempty"`
	AuthMethods   []auth.AuthInfo `json:"authMethods,omitempty"`
	PhoneNumbers  []string        `json:"phoneNumbers,omitempty"`
	CaptchaWidth  int             `json:"captchaWidth,omitempty"`
	CaptchaHeight int             `json:"captchaHeight,omitempty"`
	Username      string          `json:"username,omitempty"`
}

// authenticationSnapshot is the only sensitive value allowed to cross from
// Go to Kotlin for persistence. It deliberately excludes password, username,
// standalone SID, resources, connection ID, and sign key.
type authenticationSnapshot struct {
	SchemaVersion int           `json:"schemaVersion"`
	DeviceID      string        `json:"deviceId"`
	Cookies       []auth.Cookie `json:"cookies"`
}

type authenticationSession struct {
	mu                  sync.Mutex
	flow                *auth.InteractiveFlow
	listener            BridgeListener
	server              string
	port                int
	deviceID            string
	busy                bool
	authenticatedResult *auth.InteractiveResult
}

var currentAuthentication authenticationSession

func cloneInteractiveResult(result auth.InteractiveResult) auth.InteractiveResult {
	return auth.InteractiveResult{
		Username:     result.Username,
		SID:          result.SID,
		AuthData:     append([]byte(nil), result.AuthData...),
		ResourceData: append([]byte(nil), result.ResourceData...),
	}
}

func clearInteractiveResult(result *auth.InteractiveResult) {
	if result == nil {
		return
	}
	clear(result.AuthData)
	clear(result.ResourceData)
	result.Username = ""
	result.SID = ""
	result.AuthData = nil
	result.ResourceData = nil
}

func isReusableInteractiveResult(result auth.InteractiveResult) bool {
	return result.SID != "" && len(result.AuthData) > 0 && len(result.ResourceData) > 0
}

func cacheAuthenticatedResult(result auth.InteractiveResult) {
	cacheAuthenticatedResultForFlow(nil, nil, result)
}

func cacheAuthenticatedResultForFlow(
	flow *auth.InteractiveFlow,
	listener BridgeListener,
	result auth.InteractiveResult,
) bool {
	if !isReusableInteractiveResult(result) {
		return false
	}
	cached := cloneInteractiveResult(result)
	currentAuthentication.mu.Lock()
	if flow != nil && (currentAuthentication.flow != flow || currentAuthentication.listener != listener) {
		currentAuthentication.mu.Unlock()
		clearInteractiveResult(&cached)
		return false
	}
	previous := currentAuthentication.authenticatedResult
	currentAuthentication.authenticatedResult = &cached
	currentAuthentication.mu.Unlock()
	clearInteractiveResult(previous)
	return true
}

func reusableAuthenticatedResult() (auth.InteractiveResult, bool) {
	currentAuthentication.mu.Lock()
	cached := currentAuthentication.authenticatedResult
	if cached == nil || !isReusableInteractiveResult(*cached) {
		currentAuthentication.mu.Unlock()
		return auth.InteractiveResult{}, false
	}
	result := cloneInteractiveResult(*cached)
	currentAuthentication.mu.Unlock()
	return result, true
}

func currentAuthenticatedResult() (auth.InteractiveResult, bool) {
	if result, ok := reusableAuthenticatedResult(); ok {
		return result, true
	}

	currentAuthentication.mu.Lock()
	flow := currentAuthentication.flow
	currentAuthentication.mu.Unlock()
	if flow == nil {
		return auth.InteractiveResult{}, false
	}
	return flow.Result()
}

// StartAuthentication begins a single in-memory aTrust authentication session.
// The target is deliberately restricted to ZJU's production aTrust endpoint so
// credentials cannot be redirected to an arbitrary host by the UI layer.
func StartAuthentication(requestJSON string, listener BridgeListener) string {
	var request authenticationStartRequest
	if err := json.Unmarshal([]byte(requestJSON), &request); err != nil {
		return authError("invalidRequest", "Authentication request is not valid JSON")
	}
	if request.Server != zjuAtrustServer || request.Port != zjuAtrustServerPort {
		return authError("invalidRequest", "Authentication is only available for the ZJU aTrust service")
	}
	if err := validateDeviceID(request.DeviceID); err != nil {
		return authError("invalidRequest", "Authentication requires a valid device identity")
	}
	if listener == nil {
		return authError("invalidRequest", "Authentication listener is required")
	}

	currentAuthentication.mu.Lock()
	if currentAuthentication.flow != nil {
		currentAuthentication.mu.Unlock()
		return authError("alreadyRunning", "An authentication session is already active")
	}
	flow, err := newAuthenticationFlow(request.Server, request.Port, request.DeviceID)
	if err != nil {
		currentAuthentication.mu.Unlock()
		return authError("initializationFailed", "Unable to initialize authentication")
	}
	currentAuthentication.flow = flow
	currentAuthentication.listener = listener
	currentAuthentication.server = request.Server
	currentAuthentication.port = request.Port
	currentAuthentication.deviceID = request.DeviceID
	currentAuthentication.busy = true
	currentAuthentication.mu.Unlock()

	go runAuthenticationOperation(flow, listener, "auth.config", func() (auth.InteractivePrompt, error) {
		return flow.Begin()
	})
	return marshal(authenticationEvent{
		SchemaVersion: schemaVersion,
		Type:          "authenticationStarted",
		State:         "fetchingAuthMethods",
		Message:       "Fetching available authentication methods",
	})
}

// SubmitAuthentication advances the current session. Values supplied in this
// JSON are used in-memory only and are never included in a callback event.
func SubmitAuthentication(responseJSON string) string {
	var response authenticationSubmission
	if err := json.Unmarshal([]byte(responseJSON), &response); err != nil {
		return authError("invalidRequest", "Authentication response is not valid JSON")
	}
	if response.Action == "retry" {
		return retryAuthentication()
	}

	flow, listener, ok := acquireAuthenticationOperation()
	if !ok {
		return authError("invalidState", "Authentication is not ready for another response")
	}
	var (
		operation func() (auth.InteractivePrompt, error)
		stage     = authenticationStage(response.Action)
	)
	switch response.Action {
	case "selectMethod":
		operation = func() (auth.InteractivePrompt, error) {
			return flow.SelectMethod(response.AuthType, response.LoginDomain)
		}
	case "submitCredentials":
		operation = func() (auth.InteractivePrompt, error) {
			return flow.SubmitCredentials(response.Username, response.Password)
		}
	case "submitPhone":
		operation = func() (auth.InteractivePrompt, error) {
			return flow.SubmitPhone(response.Phone)
		}
	case "submitSmsCode":
		operation = func() (auth.InteractivePrompt, error) {
			return flow.SubmitSMSCode(response.SMSCode)
		}
	case "submitCaptcha":
		operation = func() (auth.InteractivePrompt, error) {
			return flow.SubmitCaptcha(response.Captcha)
		}
	case "submitToken":
		operation = func() (auth.InteractivePrompt, error) {
			return flow.SubmitToken(response.Token)
		}
	default:
		releaseAuthenticationOperation(flow)
		return authError("invalidRequest", "Unknown authentication response")
	}

	go runAuthenticationOperation(flow, listener, stage, operation)
	return marshal(authenticationEvent{
		SchemaVersion: schemaVersion,
		Type:          "responseAccepted",
		State:         "authenticating",
		Message:       "Authentication response accepted",
	})
}

// GetPendingCaptchaImage returns a copy of the current image bytes only while
// the flow is awaiting a captcha response. It never writes a file.
func GetPendingCaptchaImage() []byte {
	currentAuthentication.mu.Lock()
	flow := currentAuthentication.flow
	currentAuthentication.mu.Unlock()
	if flow == nil {
		return nil
	}
	image, err := flow.PendingCaptchaImage()
	if err != nil {
		return nil
	}
	return image
}

// CancelAuthentication clears passwords, verification input, captcha bytes,
// and the active interactive flow. A completed authenticated result remains
// reusable until ClearAuthenticatedResult is called or the process exits.
// Repeating it is harmless.
func CancelAuthentication() {
	currentAuthentication.mu.Lock()
	flow := currentAuthentication.flow
	listener := currentAuthentication.listener
	currentAuthentication.flow = nil
	currentAuthentication.listener = nil
	currentAuthentication.server = ""
	currentAuthentication.port = 0
	currentAuthentication.deviceID = ""
	currentAuthentication.busy = false
	currentAuthentication.mu.Unlock()
	if flow != nil {
		flow.Cancel()
	}
	if listener != nil {
		emitAuthenticationEvent(listener, authenticationEvent{
			SchemaVersion: schemaVersion,
			Type:          "cancelled",
			State:         "cancelled",
			Code:          "cancelled",
			Message:       "Authentication cancelled",
		})
	}
}

// HasReusableAuthenticatedResult reports whether the process holds a complete
// authenticated result suitable for direct VPN preparation. It intentionally
// exposes no session fields across the bridge.
func HasReusableAuthenticatedResult() bool {
	currentAuthentication.mu.Lock()
	result := currentAuthentication.authenticatedResult
	ok := result != nil && isReusableInteractiveResult(*result)
	currentAuthentication.mu.Unlock()
	return ok
}

// ClearAuthenticatedResult discards both the reusable result and any result
// still held by the active interactive flow.
func ClearAuthenticatedResult() {
	currentAuthentication.mu.Lock()
	flow := currentAuthentication.flow
	result := currentAuthentication.authenticatedResult
	currentAuthentication.authenticatedResult = nil
	currentAuthentication.mu.Unlock()
	clearInteractiveResult(result)
	if flow != nil {
		flow.ClearResult()
	}
}

// ExportAuthenticatedSession returns the minimum versioned state needed to
// validate this authentication again. The returned JSON bytes are sensitive:
// callers must encrypt them immediately and must never log them.
func ExportAuthenticatedSession() []byte {
	result, ok := currentAuthenticatedResult()
	if !ok {
		return nil
	}
	defer clearInteractiveResult(&result)
	encoded, err := encodeAuthenticationSnapshot(result.AuthData)
	if err != nil {
		return nil
	}
	return encoded
}

// ResumeAuthentication validates an encrypted-at-rest snapshot after Kotlin
// decrypts it. The flow is restricted to ZJU's endpoint and emits only safe
// lifecycle events; snapshot values never enter callback JSON.
func ResumeAuthentication(snapshotBytes []byte, deviceID string, listener BridgeListener) string {
	if listener == nil {
		return authError("invalidRequest", "Authentication listener is required")
	}
	if err := validateDeviceID(deviceID); err != nil {
		return authError("invalidRequest", "Session restoration requires a valid device identity")
	}
	authData, err := decodeAuthenticationSnapshot(snapshotBytes, deviceID)
	if err != nil {
		return authError("invalidSession", "Saved authentication data is invalid")
	}

	currentAuthentication.mu.Lock()
	if currentAuthentication.flow != nil {
		currentAuthentication.mu.Unlock()
		return authError("alreadyRunning", "An authentication session is already active")
	}
	flow, err := newAuthenticationFlow(zjuAtrustServer, zjuAtrustServerPort, deviceID)
	if err != nil {
		currentAuthentication.mu.Unlock()
		return authError("initializationFailed", "Unable to initialize session restoration")
	}
	currentAuthentication.flow = flow
	currentAuthentication.listener = listener
	currentAuthentication.server = zjuAtrustServer
	currentAuthentication.port = zjuAtrustServerPort
	currentAuthentication.deviceID = deviceID
	currentAuthentication.busy = true
	currentAuthentication.mu.Unlock()

	go runSessionRestore(flow, listener, authData)
	return marshal(authenticationEvent{
		SchemaVersion: schemaVersion,
		Type:          "sessionRestoreStarted",
		State:         "restoringSession",
		Message:       "Validating saved authentication session",
	})
}

func encodeAuthenticationSnapshot(authData []byte) ([]byte, error) {
	var clientAuthData auth.ClientAuthData
	if err := json.Unmarshal(authData, &clientAuthData); err != nil {
		return nil, err
	}
	snapshot := authenticationSnapshot{
		SchemaVersion: authenticationSnapshotSchemaVersion,
		DeviceID:      clientAuthData.DeviceID,
		Cookies:       append([]auth.Cookie(nil), clientAuthData.Cookies...),
	}
	if err := validateAuthenticationSnapshot(snapshot); err != nil {
		return nil, err
	}
	return json.Marshal(snapshot)
}

func decodeAuthenticationSnapshot(snapshotBytes []byte, deviceID string) ([]byte, error) {
	if len(snapshotBytes) == 0 || len(snapshotBytes) > maxAuthenticationSnapshotSize {
		return nil, fmt.Errorf("authentication snapshot size is invalid")
	}
	var snapshot authenticationSnapshot
	if err := json.Unmarshal(snapshotBytes, &snapshot); err != nil {
		return nil, err
	}
	if err := validateAuthenticationSnapshot(snapshot); err != nil {
		return nil, err
	}
	return json.Marshal(auth.ClientAuthData{
		DeviceID: deviceID,
		Cookies:  append([]auth.Cookie(nil), snapshot.Cookies...),
	})
}

func validateAuthenticationSnapshot(snapshot authenticationSnapshot) error {
	if snapshot.SchemaVersion != authenticationSnapshotSchemaVersion {
		return fmt.Errorf("unsupported authentication snapshot version")
	}
	if err := validateDeviceID(snapshot.DeviceID); err != nil {
		return err
	}
	if len(snapshot.Cookies) == 0 || len(snapshot.Cookies) > 64 {
		return fmt.Errorf("cookie count is invalid")
	}
	expectedHost := net.JoinHostPort(zjuAtrustServer, fmt.Sprint(zjuAtrustServerPort))
	hasSID := false
	for _, cookie := range snapshot.Cookies {
		if cookie.Host != expectedHost || cookie.Scheme != "https" || cookie.Name == "" || cookie.Value == "" {
			return fmt.Errorf("cookie scope is invalid")
		}
		if cookie.Name == "sid" {
			hasSID = true
		}
	}
	if !hasSID {
		return fmt.Errorf("sid cookie is missing")
	}
	return nil
}

func validateDeviceID(deviceID string) error {
	if len(deviceID) != 32 {
		return fmt.Errorf("device ID length is invalid")
	}
	if _, err := hex.DecodeString(deviceID); err != nil {
		return fmt.Errorf("device ID is invalid")
	}
	return nil
}

func newAuthenticationFlow(server string, port int, deviceID string) (*auth.InteractiveFlow, error) {
	return auth.NewInteractiveFlowWithOptions(
		net.JoinHostPort(server, fmt.Sprint(port)),
		auth.InteractiveFlowOptions{DeviceID: deviceID, StrictTLS: true},
	)
}

func acquireAuthenticationOperation() (*auth.InteractiveFlow, BridgeListener, bool) {
	currentAuthentication.mu.Lock()
	defer currentAuthentication.mu.Unlock()
	if currentAuthentication.flow == nil || currentAuthentication.listener == nil || currentAuthentication.busy {
		return nil, nil, false
	}
	currentAuthentication.busy = true
	return currentAuthentication.flow, currentAuthentication.listener, true
}

func releaseAuthenticationOperation(flow *auth.InteractiveFlow) {
	currentAuthentication.mu.Lock()
	if currentAuthentication.flow == flow {
		currentAuthentication.busy = false
	}
	currentAuthentication.mu.Unlock()
}

func isCurrentAuthenticationFlow(flow *auth.InteractiveFlow, listener BridgeListener) bool {
	currentAuthentication.mu.Lock()
	defer currentAuthentication.mu.Unlock()
	return currentAuthentication.flow == flow && currentAuthentication.listener == listener
}

func runAuthenticationOperation(
	flow *auth.InteractiveFlow,
	listener BridgeListener,
	stage string,
	operation func() (auth.InteractivePrompt, error),
) {
	started := time.Now()
	prompt, err := operation()
	duration := time.Since(started)
	releaseAuthenticationOperation(flow)
	if !isCurrentAuthenticationFlow(flow, listener) {
		return
	}
	if err != nil {
		emitAuthenticationEvent(listener, authenticationFailure(stage, duration, err))
		return
	}
	runAuthenticationPrompt(flow, listener, prompt, stage, duration)
}

func runSessionRestore(flow *auth.InteractiveFlow, listener BridgeListener, authData []byte) {
	defer clear(authData)
	started := time.Now()
	prompt, err := flow.Resume(authData)
	duration := time.Since(started)
	releaseAuthenticationOperation(flow)
	if !isCurrentAuthenticationFlow(flow, listener) {
		return
	}
	if err != nil {
		discardAuthenticationFlow(flow)
		emitAuthenticationEvent(listener, sessionRestoreFailure(duration, err))
		return
	}
	runAuthenticationPrompt(flow, listener, prompt, "auth.session_restore", duration)
}

func runAuthenticationPrompt(
	flow *auth.InteractiveFlow,
	listener BridgeListener,
	prompt auth.InteractivePrompt,
	stage string,
	duration time.Duration,
) {
	event := authenticationEvent{
		SchemaVersion: schemaVersion,
		State:         prompt.State,
		Code:          prompt.Code,
		Message:       prompt.Message,
		Stage:         stage,
		DurationMs:    boundedDurationMillis(duration),
		ChallengeKind: prompt.ChallengeKind,
		AuthMethods:   prompt.AuthMethods,
		PhoneNumbers:  prompt.PhoneNumbers,
		CaptchaWidth:  prompt.CaptchaWidth,
		CaptchaHeight: prompt.CaptchaHeight,
	}
	switch prompt.State {
	case "awaitingMethod":
		event.Type = "authMethodsReady"
	case "awaitingCredentials":
		event.Type = "credentialsRequired"
	case "awaitingPhone":
		event.Type = "phoneRequired"
	case "awaitingSms":
		event.Type = "smsRequired"
	case "awaitingCaptcha":
		event.Type = "captchaRequired"
	case "awaitingToken":
		event.Type = "tokenRequired"
	case "authenticated":
		event.Type = "authenticated"
		if result, ok := flow.Result(); ok {
			if !cacheAuthenticatedResultForFlow(flow, listener, result) {
				clearInteractiveResult(&result)
				return
			}
			event.Username = result.Username
			clearInteractiveResult(&result)
		}
	default:
		event.Type = "stateChanged"
	}
	emitAuthenticationEvent(listener, event)
}

func discardAuthenticationFlow(flow *auth.InteractiveFlow) {
	currentAuthentication.mu.Lock()
	if currentAuthentication.flow == flow {
		currentAuthentication.flow = nil
		currentAuthentication.listener = nil
		currentAuthentication.server = ""
		currentAuthentication.port = 0
		currentAuthentication.deviceID = ""
		currentAuthentication.busy = false
	}
	currentAuthentication.mu.Unlock()
	flow.Cancel()
}

func sessionRestoreFailure(duration time.Duration, err error) authenticationEvent {
	if errors.Is(err, auth.ErrSessionInvalid) {
		return authenticationEvent{
			SchemaVersion: schemaVersion,
			Type:          "sessionInvalid",
			State:         "idle",
			Code:          "sessionInvalid",
			Message:       "Saved authentication session has expired",
			Stage:         "auth.session_restore",
			Cause:         "authentication",
			DurationMs:    boundedDurationMillis(duration),
		}
	}
	failure := classifyAuthenticationFailure("auth.session_restore", duration, err)
	if failure.code == "authenticationFailed" {
		failure.code = "sessionRestoreUnavailable"
	}
	return authenticationEvent{
		SchemaVersion: schemaVersion,
		Type:          "error",
		State:         "error",
		Code:          failure.code,
		Message:       "Saved authentication session could not be validated",
		Stage:         failure.stage,
		Cause:         failure.cause,
		DurationMs:    failure.durationMs,
	}
}

func retryAuthentication() string {
	currentAuthentication.mu.Lock()
	if currentAuthentication.busy || currentAuthentication.listener == nil {
		currentAuthentication.mu.Unlock()
		return authError("invalidState", "Authentication is not ready to retry")
	}
	oldFlow := currentAuthentication.flow
	flow, err := newAuthenticationFlow(currentAuthentication.server, currentAuthentication.port, currentAuthentication.deviceID)
	if err != nil {
		currentAuthentication.mu.Unlock()
		return authError("initializationFailed", "Unable to restart authentication")
	}
	listener := currentAuthentication.listener
	currentAuthentication.flow = flow
	currentAuthentication.busy = true
	currentAuthentication.mu.Unlock()
	if oldFlow != nil {
		oldFlow.Cancel()
	}
	go runAuthenticationOperation(flow, listener, "auth.config", func() (auth.InteractivePrompt, error) {
		return flow.Begin()
	})
	return marshal(authenticationEvent{
		SchemaVersion: schemaVersion,
		Type:          "retryStarted",
		State:         "fetchingAuthMethods",
		Message:       "Retrying authentication",
	})
}

func authenticationFailure(stage string, duration time.Duration, err error) authenticationEvent {
	failure := classifyAuthenticationFailure(stage, duration, err)
	return authenticationEvent{
		SchemaVersion: schemaVersion,
		Type:          "error",
		State:         "error",
		Code:          failure.code,
		Message:       "Authentication could not be completed",
		Stage:         failure.stage,
		Cause:         failure.cause,
		DurationMs:    failure.durationMs,
	}
}

type authenticationFailureInfo struct {
	code       string
	stage      string
	cause      string
	durationMs int64
}

func classifyAuthenticationFailure(stage string, duration time.Duration, err error) authenticationFailureInfo {
	info := authenticationFailureInfo{
		code:       "authenticationFailed",
		stage:      stage,
		cause:      "authentication",
		durationMs: boundedDurationMillis(duration),
	}
	if err == nil {
		return info
	}

	var dnsError *net.DNSError
	var networkError net.Error
	message := strings.ToLower(err.Error())
	switch {
	case errors.As(err, &dnsError) || strings.Contains(message, "no such host"):
		info.code = "authDnsFailure"
		info.cause = "dns"
	case errors.Is(err, context.DeadlineExceeded) || (errors.As(err, &networkError) && networkError.Timeout()):
		info.code = "authNetworkTimeout"
		info.cause = "timeout"
	case strings.Contains(message, "x509:") || strings.Contains(message, "tls:") ||
		strings.Contains(message, "certificate") || strings.Contains(message, "handshake failure"):
		info.code = "certificateRejected"
		info.cause = "tls"
	case errors.Is(err, syscall.ECONNREFUSED) || errors.Is(err, syscall.ECONNRESET) ||
		errors.Is(err, syscall.ENETUNREACH) || errors.Is(err, syscall.EHOSTUNREACH) ||
		strings.Contains(message, "connection refused") || strings.Contains(message, "network is unreachable") ||
		strings.Contains(message, "no route to host") || strings.Contains(message, "connection reset by peer"):
		info.code = "authNetworkFailure"
		info.cause = "network"
	case errors.Is(err, io.ErrUnexpectedEOF) || strings.Contains(message, "invalid status code") ||
		strings.Contains(message, "invalid character") || strings.Contains(message, "unexpected eof") ||
		strings.Contains(message, "unexpected end of json"):
		info.code = "authProtocolFailure"
		info.cause = "protocol"
	case strings.Contains(message, "http status") || strings.Contains(message, "failed with code"):
		info.code = "authServerFailure"
		info.cause = "server"
	case strings.Contains(message, "unsupported"):
		info.code = "unsupportedAuthMethod"
	case strings.Contains(message, "expected") || strings.Contains(message, "required") || strings.Contains(message, "advertised"):
		info.code = "invalidInput"
	}
	return info
}

func authenticationStage(action string) string {
	switch action {
	case "selectMethod":
		return "auth.select_method"
	case "submitCredentials":
		return "auth.credentials"
	case "submitPhone":
		return "auth.phone"
	case "submitSmsCode":
		return "auth.sms"
	case "submitCaptcha":
		return "auth.captcha"
	case "submitToken":
		return "auth.token"
	default:
		return "auth"
	}
}

func boundedDurationMillis(duration time.Duration) int64 {
	if duration <= 0 {
		return 0
	}
	const maxDiagnosticDuration = 5 * time.Minute
	if duration > maxDiagnosticDuration {
		return maxDiagnosticDuration.Milliseconds()
	}
	return duration.Milliseconds()
}

func emitAuthenticationEvent(listener BridgeListener, event authenticationEvent) {
	if listener != nil {
		listener.OnEvent(marshal(event))
	}
}

func authError(code, message string) string {
	return marshal(authInfoResponse{
		SchemaVersion: schemaVersion,
		Type:          "error",
		Code:          code,
		Message:       message,
	})
}

func marshal(value any) string {
	encoded, err := json.Marshal(value)
	if err != nil {
		return fmt.Sprintf("{\"schemaVersion\":%d,\"type\":\"error\",\"code\":\"serializationFailed\",\"message\":\"Unable to encode Go bridge response\"}", schemaVersion)
	}
	return string(encoded)
}
