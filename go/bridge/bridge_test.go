package core

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"
)

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
