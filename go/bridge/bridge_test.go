package core

import (
	"encoding/json"
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
