package core

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/x509"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"net"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	"github.com/mythologyli/zju-connect/client/atrust/auth"
	"github.com/mythologyli/zju-connect/client/authchallenge"
)

func TestVerifyPinnedNodeSPKI(t *testing.T) {
	certificate := &x509.Certificate{RawSubjectPublicKeyInfo: []byte("expected-node-key")}
	expected := sha256.Sum256(certificate.RawSubjectPublicKeyInfo)

	if err := verifyPinnedNodeSPKI([]*x509.Certificate{certificate}, [][sha256.Size]byte{expected}); err != nil {
		t.Fatalf("matching node pin rejected: %v", err)
	}
	if err := verifyPinnedNodeSPKI(nil, [][sha256.Size]byte{expected}); err == nil {
		t.Fatal("missing node certificate was accepted")
	}
	if err := verifyPinnedNodeSPKI(
		[]*x509.Certificate{{RawSubjectPublicKeyInfo: []byte("unexpected-node-key")}},
		[][sha256.Size]byte{expected},
	); err == nil {
		t.Fatal("unexpected node certificate was accepted")
	}
}

func TestZjuAtrustNodeTLSConfigPinsDespiteCustomVerification(t *testing.T) {
	config := zjuAtrustNodeTLSConfig()
	if !config.InsecureSkipVerify || config.VerifyConnection == nil {
		t.Fatal("node TLS config must replace appliance PKI validation with a mandatory pin")
	}
}

type recordingListener struct {
	mu     sync.Mutex
	events []string
}

func (l *recordingListener) OnEvent(eventJSON string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.events = append(l.events, eventJSON)
}

func (l *recordingListener) snapshot() []string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return append([]string(nil), l.events...)
}

