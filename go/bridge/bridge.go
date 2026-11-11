// Package core is the Android-facing façade for zju-connect.
//
// Keep this surface constrained to gomobile-compatible values: String and
// callback interfaces. Do not expose upstream clients, credentials, sessions,
// file paths, or file descriptors here without a reviewed lifecycle design.
package core

import (
	"bytes"
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	_ "image/gif"
	_ "image/jpeg"
	_ "image/png"
	"io"
	"net"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/mythologyli/zju-connect/client/atrust/auth"
	"github.com/mythologyli/zju-connect/client/authchallenge"
)

const (
	schemaVersion = 1
	// Snapshot v1 predates the experimental custom-server option and always
	// refers to the ZJU production endpoint; v2 records the endpoint inline.
	authenticationSnapshotSchemaVersion = 2
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
	SchemaVersion int    `json:"schemaVersion"`
	DeviceID      string `json:"deviceId"`
	// Server and Port record the aTrust endpoint the cookies belong to.
	// v1 snapshots omit both and always refer to the ZJU endpoint.
	Server  string        `json:"server,omitempty"`
	Port    int           `json:"port,omitempty"`
	Cookies []auth.Cookie `json:"cookies"`
}

type authenticatedResult struct {
	Username     string
	SID          string
	AuthData     []byte
	ResourceData []byte
	// Server and Port record the aTrust endpoint this result authenticated
	// against, so the VPN data plane reconnects to the same service. They
	// identify the origin of the session and are not secrets.
	Server string
	Port   int
}

type upstreamAuthSession interface {
	GetAuthInfoList() ([]auth.AuthInfo, error)
	Login(auth.LoginMethod, auth.LoginOptions) (auth.LoginResult, error)
	ClientResource() ([]byte, error)
}

var newUpstreamAuthSession = func(ctx context.Context, server string) upstreamAuthSession {
	return auth.NewSessionContext(ctx, server, nil)
}

type authenticationSession struct {
	mu                  sync.Mutex
	coordinator         *authenticationCoordinator
	authenticatedResult *authenticatedResult
}

var currentAuthentication authenticationSession

func cloneAuthenticatedResult(result authenticatedResult) authenticatedResult {
	return authenticatedResult{
		Username:     result.Username,
		SID:          result.SID,
		AuthData:     append([]byte(nil), result.AuthData...),
		ResourceData: append([]byte(nil), result.ResourceData...),
		Server:       result.Server,
		Port:         result.Port,
	}
}

func clearAuthenticatedResult(result *authenticatedResult) {
	if result == nil {
		return
	}
	clear(result.AuthData)
	clear(result.ResourceData)
	result.Username = ""
	result.SID = ""
	result.Server = ""
	result.Port = 0
	result.AuthData = nil
	result.ResourceData = nil
}

func isReusableAuthenticatedResult(result authenticatedResult) bool {
	return result.SID != "" && len(result.AuthData) > 0 && len(result.ResourceData) > 0
}

func cacheAuthenticatedResult(result authenticatedResult) {
	cacheAuthenticatedResultForCoordinator(nil, result)
}

func cacheAuthenticatedResultForCoordinator(coordinator *authenticationCoordinator, result authenticatedResult) bool {
	if !isReusableAuthenticatedResult(result) {
		return false
	}
	cached := cloneAuthenticatedResult(result)
	currentAuthentication.mu.Lock()
	if coordinator != nil && currentAuthentication.coordinator != coordinator {
		currentAuthentication.mu.Unlock()
		clearAuthenticatedResult(&cached)
		return false
	}
	previous := currentAuthentication.authenticatedResult
	currentAuthentication.authenticatedResult = &cached
	currentAuthentication.mu.Unlock()
	clearAuthenticatedResult(previous)
	return true
}

func reusableAuthenticatedResult() (authenticatedResult, bool) {
	currentAuthentication.mu.Lock()
	cached := currentAuthentication.authenticatedResult
	if cached == nil || !isReusableAuthenticatedResult(*cached) {
		currentAuthentication.mu.Unlock()
		return authenticatedResult{}, false
	}
	result := cloneAuthenticatedResult(*cached)
	currentAuthentication.mu.Unlock()
	return result, true
}

func currentAuthenticatedResult() (authenticatedResult, bool) {
	return reusableAuthenticatedResult()
}

