package core

import (
	"crypto/sha256"
	"crypto/x509"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"syscall"
	"testing"

	"github.com/mythologyli/zju-connect/client/atrust/auth"
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

func TestAuthenticationPromptPreservesServerDrivenChallenge(t *testing.T) {
	listener := &recordingListener{}
	runAuthenticationPrompt(nil, listener, auth.InteractivePrompt{
		State:         "awaitingToken",
		Code:          "sessionExpired",
		Message:       "token needed",
		ChallengeKind: "auth/totp",
	})
	if len(listener.events) != 1 {
		t.Fatalf("events = %d, want 1", len(listener.events))
	}
	var event authenticationEvent
	if err := json.Unmarshal([]byte(listener.events[0]), &event); err != nil {
		t.Fatal(err)
	}
	if event.Type != "tokenRequired" || event.Code != "sessionExpired" || event.ChallengeKind != "auth/totp" {
		t.Fatalf("event = %#v", event)
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
	invalid := sessionRestoreFailure(auth.ErrSessionInvalid)
	if invalid.Type != "sessionInvalid" || invalid.Code != "sessionInvalid" || invalid.State != "idle" {
		t.Fatalf("invalid-session event = %#v", invalid)
	}

	secret := "cookie-value-that-must-not-leak"
	unavailable := sessionRestoreFailure(errors.New("network failure: " + secret))
	encoded := marshal(unavailable)
	if unavailable.Code != "sessionRestoreUnavailable" || strings.Contains(encoded, secret) {
		t.Fatalf("restore failure leaked or misclassified: %s", encoded)
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
	if meta.DataLength != len(testMarker) || meta.IPChecksum != "valid" || meta.TransportChecksum != "valid" {
		t.Fatalf("packet checksum metadata = %#v", meta)
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

	for _, forbidden := range []string{"password", "cookie", "sid", "deviceId", "signKey", testMarker} {
		if strings.Contains(encoded, forbidden) {
			t.Fatalf("diagnostic event contained forbidden value %q: %s", forbidden, encoded)
		}
	}
}