func (l *recordingListener) waitForType(t *testing.T, eventType string) authenticationEvent {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		for _, encoded := range l.snapshot() {
			var event authenticationEvent
			if json.Unmarshal([]byte(encoded), &event) == nil && event.Type == eventType {
				return event
			}
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("timed out waiting for %q in %v", eventType, l.snapshot())
	return authenticationEvent{}
}

func TestStartAuthenticationValidatesEndpointWithoutNetworking(t *testing.T) {
	defer CancelAuthentication()

	result := StartAuthentication(`{"server":"example.com","port":443}`, &recordingListener{})
	var response authInfoResponse
	if err := json.Unmarshal([]byte(result), &response); err != nil {
		t.Fatalf("StartAuthentication returned invalid JSON: %v", err)
	}
	if response.Code != "invalidRequest" {
		t.Errorf("code = %q, want invalidRequest", response.Code)
	}
}

func TestStartAuthenticationRequiresDeviceIdentityWithoutNetworking(t *testing.T) {
	result := StartAuthentication(`{"server":"vpn.zju.edu.cn","port":443,"deviceId":"invalid"}`, &recordingListener{})
	var response authInfoResponse
	if err := json.Unmarshal([]byte(result), &response); err != nil {
		t.Fatal(err)
	}
	if response.Code != "invalidRequest" {
		t.Fatalf("code = %q, want invalidRequest", response.Code)
	}
}

func TestReusableAuthenticatedResultSurvivesCancellation(t *testing.T) {
	CancelAuthentication()
	ClearAuthenticatedResult()
	defer func() {
		CancelAuthentication()
		ClearAuthenticatedResult()
	}()

	authData, err := json.Marshal(auth.ClientAuthData{
		DeviceID: "0123456789abcdef0123456789abcdef",
		Cookies: []auth.Cookie{
			{Host: "vpn.zju.edu.cn:443", Scheme: "https", Name: "sid", Value: "session-cookie"},
		},
	})
	if err != nil {
		t.Fatalf("Marshal auth data: %v", err)
	}

	result := authenticatedResult{
		Username:     "student",
		SID:          "session-id",
		AuthData:     authData,
		ResourceData: []byte(`{"resource":"opaque"}`),
	}
	cacheAuthenticatedResult(result)
	clearAuthenticatedResult(&result)

	if !HasReusableAuthenticatedResult() {
		t.Fatal("authenticated result was not cached")
	}

	CancelAuthentication()
	if !HasReusableAuthenticatedResult() {
		t.Fatal("cancelling the interactive flow cleared the reusable result")
	}

	reusable, ok := currentAuthenticatedResult()
	if !ok || reusable.Username != "student" || reusable.SID != "session-id" {
		t.Fatalf("cached result = %#v, ok=%v", reusable, ok)
	}
	clearAuthenticatedResult(&reusable)

	snapshot := ExportAuthenticatedSession()
	if len(snapshot) == 0 {
		t.Fatal("cached result could not export the encrypted-session input")
	}
	if !strings.Contains(string(snapshot), "session-cookie") {
		t.Fatalf("exported snapshot omitted the expected cookie: %s", snapshot)
	}
	clear(snapshot)

	ClearAuthenticatedResult()
	if HasReusableAuthenticatedResult() {
		t.Fatal("explicit result clear left a reusable result")
	}
}

func TestIncompleteAuthenticatedResultIsNotReusable(t *testing.T) {
	CancelAuthentication()
	ClearAuthenticatedResult()
	defer ClearAuthenticatedResult()

	cacheAuthenticatedResult(authenticatedResult{SID: "session-id"})
	if HasReusableAuthenticatedResult() {
		t.Fatal("incomplete authenticated result was marked reusable")
	}
}

type fakeAuthSession struct {
	methods      []auth.AuthInfo
	login        func(auth.LoginMethod, auth.LoginOptions) (auth.LoginResult, error)
	resourceData []byte
	resourceErr  error
}

func (s *fakeAuthSession) GetAuthInfoList() ([]auth.AuthInfo, error) {
	return append([]auth.AuthInfo(nil), s.methods...), nil
}

func (s *fakeAuthSession) Login(method auth.LoginMethod, options auth.LoginOptions) (auth.LoginResult, error) {
	if s.login == nil {
		return auth.LoginResult{}, errors.New("unexpected login")
	}
	return s.login(method, options)
}

func (s *fakeAuthSession) ClientResource() ([]byte, error) {
	return append([]byte(nil), s.resourceData...), s.resourceErr
}

func installTestCoordinator(t *testing.T, session upstreamAuthSession) (*authenticationCoordinator, *recordingListener) {
	t.Helper()
	CancelAuthentication()
	ClearAuthenticatedResult()
	listener := &recordingListener{}
	coordinator := newAuthenticationCoordinator(
		zjuAtrustServer,
		zjuAtrustServerPort,
		"0123456789abcdef0123456789abcdef",
		listener,
	)
	coordinator.session = session
	coordinator.running = false
	currentAuthentication.mu.Lock()
	currentAuthentication.coordinator = coordinator
	currentAuthentication.mu.Unlock()
	t.Cleanup(func() {
		detachAuthenticationCoordinator(coordinator)
		coordinator.cancel()
		ClearAuthenticatedResult()
	})
	return coordinator, listener
}

func TestCoordinatorUsesUpstreamLoginAndBuildsReusableResult(t *testing.T) {
	loginCall := make(chan struct {
		authType string
		domain   string
		handler  authchallenge.Handler
	}, 1)
	session := &fakeAuthSession{
		methods: []auth.AuthInfo{{AuthType: "auth/psw", LoginDomain: "default", AuthName: "Password"}},
		login: func(method auth.LoginMethod, options auth.LoginOptions) (auth.LoginResult, error) {
			loginCall <- struct {
				authType string
				domain   string
				handler  authchallenge.Handler
			}{method.AuthType(), method.LoginDomain(), options.ChallengeHandler}
			return auth.LoginResult{
				Username: "student",
				SID:      "secret-session-id",
				Cookies: []auth.Cookie{{
					Host: "vpn.zju.edu.cn:443", Scheme: "https", Name: "sid", Value: "secret-cookie",
				}},
			}, nil
		},
		resourceData: []byte(`{"resource":"opaque"}`),
	}
	coordinator, listener := installTestCoordinator(t, session)
	coordinator.running = true
	coordinator.fetchAuthMethods()
	listener.waitForType(t, "authMethodsReady")

	if err := coordinator.selectMethod("auth/psw", "default"); err != nil {
		t.Fatal(err)
	}
	listener.waitForType(t, "credentialsRequired")
	if err := coordinator.submitLogin(auth.LoginMethodOptions{Username: "student", Password: "secret-password"}); err != nil {
		t.Fatal(err)
	}
	authenticated := listener.waitForType(t, "authenticated")
	if authenticated.Username != "student" || !HasReusableAuthenticatedResult() {
		t.Fatalf("authenticated event/result = %#v, reusable=%v", authenticated, HasReusableAuthenticatedResult())
	}
	call := <-loginCall
	if call.authType != "auth/psw" || call.domain != "default" || call.handler != coordinator {
		t.Fatalf("upstream login call = %#v", call)
	}
	for _, encoded := range listener.snapshot() {
		for _, secret := range []string{"secret-password", "secret-cookie", "secret-session-id"} {
			if strings.Contains(encoded, secret) {
				t.Fatalf("callback leaked %q: %s", secret, encoded)
			}
		}
	}
}

func TestChallengeResponsesAreIsolatedByIdentity(t *testing.T) {
	coordinator, listener := installTestCoordinator(t, &fakeAuthSession{})

	firstResult := make(chan authchallenge.CodeResponse, 1)
	firstErr := make(chan error, 1)
	go func() {
		response, err := coordinator.HandleCodeChallenge(authchallenge.CodeChallenge{Kind: authchallenge.CodeTOTP})
		firstResult <- response
		firstErr <- err
	}()
	listener.waitForType(t, "tokenRequired")
	coordinator.mu.Lock()
	firstChallenge := coordinator.pending
	coordinator.mu.Unlock()
	if accepted := SubmitAuthentication(`{"action":"submitToken","token":"$ 123456"}`); !strings.Contains(accepted, `"type":"responseAccepted"`) {
		t.Fatalf("token submission was not accepted: %s", accepted)
	}
	if err := <-firstErr; err != nil {
		t.Fatal(err)
	}
	if response := <-firstResult; response.Code != "123456" || !response.SkipSecondaryAuth {
		t.Fatalf("first response = %#v", response)
	}

	secondResult := make(chan authchallenge.CodeResponse, 1)
	secondErr := make(chan error, 1)
	go func() {
		response, err := coordinator.HandleCodeChallenge(authchallenge.CodeChallenge{Kind: authchallenge.CodeRadius})
		secondResult <- response
		secondErr <- err
	}()
	deadline := time.Now().Add(time.Second)
	var secondChallenge *pendingAuthenticationChallenge
	for time.Now().Before(deadline) {
		coordinator.mu.Lock()
		secondChallenge = coordinator.pending
		coordinator.mu.Unlock()
		if secondChallenge != nil && secondChallenge.id != firstChallenge.id {
			break
		}
		time.Sleep(time.Millisecond)
	}
	if secondChallenge == nil || secondChallenge.id == firstChallenge.id {
		t.Fatal("second challenge was not installed with a fresh identity")
	}

	select {
	case firstChallenge.responses <- challengeResponse{id: firstChallenge.id, code: "late-secret"}:
	default:
		t.Fatal("completed response channel was unexpectedly closed or blocked")
	}
	select {
	case <-secondResult:
		t.Fatal("late response from the first challenge reached the second challenge")
	case <-time.After(20 * time.Millisecond):
	}
	if accepted := SubmitAuthentication(`{"action":"submitToken","token":"654321"}`); !strings.Contains(accepted, `"type":"responseAccepted"`) {
		t.Fatalf("second token submission was not accepted: %s", accepted)
	}
	if err := <-secondErr; err != nil {
		t.Fatal(err)
	}
	if response := <-secondResult; response.Code != "654321" {
		t.Fatalf("second response = %#v", response)
	}
}

func TestChallengeCancellationUsesDoneSignalWithoutClosingResponses(t *testing.T) {
	coordinator, listener := installTestCoordinator(t, &fakeAuthSession{})
	result := make(chan error, 1)
	go func() {
		_, err := coordinator.HandleCodeChallenge(authchallenge.CodeChallenge{Kind: authchallenge.CodeSMS})
		result <- err
	}()
	listener.waitForType(t, "smsRequired")
	coordinator.mu.Lock()
	challenge := coordinator.pending
	coordinator.mu.Unlock()
	coordinator.cancel()
	if err := <-result; !errors.Is(err, errAuthenticationCancelled) {
		t.Fatalf("cancelled handler error = %v", err)
	}
	select {
	case challenge.responses <- challengeResponse{id: challenge.id, code: "late"}:
	default:
		t.Fatal("cancellation closed or blocked the response channel")
	}
}

func TestClickCaptchaUsesExistingCoordinateSubmission(t *testing.T) {
	coordinator, listener := installTestCoordinator(t, &fakeAuthSession{})
	imageBuffer := new(bytes.Buffer)
	pixels := image.NewRGBA(image.Rect(0, 0, 20, 10))
	pixels.Set(1, 1, color.White)
	if err := png.Encode(imageBuffer, pixels); err != nil {
		t.Fatal(err)
	}
	result := make(chan authchallenge.ClickCaptchaResponse, 1)
	errResult := make(chan error, 1)
	go func() {
		response, err := coordinator.HandleClickCaptcha(authchallenge.ClickCaptchaChallenge{Image: imageBuffer.Bytes()})
		result <- response
		errResult <- err
	}()
	event := listener.waitForType(t, "captchaRequired")
	if event.CaptchaWidth != 20 || event.CaptchaHeight != 10 || len(coordinator.pendingCaptchaImage()) == 0 {
		t.Fatalf("captcha event/image = %#v, bytes=%d", event, len(coordinator.pendingCaptchaImage()))
	}
	if accepted := SubmitAuthentication(`{"action":"submitCaptcha","captcha":"{\"coordinates\":[[4,5]],\"width\":20,\"height\":10}"}`); !strings.Contains(accepted, `"type":"responseAccepted"`) {
		t.Fatalf("captcha submission was not accepted: %s", accepted)
	}
	if err := <-errResult; err != nil {
		t.Fatal(err)
	}
	response := <-result
	if len(response.Points) != 1 || response.Points[0].X != 4 || response.Points[0].Y != 5 {
		t.Fatalf("captcha response = %#v", response)
	}
}

func TestAuthenticationFailureIsRedacted(t *testing.T) {
	secret := "session-cookie-and-password"
	event := authenticationFailure("auth.config", 0, fmt.Errorf("x509: certificate rejected: %s", secret))
	encoded := marshal(event)
	if event.Code != "certificateRejected" {
		t.Errorf("code = %q, want certificateRejected", event.Code)
	}
	if strings.Contains(encoded, secret) {
		t.Fatalf("authentication event leaked a secret: %s", encoded)
	}
}

func TestAuthenticationFailureClassifiesSafeTransportDetails(t *testing.T) {
	timeout := authenticationFailure("auth.config", 20*time.Second, context.DeadlineExceeded)
	if timeout.Code != "authNetworkTimeout" || timeout.Stage != "auth.config" || timeout.Cause != "timeout" {
		t.Fatalf("timeout event = %#v", timeout)
	}
	if timeout.DurationMs != 20_000 {
		t.Fatalf("timeout duration = %d, want 20000", timeout.DurationMs)
	}

	dns := authenticationFailure("auth.config", time.Second, &net.DNSError{Err: "lookup failed", Name: "vpn.zju.edu.cn"})
	if dns.Code != "authDnsFailure" || dns.Cause != "dns" {
		t.Fatalf("DNS event = %#v", dns)
	}

	protocol := authenticationFailure("auth.config", 2*time.Second, fmt.Errorf("invalid character '<' looking for beginning of value"))
	if protocol.Code != "authProtocolFailure" || protocol.Cause != "protocol" {
		t.Fatalf("protocol event = %#v", protocol)
	}
}

func TestAuthenticationSnapshotContainsOnlyMinimumState(t *testing.T) {
	host := "vpn.zju.edu.cn:443"
	authData, err := json.Marshal(auth.ClientAuthData{
		DeviceID: "ABCDEF0123456789ABCDEF0123456789",
		Cookies: []auth.Cookie{
			{Host: host, Scheme: "https", Name: "sid", Value: "session-cookie"},
			{Host: host, Scheme: "https", Name: "sid.sig", Value: "session-signature"},
		},
	})
	if err != nil {
		t.Fatalf("Marshal auth data: %v", err)
	}

	encoded, err := encodeAuthenticationSnapshot(authData)
	if err != nil {
		t.Fatalf("encodeAuthenticationSnapshot: %v", err)
	}
	var snapshot map[string]json.RawMessage
	if err := json.Unmarshal(encoded, &snapshot); err != nil {
		t.Fatalf("Unmarshal snapshot: %v", err)
	}
	for _, required := range []string{"schemaVersion", "deviceId", "cookies"} {
		if _, ok := snapshot[required]; !ok {
			t.Errorf("snapshot omitted %q", required)
		}
	}
	for _, forbidden := range []string{"password", "username", "sid", "resourceData", "connectionId", "signKey", "createdAt"} {
		if _, ok := snapshot[forbidden]; ok {
			t.Errorf("snapshot contained forbidden field %q", forbidden)
		}
	}

	const currentDeviceID = "11111111111111111111111111111111"
	decoded, err := decodeAuthenticationSnapshot(encoded, currentDeviceID)
	if err != nil {
		t.Fatalf("decodeAuthenticationSnapshot: %v", err)
	}
	var roundTrip auth.ClientAuthData
	if err := json.Unmarshal(decoded, &roundTrip); err != nil {
		t.Fatalf("Unmarshal round trip: %v", err)
	}
	if roundTrip.DeviceID != currentDeviceID || len(roundTrip.Cookies) != 2 {
		t.Fatalf("round-trip authentication data = %#v", roundTrip)
	}
}

func TestAuthenticationSnapshotRejectsInvalidScopeAndVersion(t *testing.T) {
	tests := []authenticationSnapshot{
		{SchemaVersion: 2, DeviceID: "0123456789abcdef0123456789abcdef", Cookies: []auth.Cookie{{Host: "vpn.zju.edu.cn:443", Scheme: "https", Name: "sid", Value: "value"}}},
		{SchemaVersion: 1, DeviceID: "not-a-device-id", Cookies: []auth.Cookie{{Host: "vpn.zju.edu.cn:443", Scheme: "https", Name: "sid", Value: "value"}}},
		{SchemaVersion: 1, DeviceID: "0123456789abcdef0123456789abcdef", Cookies: []auth.Cookie{{Host: "example.com:443", Scheme: "https", Name: "sid", Value: "value"}}},
		{SchemaVersion: 1, DeviceID: "0123456789abcdef0123456789abcdef", Cookies: []auth.Cookie{{Host: "vpn.zju.edu.cn:443", Scheme: "https", Name: "sid.sig", Value: "value"}}},
	}
	for _, snapshot := range tests {
		if err := validateAuthenticationSnapshot(snapshot); err == nil {
			t.Errorf("invalid snapshot accepted: %#v", snapshot)
		}
	}
}

func TestSessionRestoreFailureDistinguishesExpiryAndRedactsErrors(t *testing.T) {
	invalid := sessionRestoreFailure(0, errors.New("login method is nil, but user is not logged in"))
	if invalid.Type != "sessionInvalid" || invalid.Code != "sessionInvalid" || invalid.State != "idle" {
		t.Fatalf("invalid-session event = %#v", invalid)
	}

	secret := "cookie-value-that-must-not-leak"
	unavailable := sessionRestoreFailure(0, errors.New("network failure: "+secret))
	encoded := marshal(unavailable)
	if unavailable.Code != "sessionRestoreUnavailable" || strings.Contains(encoded, secret) {
		t.Fatalf("restore failure leaked or misclassified: %s", encoded)
	}
}

func TestUpstreamErrorShimsAreNarrow(t *testing.T) {
	if !isSessionInvalidError(errors.New("login method is nil, but user is not logged in")) {
		t.Fatal("current upstream invalid-session error was not recognized")
	}
	if isSessionInvalidError(errors.New("network failure while login method is nil")) {
		t.Fatal("unrelated restore failure was classified as invalid session")
	}
	if !isCredentialsRejectedError(errors.New("password authentication failed with code 1001: rejected")) {
		t.Fatal("current upstream credential rejection was not recognized")
	}
	if isCredentialsRejectedError(errors.New("SMS authentication failed with code 1001")) {
		t.Fatal("non-password failure was classified as credential rejection")
	}
}

func TestCoordinatorMapsInvalidRestoredSessionToExistingEvent(t *testing.T) {
	session := &fakeAuthSession{
		login: func(method auth.LoginMethod, options auth.LoginOptions) (auth.LoginResult, error) {
			if method != nil || len(options.Cookies) != 1 {
				t.Fatalf("restore login inputs = method %v, options %#v", method, options)
			}
			return auth.LoginResult{}, errors.New("login method is nil, but user is not logged in")
		},
	}
	coordinator, listener := installTestCoordinator(t, session)
	coordinator.running = true
	coordinator.restore([]auth.Cookie{{
		Host: "vpn.zju.edu.cn:443", Scheme: "https", Name: "sid", Value: "secret-cookie",
	}})
	event := listener.waitForType(t, "sessionInvalid")
	if event.Code != "sessionInvalid" || event.State != "idle" {
		t.Fatalf("session-invalid event = %#v", event)
	}
	for _, encoded := range listener.snapshot() {
		if strings.Contains(encoded, "secret-cookie") {
			t.Fatalf("restore callback leaked a cookie: %s", encoded)
		}
	}
}

func TestResumeAuthenticationRejectsMalformedSnapshotWithoutNetworking(t *testing.T) {
	result := ResumeAuthentication([]byte(`{"schemaVersion":1}`), "0123456789abcdef0123456789abcdef", &recordingListener{})
	var response authInfoResponse
	if err := json.Unmarshal([]byte(result), &response); err != nil {
		t.Fatalf("ResumeAuthentication returned invalid JSON: %v", err)
	}
	if response.Code != "invalidSession" {
		t.Fatalf("ResumeAuthentication code = %q, want invalidSession", response.Code)
	}
}

func TestRealVpnErrorIsRedactedAndVersioned(t *testing.T) {
	encoded := realVpnErrorWithCause("vpnSetupFailed", "prepare.setup", "timeout", "Unable to prepare the authenticated aTrust VPN")

	var event realVpnPreparedEvent
	if err := json.Unmarshal([]byte(encoded), &event); err != nil {
		t.Fatalf("realVpnError returned invalid JSON: %v", err)
	}
	if event.SchemaVersion != schemaVersion || event.Type != "error" || event.State != "error" {
		t.Fatalf("unexpected real VPN error event: %#v", event)
	}
	if event.Stage != "prepare.setup" {
		t.Errorf("stage = %q, want prepare.setup", event.Stage)
	}
	if event.Cause != "timeout" {
		t.Errorf("cause = %q, want timeout", event.Cause)
	}
	for _, forbidden := range []string{"password", "cookie", "sid", "deviceId", "signKey"} {
		if strings.Contains(encoded, forbidden) {
			t.Fatalf("real VPN error contained forbidden field %q: %s", forbidden, encoded)
		}
	}
}

func TestRealVpnPreparationTimeoutIsStableAndRedacted(t *testing.T) {
	encoded := realVpnPreparationEvent(
		"error",
		"vpnPrepareTimeout",
		"prepare.nodeProbe",
		"timeout",
		30_000,
		"Timed out while preparing the authenticated aTrust VPN",
	)
	var event realVpnPreparedEvent
	if err := json.Unmarshal([]byte(encoded), &event); err != nil {
		t.Fatalf("timeout event was not valid JSON: %v", err)
	}
	if event.State != "error" || event.Code != "vpnPrepareTimeout" || event.Stage != "prepare.nodeProbe" || event.Cause != "timeout" {
		t.Fatalf("timeout event = %#v", event)
	}
	if event.DurationMillis != 30_000 {
		t.Fatalf("timeout duration = %d, want 30000", event.DurationMillis)
	}
	for _, forbidden := range []string{"cookie", "sid", "deviceId", "resource", "endpoint"} {
		if strings.Contains(encoded, forbidden) {
			t.Fatalf("timeout event contained forbidden field %q: %s", forbidden, encoded)
		}
	}
}

func TestClassifyTunWriteErrorUsesOnlyStableCategories(t *testing.T) {
	tests := []struct {
		err  error
		want string
	}{
		{err: syscall.EBADF, want: "fdClosed"},
		{err: syscall.EAGAIN, want: "wouldBlock"},
		{err: syscall.EMSGSIZE, want: "packetTooLarge"},
		{err: syscall.EINVAL, want: "invalidPacket"},
		{err: syscall.EIO, want: "tunUnavailable"},
		{err: fmt.Errorf("provider response contained credential-like text"), want: "io"},
	}

	for _, test := range tests {
		if got := classifyTunWriteError(test.err); got != test.want {
			t.Errorf("classifyTunWriteError(%v) = %q, want %q", test.err, got, test.want)
		}
	}
}

func TestInspectRealVpnPacketReportsOnlySafeMetadata(t *testing.T) {
	packet := buildMarkedPacket()
	meta := inspectRealVpnPacket("dataplane.tun.read", packet)

	if !meta.Valid || meta.Truncated {
		t.Fatalf("valid packet classified incorrectly: %#v", meta)
	}
	if meta.IPVersion != 4 || meta.Protocol != "udp" {
		t.Fatalf("packet protocol metadata = %#v", meta)
	}
	if meta.SourceIP != "192.168.255.2" || meta.DestinationIP != "192.168.255.1" {
		t.Fatalf("packet address metadata = %#v", meta)
	}
	if meta.SourcePort != 49152 || meta.DestinationPort != 34890 {
		t.Fatalf("packet port metadata = %#v", meta)
	}
	if meta.DataLength != len(diagnosticMarker) || meta.IPChecksum != "valid" || meta.TransportChecksum != "valid" {
		t.Fatalf("packet checksum metadata = %#v", meta)
	}
	encoded := marshal(meta)
	if strings.Contains(encoded, diagnosticMarker) {
		t.Fatalf("packet metadata leaked payload marker: %s", encoded)
	}
}

func TestInspectRealVpnPacketDetectsTruncation(t *testing.T) {
	packet := buildMarkedPacket()
	packet = packet[:len(packet)-1]
	meta := inspectRealVpnPacket("dataplane.l3.read", packet)

	if meta.Valid || !meta.Truncated {
		t.Fatalf("truncated packet classified incorrectly: %#v", meta)
	}
}

func TestRealVpnDiagnosticsCountersAreMonotonic(t *testing.T) {
	var diagnostics realVpnDiagnostics
	diagnostics.tunReadPackets.Add(1)
	diagnostics.tunReadBytes.Add(48)
	first := diagnostics.snapshot()
	diagnostics.tunReadPackets.Add(2)
	diagnostics.tunReadBytes.Add(96)
	diagnostics.l3WriteAttempts.Add(3)
	diagnostics.l3WriteSuccesses.Add(2)
	second := diagnostics.snapshot()

	if second.TunReadPackets < first.TunReadPackets || second.TunReadBytes < first.TunReadBytes {
		t.Fatalf("diagnostic counters regressed: first=%#v second=%#v", first, second)
	}
	if second.L3WriteAttempts != 3 || second.L3WriteSuccesses != 2 {
		t.Fatalf("unexpected L3 counters: %#v", second)
	}
}

func TestRealVpnPacketSamplingIsBoundedAndUniquePerStageAndFlow(t *testing.T) {
	var diagnostics realVpnDiagnostics
	packet := buildMarkedPacket()
	first := diagnostics.samplePacket("dataplane.tun.read", packet)
	duplicate := diagnostics.samplePacket("dataplane.tun.read", packet)
	nextStage := diagnostics.samplePacket("dataplane.l3.write", packet)

	if first == nil || first.Sequence != 1 {
		t.Fatalf("first sample = %#v, want sequence 1", first)
	}
	if duplicate != nil {
		t.Fatalf("duplicate flow was sampled again: %#v", duplicate)
	}
	if nextStage == nil || nextStage.Sequence != 2 {
		t.Fatalf("next-stage sample = %#v, want sequence 2", nextStage)
	}
}

func TestRealVpnPacketSamplingDistinguishesPacketsWithinTCPFlow(t *testing.T) {
	var diagnostics realVpnDiagnostics
	packet := buildTCPPacketForDiagnostics(0x02, nil)
	first := diagnostics.samplePacket("dataplane.tun.read", packet)

	packet = buildTCPPacketForDiagnostics(0x18, []byte("client hello metadata only"))
	second := diagnostics.samplePacket("dataplane.tun.read", packet)

	if first == nil || second == nil {
		t.Fatalf("TCP flow packets were collapsed: first=%#v second=%#v", first, second)
	}
	if first.TCPFlags != 0x02 || first.DataLength != 0 {
		t.Fatalf("SYN metadata = %#v", first)
	}
	if second.TCPFlags != 0x18 || second.DataLength == 0 {
		t.Fatalf("data metadata = %#v", second)
	}
	if second.TCPSequence != 1234 || second.TCPAcknowledgment != 5678 || second.TCPWindow != 65535 {
		t.Fatalf("TCP sequence metadata = %#v", second)
	}
}

func buildTCPPacketForDiagnostics(flags byte, payload []byte) []byte {
	packet := make([]byte, 20+20+len(payload))
	packet[0] = 0x45
	packet[8] = 64
	packet[9] = 6
	copy(packet[12:16], []byte{10, 190, 130, 250})
	copy(packet[16:20], []byte{10, 10, 98, 98})
	binary.BigEndian.PutUint16(packet[20:22], 49152)
	binary.BigEndian.PutUint16(packet[22:24], 443)
	packet[32] = 0x50
	packet[33] = flags
	binary.BigEndian.PutUint32(packet[24:28], 1234)
	binary.BigEndian.PutUint32(packet[28:32], 5678)
	binary.BigEndian.PutUint16(packet[34:36], 65535)
	copy(packet[40:], payload)
	binary.BigEndian.PutUint16(packet[2:4], uint16(len(packet)))
	binary.BigEndian.PutUint16(packet[10:12], internetChecksum(packet[:20]))
	pseudo := append([]byte{}, packet[12:20]...)
	pseudo = append(pseudo, 0, 6)
	pseudo = append(pseudo, byte(len(packet[20:])>>8), byte(len(packet[20:])))
	pseudo = append(pseudo, packet[20:]...)
	binary.BigEndian.PutUint16(packet[36:38], internetChecksum(pseudo))
	return packet
}

func TestRealVpnDiagnosticEventContainsNoAuthenticationFields(t *testing.T) {
	snapshot := realVpnDiagnosticsSnapshot{TunReadPackets: 1, L3WriteSuccesses: 1}
	packet := inspectRealVpnPacket("dataplane.l3.write", buildMarkedPacket())
	encoded := marshal(realVpnPreparedEvent{
		SchemaVersion: schemaVersion,
		Type:          "vpnDiagnostic",
		State:         "diagnostic",
		Stage:         "dataplane.l3.write",
		Message:       "Real VPN data-plane observation",
		Diagnostics:   &snapshot,
		Packet:        &packet,
	})

	for _, forbidden := range []string{"password", "cookie", "sid", "deviceId", "signKey", diagnosticMarker} {
		if strings.Contains(encoded, forbidden) {
			t.Fatalf("diagnostic event contained forbidden value %q: %s", forbidden, encoded)
		}
	}
}