// StartAuthentication begins a single in-memory aTrust authentication session.
// The target defaults to ZJU's production aTrust endpoint; the experimental
// custom-server setting may point it at another aTrust endpoint, and the UI
// layer is still restricted to a bare host and port so credentials cannot be
// redirected into arbitrary URLs.
func StartAuthentication(requestJSON string, listener BridgeListener) string {
	var request authenticationStartRequest
	if err := json.Unmarshal([]byte(requestJSON), &request); err != nil {
		return authError("invalidRequest", "Authentication request is not valid JSON")
	}
	server, port, err := resolveAuthenticationServer(request.Server, request.Port)
	if err != nil {
		return authError("invalidRequest", "Authentication requires a valid aTrust server host and port")
	}
	if err := validateDeviceID(request.DeviceID); err != nil {
		return authError("invalidRequest", "Authentication requires a valid device identity")
	}
	if listener == nil {
		return authError("invalidRequest", "Authentication listener is required")
	}

	coordinator := newAuthenticationCoordinator(server, port, request.DeviceID, listener)
	currentAuthentication.mu.Lock()
	if currentAuthentication.coordinator != nil {
		currentAuthentication.mu.Unlock()
		coordinator.cancel()
		return authError("alreadyRunning", "An authentication session is already active")
	}
	currentAuthentication.coordinator = coordinator
	currentAuthentication.mu.Unlock()

	go coordinator.fetchAuthMethods("")
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

	coordinator := currentAuthenticationCoordinator()
	if coordinator == nil {
		return authError("invalidState", "Authentication is not ready for another response")
	}

	var err error
	switch response.Action {
	case "selectMethod":
		err = coordinator.selectMethod(response.AuthType, response.LoginDomain)
	case "submitCredentials":
		err = coordinator.submitLogin(auth.LoginMethodOptions{
			AuthType: response.AuthType,
			Username: response.Username,
			Password: response.Password,
		})
	case "submitPhone":
		err = coordinator.submitLogin(auth.LoginMethodOptions{
			AuthType: "auth/smsCheckCode",
			Phone:    response.Phone,
		})
	case "submitSmsCode":
		err = coordinator.submitChallenge(response.Action, response.SMSCode)
	case "submitCaptcha":
		err = coordinator.submitChallenge(response.Action, response.Captcha)
	case "submitToken":
		err = coordinator.submitChallenge(response.Action, response.Token)
	default:
		return authError("invalidRequest", "Unknown authentication response")
	}
	if err != nil {
		return authError("invalidState", "Authentication is not ready for this response")
	}
	return marshal(authenticationEvent{
		SchemaVersion: schemaVersion,
		Type:          "responseAccepted",
		State:         "authenticating",
		Message:       "Authentication response accepted",
	})
}

// GetPendingCaptchaImage returns a copy of the current image bytes only while
// the coordinator is awaiting a captcha response. It never writes a file.
func GetPendingCaptchaImage() []byte {
	coordinator := currentAuthenticationCoordinator()
	if coordinator == nil {
		return nil
	}
	return coordinator.pendingCaptchaImage()
}

