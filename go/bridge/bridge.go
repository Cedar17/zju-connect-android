// Package core is the Android-facing façade for zju-connect.
//
// Keep this surface constrained to gomobile-compatible values: String and
// callback interfaces. Do not expose upstream clients, credentials, sessions,
// file paths, or file descriptors here without a reviewed lifecycle design.
package core

import (
	"encoding/json"
	"fmt"
	"strings"

	"github.com/mythologyli/zju-connect/client/atrust"
)

const (
	schemaVersion  = 1
	upstreamCommit = "7776cdcfa33e3df56ba8da438c17b2274e316128"
	upstreamModule = "github.com/mythologyli/zju-connect"
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
// protection need dedicated lifecycle APIs before they may be exposed here.
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
