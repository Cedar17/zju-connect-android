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

func TestInspectRealVpnPacketReportsOnlySafeMetadata(t *testing.T) {
	packet := buildMarkedPacket()
	meta := inspectRealVpnPacket("dataplane.tun.read", packet)

	if !meta.Valid || meta.Truncated {
		t.Fatalf("valid packet classified incorrectly: %#v", meta)
	}
	if meta.IPVersion != 4 || meta.Protocol != "udp" {
		t.Fatalf("packet protocol metadata = %#v", meta)
	}
	if meta.SourceIP != "10.255.0.2" || meta.DestinationIP != "192.0.2.1" {
		t.Fatalf("packet address metadata = %#v", meta)
	}
	if meta.SourcePort != 49152 || meta.DestinationPort != 34890 {
		t.Fatalf("packet port metadata = %#v", meta)
	}
	encoded := marshal(meta)
	if strings.Contains(encoded, testMarker) {
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

	for _, forbidden := range []string{"password", "cookie", "sid", "deviceId", "signKey", testMarker} {
		if strings.Contains(encoded, forbidden) {
			t.Fatalf("diagnostic event contained forbidden value %q: %s", forbidden, encoded)
		}
	}
}