// CancelAuthentication clears bridge-owned verification input, captcha bytes,
// and the active coordinator. A completed authenticated result remains
// reusable until ClearAuthenticatedResult is called or the process exits.
// Repeating it is harmless.
func CancelAuthentication() {
	currentAuthentication.mu.Lock()
	coordinator := currentAuthentication.coordinator
	currentAuthentication.coordinator = nil
	currentAuthentication.mu.Unlock()
	if coordinator != nil {
		coordinator.cancel()
		emitAuthenticationEvent(coordinator.listener, authenticationEvent{
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
	ok := result != nil && isReusableAuthenticatedResult(*result)
	currentAuthentication.mu.Unlock()
	return ok
}

// ClearAuthenticatedResult discards the reusable in-process result.
func ClearAuthenticatedResult() {
	currentAuthentication.mu.Lock()
	result := currentAuthentication.authenticatedResult
	currentAuthentication.authenticatedResult = nil
	currentAuthentication.mu.Unlock()
	clearAuthenticatedResult(result)
}

// ExportAuthenticatedSession returns the minimum versioned state needed to
// validate this authentication again. The returned JSON bytes are sensitive:
// callers must encrypt them immediately and must never log them.
func ExportAuthenticatedSession() []byte {
	result, ok := currentAuthenticatedResult()
	if !ok {
		return nil
	}
	defer clearAuthenticatedResult(&result)
	server, port := result.Server, result.Port
	if server == "" || port == 0 {
		server, port = zjuAtrustServer, zjuAtrustServerPort
	}
	encoded, err := encodeAuthenticationSnapshot(result.AuthData, server, port)
	if err != nil {
		return nil
	}
	return encoded
}

// ResumeAuthentication validates an encrypted-at-rest snapshot after Kotlin
// decrypts it. The snapshot records the aTrust endpoint its cookies belong
// to; Kotlin passes the currently configured target and a snapshot from a
// different server is rejected as serverMismatch instead of silently
// reconnecting there. The coordinator emits only safe lifecycle events;
// snapshot values never enter callback JSON.
func ResumeAuthentication(snapshotBytes []byte, deviceID string, server string, port int, listener BridgeListener) string {
	if listener == nil {
		return authError("invalidRequest", "Authentication listener is required")
	}
	if err := validateDeviceID(deviceID); err != nil {
		return authError("invalidRequest", "Session restoration requires a valid device identity")
	}
	expectedServer, expectedPort, err := resolveAuthenticationServer(server, port)
	if err != nil {
		return authError("invalidRequest", "Session restoration requires a valid aTrust server host and port")
	}
	authData, err := decodeAuthenticationSnapshot(snapshotBytes, deviceID, expectedServer, expectedPort)
	if errors.Is(err, errSnapshotServerMismatch) {
		return authError("serverMismatch", "Saved authentication data belongs to a different server")
	}
	if err != nil {
		return authError("invalidSession", "Saved authentication data is invalid")
	}

	var clientAuthData auth.ClientAuthData
	if err := json.Unmarshal(authData, &clientAuthData); err != nil {
		clear(authData)
		return authError("invalidSession", "Saved authentication data is invalid")
	}
	clear(authData)

	coordinator := newAuthenticationCoordinator(expectedServer, expectedPort, deviceID, listener)
	currentAuthentication.mu.Lock()
	if currentAuthentication.coordinator != nil {
		currentAuthentication.mu.Unlock()
		coordinator.cancel()
		return authError("alreadyRunning", "An authentication session is already active")
	}
	currentAuthentication.coordinator = coordinator
	currentAuthentication.mu.Unlock()

	go coordinator.restore(clientAuthData.Cookies)
	return marshal(authenticationEvent{
		SchemaVersion: schemaVersion,
		Type:          "sessionRestoreStarted",
		State:         "restoringSession",
		Message:       "Validating saved authentication session",
	})
}

func encodeAuthenticationSnapshot(authData []byte, server string, port int) ([]byte, error) {
	var clientAuthData auth.ClientAuthData
	if err := json.Unmarshal(authData, &clientAuthData); err != nil {
		return nil, err
	}
	if err := validateAuthenticationServer(server, port); err != nil {
		return nil, err
	}
	snapshot := authenticationSnapshot{
		SchemaVersion: authenticationSnapshotSchemaVersion,
		DeviceID:      clientAuthData.DeviceID,
		Server:        server,
		Port:          port,
		Cookies:       append([]auth.Cookie(nil), clientAuthData.Cookies...),
	}
	if err := validateAuthenticationSnapshot(snapshot); err != nil {
		return nil, err
	}
	return json.Marshal(snapshot)
}

var errSnapshotServerMismatch = errors.New("authentication snapshot belongs to a different server")

func decodeAuthenticationSnapshot(
	snapshotBytes []byte,
	deviceID string,
	expectedServer string,
	expectedPort int,
) ([]byte, error) {
	if len(snapshotBytes) == 0 || len(snapshotBytes) > maxAuthenticationSnapshotSize {
		return nil, fmt.Errorf("authentication snapshot size is invalid")
	}
	var snapshot authenticationSnapshot
	if err := json.Unmarshal(snapshotBytes, &snapshot); err != nil {
		return nil, err
	}
	snapshotServer, snapshotPort, err := snapshotAuthenticationServer(snapshot)
	if err != nil {
		return nil, err
	}
	if snapshotServer != expectedServer || snapshotPort != expectedPort {
		return nil, fmt.Errorf("%w: %s", errSnapshotServerMismatch, snapshotServer)
	}
	if err := validateAuthenticationSnapshot(snapshot); err != nil {
		return nil, err
	}
	return json.Marshal(auth.ClientAuthData{
		DeviceID: deviceID,
		Cookies:  append([]auth.Cookie(nil), snapshot.Cookies...),
	})
}

// snapshotAuthenticationServer resolves the endpoint a snapshot belongs to.
// v1 snapshots predate the custom-server option and only ever targeted the
// ZJU endpoint; v2 snapshots carry their server inline and must be valid.
func snapshotAuthenticationServer(snapshot authenticationSnapshot) (string, int, error) {
	switch snapshot.SchemaVersion {
	case 1:
		return zjuAtrustServer, zjuAtrustServerPort, nil
	case authenticationSnapshotSchemaVersion:
		if snapshot.Server == "" || snapshot.Port == 0 {
			return "", 0, fmt.Errorf("authentication snapshot server is missing")
		}
		if err := validateAuthenticationServer(snapshot.Server, snapshot.Port); err != nil {
			return "", 0, err
		}
		return snapshot.Server, snapshot.Port, nil
	default:
		return "", 0, fmt.Errorf("unsupported authentication snapshot version")
	}
}

func validateAuthenticationSnapshot(snapshot authenticationSnapshot) error {
	if _, _, err := snapshotAuthenticationServer(snapshot); err != nil {
		return err
	}
	if err := validateDeviceID(snapshot.DeviceID); err != nil {
		return err
	}
	if len(snapshot.Cookies) == 0 || len(snapshot.Cookies) > 64 {
		return fmt.Errorf("cookie count is invalid")
	}
	server, port, err := snapshotAuthenticationServer(snapshot)
	if err != nil {
		return err
	}
	expectedHost := net.JoinHostPort(server, fmt.Sprint(port))
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

// resolveAuthenticationServer maps an optional client-supplied target onto the
// effective aTrust endpoint. Empty values keep the ZJU production endpoint, so
// callers that predate the experimental custom-server setting stay valid.
func resolveAuthenticationServer(server string, port int) (string, int, error) {
	if server == "" && port == 0 {
		return zjuAtrustServer, zjuAtrustServerPort, nil
	}
	if err := validateAuthenticationServer(server, port); err != nil {
		return "", 0, err
	}
	return server, port, nil
}

// validateAuthenticationServer accepts only a bare host (hostname, IPv4 or
// IPv6 literal) and a concrete port. Schemes, paths, credentials, and
// whitespace are rejected: the custom-server option points the aTrust client
// at another aTrust endpoint, not at an arbitrary URL.
func validateAuthenticationServer(server string, port int) error {
	if server == "" {
		return fmt.Errorf("server host is required")
	}
	if len(server) > 253 {
		return fmt.Errorf("server host is too long")
	}
	host := server
	if strings.HasPrefix(host, "[") && strings.HasSuffix(host, "]") {
		host = host[1 : len(host)-1]
	}
	if net.ParseIP(host) == nil && !isHostnameLabelChain(host) {
		return fmt.Errorf("server host is not a hostname or IP address")
	}
	if port < 1 || port > 65535 {
		return fmt.Errorf("server port is out of range")
	}
	return nil
}

func isHostnameLabelChain(host string) bool {
	// A trailing dot is a legal root-of-DNS notation; validate without it.
	host = strings.TrimSuffix(host, ".")
	if host == "" {
		return false
	}
	for _, label := range strings.Split(host, ".") {
		if len(label) < 1 || len(label) > 63 {
			return false
		}
		for index, char := range label {
			switch {
			case char >= 'a' && char <= 'z', char >= 'A' && char <= 'Z', char >= '0' && char <= '9':
			case char == '-' && index != 0 && index != len(label)-1:
			default:
				return false
			}
		}
	}
	return true
}

var errAuthenticationCancelled = errors.New("authentication coordinator cancelled")

type challengeResponse struct {
	id      uint64
	code    string
	skip    bool
	captcha authchallenge.ClickCaptchaResponse
}

type pendingAuthenticationChallenge struct {
	id        uint64
	action    string
	responses chan challengeResponse
	ctx       context.Context
	cancel    context.CancelFunc
	image     []byte
	submitted bool
}

type authenticationCoordinator struct {
	mu        sync.Mutex
	session   upstreamAuthSession
	listener  BridgeListener
	server    string
	port      int
	deviceID  string
	ctx       context.Context
	cancelCtx context.CancelFunc
	methods   []auth.AuthInfo
	selected  *auth.AuthInfo
	running   bool
	nextID    uint64
	pending   *pendingAuthenticationChallenge
}

func newAuthenticationCoordinator(server string, port int, deviceID string, listener BridgeListener) *authenticationCoordinator {
	ctx, cancel := context.WithCancel(context.Background())
	return &authenticationCoordinator{
		session:   newUpstreamAuthSession(ctx, net.JoinHostPort(server, fmt.Sprint(port))),
		listener:  listener,
		server:    server,
		port:      port,
		deviceID:  deviceID,
		ctx:       ctx,
		cancelCtx: cancel,
		running:   true,
	}
}

func currentAuthenticationCoordinator() *authenticationCoordinator {
	currentAuthentication.mu.Lock()
	defer currentAuthentication.mu.Unlock()
	return currentAuthentication.coordinator
}

func isCurrentAuthenticationCoordinator(coordinator *authenticationCoordinator) bool {
	currentAuthentication.mu.Lock()
	defer currentAuthentication.mu.Unlock()
	return currentAuthentication.coordinator == coordinator
}

func detachAuthenticationCoordinator(coordinator *authenticationCoordinator) {
	currentAuthentication.mu.Lock()
	if currentAuthentication.coordinator == coordinator {
		currentAuthentication.coordinator = nil
	}
	currentAuthentication.mu.Unlock()
}

func (c *authenticationCoordinator) cancel() {
	c.cancelCtx()
	c.mu.Lock()
	if c.pending != nil {
		c.pending.cancel()
		clear(c.pending.image)
		c.pending.image = nil
	}
	c.mu.Unlock()
}

func (c *authenticationCoordinator) active() bool {
	return c.ctx.Err() == nil && isCurrentAuthenticationCoordinator(c)
}

func (c *authenticationCoordinator) fetchAuthMethods(code string) {
	started := time.Now()
	methods, err := c.session.GetAuthInfoList()
	duration := time.Since(started)

	c.mu.Lock()
	c.running = false
	if err == nil {
		c.methods = append([]auth.AuthInfo(nil), methods...)
	}
	c.mu.Unlock()
	if !c.active() {
		return
	}
	if err != nil {
		emitAuthenticationEvent(c.listener, authenticationFailure("auth.config", duration, err))
		return
	}
	if len(methods) == 0 {
		emitAuthenticationEvent(c.listener, authenticationFailure(
			"auth.config",
			duration,
			fmt.Errorf("server did not advertise a supported authentication method"),
		))
		return
	}
	emitAuthenticationEvent(c.listener, authenticationEvent{
		SchemaVersion: schemaVersion,
		Type:          "authMethodsReady",
		State:         "awaitingMethod",
		Code:          code,
		Message:       "Choose an authentication method",
		Stage:         "auth.config",
		DurationMs:    boundedDurationMillis(duration),
		AuthMethods:   append([]auth.AuthInfo(nil), methods...),
	})
}

func (c *authenticationCoordinator) selectMethod(authType, loginDomain string) error {
	c.mu.Lock()
	if c.ctx.Err() != nil || c.running || c.pending != nil {
		c.mu.Unlock()
		return fmt.Errorf("authentication method is not expected")
	}
	var selected *auth.AuthInfo
	for index := range c.methods {
		method := c.methods[index]
		if method.AuthType == authType && method.LoginDomain == loginDomain {
			selected = &method
			break
		}
	}
	if selected == nil {
		c.mu.Unlock()
		return fmt.Errorf("selected authentication method was not advertised")
	}
	c.selected = selected
	c.mu.Unlock()

	event := authenticationEvent{
		SchemaVersion: schemaVersion,
		State:         "awaitingCredentials",
		Message:       "Enter your account and password",
		Stage:         "auth.select_method",
		Type:          "credentialsRequired",
	}
	switch selected.AuthType {
	case "auth/psw":
	case "auth/smsCheckCode":
		event.Type = "phoneRequired"
		event.State = "awaitingPhone"
		event.Message = "Enter the phone number registered for this method"
	default:
		return fmt.Errorf("unsupported authentication method")
	}
	go func() {
		if c.active() {
			emitAuthenticationEvent(c.listener, event)
		}
	}()
	return nil
}

func (c *authenticationCoordinator) submitLogin(options auth.LoginMethodOptions) error {
	c.mu.Lock()
	if c.ctx.Err() != nil || c.running || c.pending != nil || c.selected == nil {
		c.mu.Unlock()
		return fmt.Errorf("login input is not expected")
	}
	selected := *c.selected
	options.AuthType = selected.AuthType
	options.Domain = selected.LoginDomain
	if selected.AuthType == "auth/psw" && (options.Username == "" || options.Password == "") {
		c.mu.Unlock()
		return fmt.Errorf("account and password are required")
	}
	if selected.AuthType == "auth/smsCheckCode" && options.Phone == "" {
		c.mu.Unlock()
		return fmt.Errorf("phone number is required")
	}
	method, err := auth.NewLoginMethod(options)
	if err != nil {
		c.mu.Unlock()
		return err
	}
	c.running = true
	c.mu.Unlock()

	stage := "auth.credentials"
	if selected.AuthType == "auth/smsCheckCode" {
		stage = "auth.phone"
	}
	go c.login(method, stage)
	return nil
}

func (c *authenticationCoordinator) login(method auth.LoginMethod, stage string) {
	started := time.Now()
	loginResult, err := c.session.Login(method, auth.LoginOptions{
		DeviceID:         c.deviceID,
		ChallengeHandler: c,
	})
	duration := time.Since(started)
	c.mu.Lock()
	c.running = false
	c.mu.Unlock()
	if !c.active() || errors.Is(err, errAuthenticationCancelled) {
		return
	}
	if err != nil {
		if stage == "auth.credentials" && isCredentialsRejectedError(err) {
			emitAuthenticationEvent(c.listener, authenticationEvent{
				SchemaVersion: schemaVersion,
				Type:          "credentialsRequired",
				State:         "awaitingCredentials",
				Code:          "credentialsRejected",
				Message:       "The saved account credentials were rejected",
				Stage:         stage,
				DurationMs:    boundedDurationMillis(duration),
			})
			return
		}
		emitAuthenticationEvent(c.listener, authenticationFailure(stage, duration, err))
		return
	}
	c.completeLogin(loginResult, duration, stage)
}

func (c *authenticationCoordinator) completeLogin(loginResult auth.LoginResult, duration time.Duration, stage string) {
	resourceData, err := c.session.ClientResource()
	if err != nil {
		if c.active() {
			emitAuthenticationEvent(c.listener, authenticationFailure("auth.client_resource", duration, err))
		}
		return
	}
	authData, err := json.Marshal(auth.ClientAuthData{
		DeviceID: c.deviceID,
		Cookies:  append([]auth.Cookie(nil), loginResult.Cookies...),
	})
	if err != nil {
		clear(resourceData)
		if c.active() {
			emitAuthenticationEvent(c.listener, authenticationFailure(stage, duration, err))
		}
		return
	}
	result := authenticatedResult{
		Username:     loginResult.Username,
		SID:          loginResult.SID,
		AuthData:     authData,
		ResourceData: resourceData,
		Server:       c.server,
		Port:         c.port,
	}
	if !cacheAuthenticatedResultForCoordinator(c, result) {
		clearAuthenticatedResult(&result)
		return
	}
	username := result.Username
	clearAuthenticatedResult(&result)
	emitAuthenticationEvent(c.listener, authenticationEvent{
		SchemaVersion: schemaVersion,
		Type:          "authenticated",
		State:         "authenticated",
		Message:       "Authentication completed",
		Stage:         stage,
		DurationMs:    boundedDurationMillis(duration),
		Username:      username,
	})
}

func (c *authenticationCoordinator) restore(cookies []auth.Cookie) {
	started := time.Now()
	loginResult, err := c.session.Login(nil, auth.LoginOptions{
		DeviceID:         c.deviceID,
		Cookies:          append([]auth.Cookie(nil), cookies...),
		ChallengeHandler: c,
	})
	duration := time.Since(started)
	if !c.active() || errors.Is(err, errAuthenticationCancelled) {
		c.mu.Lock()
		c.running = false
		c.mu.Unlock()
		return
	}
	if isSessionStaleError(err) {
		// Session.Login has already restored the cookies and stable DeviceID
		// into this Session. A rejected SID only means that it cannot directly
		// prove authentication; keep the same client context for foreground
		// credential recovery and any server-required challenge.
		c.fetchAuthMethods("sessionExpired")
		return
	}
	c.mu.Lock()
	c.running = false
	c.mu.Unlock()
	if err != nil {
		detachAuthenticationCoordinator(c)
		c.cancel()
		emitAuthenticationEvent(c.listener, sessionRestoreFailure(duration, err))
		return
	}
	c.completeLogin(loginResult, duration, "auth.session_restore")
}

func (c *authenticationCoordinator) beginChallenge(action string, event authenticationEvent, imageData []byte) (*pendingAuthenticationChallenge, error) {
	c.mu.Lock()
	if c.ctx.Err() != nil || c.pending != nil {
		c.mu.Unlock()
		return nil, errAuthenticationCancelled
	}
	c.nextID++
	challengeCtx, cancel := context.WithCancel(c.ctx)
	challenge := &pendingAuthenticationChallenge{
		id:        c.nextID,
		action:    action,
		responses: make(chan challengeResponse, 1),
		ctx:       challengeCtx,
		cancel:    cancel,
		image:     append([]byte(nil), imageData...),
	}
	c.pending = challenge
	c.mu.Unlock()
	if !c.active() {
		c.clearChallenge(challenge)
		return nil, errAuthenticationCancelled
	}
	emitAuthenticationEvent(c.listener, event)
	return challenge, nil
}

func (c *authenticationCoordinator) waitForChallenge(challenge *pendingAuthenticationChallenge) (challengeResponse, error) {
	defer c.clearChallenge(challenge)
	select {
	case response := <-challenge.responses:
		if response.id != challenge.id {
			return challengeResponse{}, fmt.Errorf("stale authentication challenge response")
		}
		return response, nil
	case <-challenge.ctx.Done():
		return challengeResponse{}, errAuthenticationCancelled
	}
}

func (c *authenticationCoordinator) clearChallenge(challenge *pendingAuthenticationChallenge) {
	c.mu.Lock()
	if c.pending != nil && c.pending.id == challenge.id {
		c.pending = nil
	}
	clear(challenge.image)
	challenge.image = nil
	challenge.cancel()
	c.mu.Unlock()
}

func (c *authenticationCoordinator) submitChallenge(action, raw string) error {
	c.mu.Lock()
	challenge := c.pending
	if challenge == nil || challenge.action != action || challenge.submitted || challenge.ctx.Err() != nil {
		c.mu.Unlock()
		return fmt.Errorf("authentication challenge response is not expected")
	}
	response := challengeResponse{id: challenge.id}
	switch action {
	case "submitSmsCode":
		response.code = strings.TrimSpace(raw)
		if response.code == "" {
			c.mu.Unlock()
			return fmt.Errorf("SMS code is required")
		}
	case "submitToken":
		response.code = strings.TrimSpace(raw)
		if strings.HasPrefix(response.code, "$") {
			response.skip = true
			response.code = strings.TrimSpace(strings.TrimPrefix(response.code, "$"))
		}
		if response.code == "" {
			c.mu.Unlock()
			return fmt.Errorf("authentication token is required")
		}
	case "submitCaptcha":
		var payload struct {
			Coordinates [][]int `json:"coordinates"`
			Width       int     `json:"width"`
			Height      int     `json:"height"`
		}
		if err := json.Unmarshal([]byte(raw), &payload); err != nil || payload.Width <= 0 || payload.Height <= 0 || len(payload.Coordinates) == 0 {
			c.mu.Unlock()
			return fmt.Errorf("captcha response is invalid")
		}
		points := make([]authchallenge.Point, 0, len(payload.Coordinates))
		for _, pair := range payload.Coordinates {
			if len(pair) != 2 {
				c.mu.Unlock()
				return fmt.Errorf("captcha response is invalid")
			}
			points = append(points, authchallenge.Point{X: pair[0], Y: pair[1]})
		}
		response.captcha = authchallenge.ClickCaptchaResponse{Points: points, Width: payload.Width, Height: payload.Height}
	default:
		c.mu.Unlock()
		return fmt.Errorf("unsupported authentication challenge response")
	}
	challenge.submitted = true
	responses := challenge.responses
	challengeCtx := challenge.ctx
	c.mu.Unlock()

	select {
	case responses <- response:
		return nil
	case <-challengeCtx.Done():
		return fmt.Errorf("authentication challenge is no longer active")
	}
}

func (c *authenticationCoordinator) pendingCaptchaImage() []byte {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.pending == nil || c.pending.action != "submitCaptcha" || c.pending.ctx.Err() != nil {
		return nil
	}
	return append([]byte(nil), c.pending.image...)
}

func (c *authenticationCoordinator) HandleCodeChallenge(challenge authchallenge.CodeChallenge) (authchallenge.CodeResponse, error) {
	event := authenticationEvent{
		SchemaVersion: schemaVersion,
		State:         "awaitingToken",
		Type:          "tokenRequired",
		Message:       "Enter the authentication token requested by the server",
	}
	action := "submitToken"
	switch challenge.Kind {
	case authchallenge.CodeSMS:
		event.Type = "smsRequired"
		event.State = "awaitingSms"
		event.Message = "Enter the SMS verification code"
		event.ChallengeKind = "auth/sms"
		action = "submitSmsCode"
	case authchallenge.CodeTOTP:
		event.ChallengeKind = "auth/totp"
	case authchallenge.CodeRadius:
		event.ChallengeKind = "auth/radius"
	default:
		return authchallenge.CodeResponse{}, fmt.Errorf("unsupported authentication code challenge")
	}
	pending, err := c.beginChallenge(action, event, nil)
	if err != nil {
		return authchallenge.CodeResponse{}, err
	}
	response, err := c.waitForChallenge(pending)
	return authchallenge.CodeResponse{Code: response.code, SkipSecondaryAuth: response.skip}, err
}

func (c *authenticationCoordinator) HandleClickCaptcha(challenge authchallenge.ClickCaptchaChallenge) (authchallenge.ClickCaptchaResponse, error) {
	config, _, err := image.DecodeConfig(bytes.NewReader(challenge.Image))
	if err != nil {
		return authchallenge.ClickCaptchaResponse{}, fmt.Errorf("decode captcha image: %w", err)
	}
	pending, err := c.beginChallenge("submitCaptcha", authenticationEvent{
		SchemaVersion: schemaVersion,
		Type:          "captchaRequired",
		State:         "awaitingCaptcha",
		Message:       "Complete the graphical verification",
		CaptchaWidth:  config.Width,
		CaptchaHeight: config.Height,
	}, challenge.Image)
	if err != nil {
		return authchallenge.ClickCaptchaResponse{}, err
	}
	response, err := c.waitForChallenge(pending)
	return response.captcha, err
}

func (c *authenticationCoordinator) HandleTextCaptcha(authchallenge.TextCaptchaChallenge) (authchallenge.TextCaptchaResponse, error) {
	return authchallenge.TextCaptchaResponse{}, fmt.Errorf("unsupported text captcha challenge")
}

func (c *authenticationCoordinator) HandleExternalLogin(challenge authchallenge.ExternalLoginChallenge) (authchallenge.ExternalLoginResponse, error) {
	return authchallenge.ExternalLoginResponse{}, fmt.Errorf("unsupported external login challenge: %s", challenge.Kind)
}

func sessionRestoreFailure(duration time.Duration, err error) authenticationEvent {
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

func isSessionStaleError(err error) bool {
	if err == nil {
		return false
	}
	message := err.Error()
	return message == "login method is nil, but user is not logged in" || message == "aTrust session is not authenticated"
}

func isCredentialsRejectedError(err error) bool {
	if err == nil {
		return false
	}
	message := err.Error()
	return message == "aTrust credentials rejected" ||
		strings.HasPrefix(message, "aTrust credentials rejected: ") ||
		strings.HasPrefix(message, "password authentication failed with code ")
}

func retryAuthentication() string {
	currentAuthentication.mu.Lock()
	oldCoordinator := currentAuthentication.coordinator
	if oldCoordinator == nil {
		currentAuthentication.mu.Unlock()
		return authError("invalidState", "Authentication is not ready to retry")
	}
	oldCoordinator.mu.Lock()
	if oldCoordinator.running || oldCoordinator.pending != nil || oldCoordinator.ctx.Err() != nil {
		oldCoordinator.mu.Unlock()
		currentAuthentication.mu.Unlock()
		return authError("invalidState", "Authentication is not ready to retry")
	}
	coordinator := newAuthenticationCoordinator(
		oldCoordinator.server,
		oldCoordinator.port,
		oldCoordinator.deviceID,
		oldCoordinator.listener,
	)
	oldCoordinator.mu.Unlock()
	currentAuthentication.coordinator = coordinator
	currentAuthentication.mu.Unlock()
	oldCoordinator.cancel()
	go coordinator.fetchAuthMethods("")
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
