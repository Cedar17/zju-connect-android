package core

import (
	"crypto/sha256"
	"crypto/x509"
	"encoding/json"
	"fmt"
	"strings"
	"syscall"
	"testing"
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
	events []string
}

func (l *recordingListener) OnEvent(eventJSON string) {
	l.events = append(l.events, eventJSON)
}

func TestGetBuildInfoReturnsPinnedStructuredEvent(t *testing.T) {
	var result event
	if err := json.Unmarshal([]byte(GetBuildInfo()), &result); err != nil {
		t.Fatalf("GetBuildInfo returned invalid JSON: %v", err)
	}

	if result.SchemaVersion != schemaVersion {
		t.Errorf("schema version = %d, want %d", result.SchemaVersion, schemaVersion)
	}
	if result.Type != "bridgeReady" {
		t.Errorf("event type = %q, want bridgeReady", result.Type)
	}
	if result.UpstreamCommit != upstreamCommit {
		t.Errorf("upstream commit = %q, want %q", result.UpstreamCommit, upstreamCommit)
	}
}

func TestEmitBuildInfoCallsListener(t *testing.T) {
	listener := &recordingListener{}

	EmitBuildInfo(listener)

	if len(listener.events) != 1 {
		t.Fatalf("callback count = %d, want 1", len(listener.events))
	}
	if listener.events[0] != GetBuildInfo() {
		t.Errorf("callback payload = %q, want GetBuildInfo payload", listener.events[0])
	}
}

func TestFetchAuthInfoRejectsInvalidRequestWithoutNetworking(t *testing.T) {
	var result authInfoResponse
	if err := json.Unmarshal([]byte(FetchAuthInfo("{}")), &result); err != nil {
		t.Fatalf("FetchAuthInfo returned invalid JSON: %v", err)
	}

	if result.Type != "error" || result.Code != "invalidRequest" {
		t.Errorf("invalid request result = %#v, want invalidRequest error", result)
	}
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

func TestAuthenticationFailureIsRedacted(t *testing.T) {
	secret := "session-cookie-and-password"
	event := authenticationFailure(fmt.Errorf("x509: certificate rejected: %s", secret))
	encoded := marshal(event)
	if event.Code != "certificateRejected" {
		t.Errorf("code = %q, want certificateRejected", event.Code)
	}
	if strings.Contains(encoded, secret) {
		t.Fatalf("authentication event leaked a secret: %s", encoded)
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
