// Package core is the Android-facing façade for zju-connect.
//
// Keep this surface constrained to gomobile-compatible values: String and
// callback interfaces. Do not expose upstream clients, credentials, sessions,
// file paths, or file descriptors here without a reviewed lifecycle design.
package core

import (
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"strings"
	"sync"

	"github.com/mythologyli/zju-connect/client/atrust"
	"github.com/mythologyli/zju-connect/client/atrust/auth"
)

const (
	schemaVersion                       = 1
	authenticationSnapshotSchemaVersion = 1
	maxAuthenticationSnapshotSize       = 64 * 1024
	upstreamCommit                      = "7776cdcfa33e3df56ba8da438c17b2274e316128"
	forkCommit                          = "758838e90cdf65c2543845f6d12cae27f0f9ec80"
	upstreamModule                      = "github.com/mythologyli/zju-connect"
	zjuAtrustServer                     = "vpn.zju.edu.cn"
	zjuAtrustServerPort                 = 443
)

// BridgeListener is implemented by Kotlin. Event payloads use versioned JSON
// so later Go changes do not leak complex Go types across the mobile boundary.
type BridgeListener interface {
	OnEvent(eventJSON string)
}

type event struct {
	SchemaVersion  int    `json:"schemaVersion"`
	Type           string `json:"type"`
	Message        string `json:"message"`
	UpstreamModule string `json:"upstreamModule"`
	UpstreamCommit string `json:"upstreamCommit"`
}

type authInfoRequest struct {
	Server string `json:"server"`
	Port   int    `json:"port"`
}

type authInfoResponse struct {
	SchemaVersion int    `json:"schemaVersion"`
	Type          string `json:"type"`
	Code          string `json:"code,omitempty"`
	Message       string `json:"message"`
	AuthMethods   any    `json:"authMethods,omitempty"`
}

type authenticationStartRequest struct {
	Server string `json:"server"`
	Port   int    `json:"port"`
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
}

type authenticationEvent struct {
	SchemaVersion int             `json:"schemaVersion"`
	Type          string          `json:"type"`
	State         string          `json:"state"`
	Code          string          `json:"code,omitempty"`
	Message       string          `json:"message"`
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
	mu       sync.Mutex
	flow     *auth.InteractiveFlow
	listener BridgeListener
	server   string
	port     int
	busy     bool
}

var currentAuthentication authenticationSession

// GetBuildInfo returns a deterministic structured result. It is the smoke-test
// API: calling it proves that Kotlin entered Go code linked with the pinned
// zju-connect source without starting a network connection.
func GetBuildInfo() string {
	return marshal(event{
		SchemaVersion:  schemaVersion,
		Type:           "bridgeReady",
		Message:        "Go bridge response received",
		UpstreamModule: upstreamModule,
		UpstreamCommit: upstreamCommit,
	})
}

// EmitBuildInfo verifies the gomobile callback direction using the same stable
// event schema as future asynchronous authentication events.
func EmitBuildInfo(listener BridgeListener) {
	if listener != nil {
		listener.OnEvent(GetBuildInfo())
	}
}

// FetchAuthInfo obtains the server's advertised aTrust authentication methods.
// It deliberately accepts no credentials and returns only a redacted structured
// result. Password, SMS, CAPTCHA, session persistence, TUN, and socket
// protection are exposed only through their dedicated lifecycle APIs.
func FetchAuthInfo(requestJSON string) string {
	var request authInfoRequest
	if err := json.Unmarshal([]byte(requestJSON), &request); err != nil {
		return authError("invalidRequest", "Authentication-info request is not valid JSON")
	}
	if strings.TrimSpace(request.Server) == "" || request.Port < 1 || request.Port > 65535 {
		return authError("invalidRequest", "Authentication-info request requires a server and valid port")
	}

	methods, err := atrust.GetAuthInfoList(request.Server, request.Port, "", false)
	if err != nil {
		return authError("authInfoUnavailable", "Unable to retrieve authentication methods")
	}

	return marshal(authInfoResponse{
		SchemaVersion: schemaVersion,
		Type:          "authInfo",
		Message:       "Authentication methods retrieved",
		AuthMethods:   methods,
	})
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
	if listener == nil {
		return authError("invalidRequest", "Authentication listener is required")
	}

	currentAuthentication.mu.Lock()
	if currentAuthentication.flow != nil {
		currentAuthentication.mu.Unlock()
		return authError("alreadyRunning", "An authentication session is already active")
	}
	flow, err := newAuthenticationFlow(request.Server, request.Port)
	if err != nil {
		currentAuthentication.mu.Unlock()
		return authError("initializationFailed", "Unable to initialize authentication")
	}
	currentAuthentication.flow = flow
	currentAuthentication.listener = listener
	currentAuthentication.server = request.Server
	currentAuthentication.port = request.Port
	currentAuthentication.busy = true
	currentAuthentication.mu.Unlock()

	go runAuthenticationOperation(flow, listener, func() (auth.InteractivePrompt, error) {
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
	var operation func() (auth.InteractivePrompt, error)
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
	default:
		releaseAuthenticationOperation(flow)
		return authError("invalidRequest", "Unknown authentication response")
	}

	go runAuthenticationOperation(flow, listener, operation)
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
// and the in-memory authentication result. Repeating it is harmless.
func CancelAuthentication() {
	currentAuthentication.mu.Lock()
	flow := currentAuthentication.flow
	listener := currentAuthentication.listener
	currentAuthentication.flow = nil
	currentAuthentication.listener = nil
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

// ClearAuthenticatedResult discards the in-memory success result while
// leaving an otherwise completed session observable to the UI.
func ClearAuthenticatedResult() {
	currentAuthentication.mu.Lock()
	flow := currentAuthentication.flow
	currentAuthentication.mu.Unlock()
	if flow != nil {
		flow.ClearResult()
	}
}

// ExportAuthenticatedSession returns the minimum versioned state needed to
// validate this authentication again. The returned JSON bytes are sensitive:
// callers must encrypt them immediately and must never log them.
func ExportAuthenticatedSession() []byte {
	currentAuthentication.mu.Lock()
	flow := currentAuthentication.flow
	currentAuthentication.mu.Unlock()
	if flow == nil {
		return nil
	}
	result, ok := flow.Result()
	if !ok {
		return nil
	}
	encoded, err := encodeAuthenticationSnapshot(result.AuthData)
	if err != nil {
		return nil
	}
	return encoded
}

// ResumeAuthentication validates an encrypted-at-rest snapshot after Kotlin
// decrypts it. The flow is restricted to ZJU's endpoint and emits only safe
// lifecycle events; snapshot values never enter callback JSON.
func ResumeAuthentication(snapshotBytes []byte, listener BridgeListener) string {
	if listener == nil {
		return authError("invalidRequest", "Authentication listener is required")
	}
	authData, err := decodeAuthenticationSnapshot(snapshotBytes)
	if err != nil {
		return authError("invalidSession", "Saved authentication data is invalid")
	}

	currentAuthentication.mu.Lock()
	if currentAuthentication.flow != nil {
		currentAuthentication.mu.Unlock()
		return authError("alreadyRunning", "An authentication session is already active")
	}
	flow, err := newAuthenticationFlow(zjuAtrustServer, zjuAtrustServerPort)
	if err != nil {
		currentAuthentication.mu.Unlock()
		return authError("initializationFailed", "Unable to initialize session restoration")
	}
	currentAuthentication.flow = flow
	currentAuthentication.listener = listener
	currentAuthentication.server = zjuAtrustServer
	currentAuthentication.port = zjuAtrustServerPort
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

func decodeAuthenticationSnapshot(snapshotBytes []byte) ([]byte, error) {
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
		DeviceID: snapshot.DeviceID,
		Cookies:  append([]auth.Cookie(nil), snapshot.Cookies...),
	})
}

func validateAuthenticationSnapshot(snapshot authenticationSnapshot) error {
	if snapshot.SchemaVersion != authenticationSnapshotSchemaVersion {
		return fmt.Errorf("unsupported authentication snapshot version")
	}
	if len(snapshot.DeviceID) != 32 {
		return fmt.Errorf("device ID length is invalid")
	}
	if _, err := hex.DecodeString(snapshot.DeviceID); err != nil {
		return fmt.Errorf("device ID is invalid")
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

func newAuthenticationFlow(server string, port int) (*auth.InteractiveFlow, error) {
	return auth.NewInteractiveFlow(net.JoinHostPort(server, fmt.Sprint(port)))
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
	operation func() (auth.InteractivePrompt, error),
) {
	prompt, err := operation()
	releaseAuthenticationOperation(flow)
	if !isCurrentAuthenticationFlow(flow, listener) {
		return
	}
	if err != nil {
		emitAuthenticationEvent(listener, authenticationFailure(err))
		return
	}
	event := authenticationEvent{
		SchemaVersion: schemaVersion,
		State:         prompt.State,
		Message:       prompt.Message,
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
	case "authenticated":
		event.Type = "authenticated"
		if result, ok := flow.Result(); ok {
			event.Username = result.Username
		}
	default:
		event.Type = "stateChanged"
	}
	emitAuthenticationEvent(listener, event)
}

func runSessionRestore(flow *auth.InteractiveFlow, listener BridgeListener, authData []byte) {
	defer clear(authData)
	prompt, err := flow.Resume(authData)
	releaseAuthenticationOperation(flow)
	if !isCurrentAuthenticationFlow(flow, listener) {
		return
	}
	if err != nil {
		discardAuthenticationFlow(flow)
		emitAuthenticationEvent(listener, sessionRestoreFailure(err))
		return
	}
	event := authenticationEvent{
		SchemaVersion: schemaVersion,
		Type:          "authenticated",
		State:         prompt.State,
		Message:       prompt.Message,
	}
	if result, ok := flow.Result(); ok {
		event.Username = result.Username
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
		currentAuthentication.busy = false
	}
	currentAuthentication.mu.Unlock()
	flow.Cancel()
}

func sessionRestoreFailure(err error) authenticationEvent {
	if errors.Is(err, auth.ErrSessionInvalid) {
		return authenticationEvent{
			SchemaVersion: schemaVersion,
			Type:          "sessionInvalid",
			State:         "idle",
			Code:          "sessionInvalid",
			Message:       "Saved authentication session has expired",
		}
	}
	code := "sessionRestoreUnavailable"
	if strings.Contains(err.Error(), "x509") || strings.Contains(err.Error(), "certificate") || strings.Contains(err.Error(), "tls:") {
		code = "certificateRejected"
	}
	return authenticationEvent{
		SchemaVersion: schemaVersion,
		Type:          "error",
		State:         "error",
		Code:          code,
		Message:       "Saved authentication session could not be validated",
	}
}

func retryAuthentication() string {
	currentAuthentication.mu.Lock()
	if currentAuthentication.busy || currentAuthentication.listener == nil {
		currentAuthentication.mu.Unlock()
		return authError("invalidState", "Authentication is not ready to retry")
	}
	oldFlow := currentAuthentication.flow
	flow, err := newAuthenticationFlow(currentAuthentication.server, currentAuthentication.port)
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
	go runAuthenticationOperation(flow, listener, func() (auth.InteractivePrompt, error) {
		return flow.Begin()
	})
	return marshal(authenticationEvent{
		SchemaVersion: schemaVersion,
		Type:          "retryStarted",
		State:         "fetchingAuthMethods",
		Message:       "Retrying authentication",
	})
}

func authenticationFailure(err error) authenticationEvent {
	code := "authenticationFailed"
	if strings.Contains(err.Error(), "x509") || strings.Contains(err.Error(), "certificate") || strings.Contains(err.Error(), "tls:") {
		code = "certificateRejected"
	} else if strings.Contains(err.Error(), "unsupported") {
		code = "unsupportedAuthMethod"
	} else if strings.Contains(err.Error(), "expected") || strings.Contains(err.Error(), "required") || strings.Contains(err.Error(), "advertised") {
		code = "invalidInput"
	}
	return authenticationEvent{
		SchemaVersion: schemaVersion,
		Type:          "error",
		State:         "error",
		Code:          code,
		Message:       "Authentication could not be completed",
	}
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
