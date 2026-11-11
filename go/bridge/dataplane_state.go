package core

// Shared data-plane state helpers used by both the Android TUN data plane
// (real_vpn.go, android builds) and the proxy-only data plane (proxy.go,
// every build). Keep this file free of build tags and platform types.

import (
	"context"
	"errors"
	"fmt"
	"strings"
)

func currentAuthenticatedVpnResult() (authenticatedResult, error) {
	result, ok := currentAuthenticatedResult()
	if !ok || result.SID == "" || len(result.AuthData) == 0 || len(result.ResourceData) == 0 {
		return authenticatedResult{}, fmt.Errorf("authentication has not completed")
	}
	if result.Server == "" || result.Port == 0 {
		return authenticatedResult{}, fmt.Errorf("authentication result is missing its server endpoint")
	}
	return result, nil
}

func classifyRealVpnSetupError(err error) string {
	if err == nil {
		return "unknown"
	}
	errText := strings.ToLower(err.Error())
	switch {
	case errors.Is(err, context.DeadlineExceeded) || strings.Contains(errText, "timeout") || strings.Contains(errText, "deadline exceeded"):
		return "timeout"
	case errors.Is(err, context.Canceled):
		return "cancelled"
	case strings.Contains(errText, "no route to host") ||
		strings.Contains(errText, "network is unreachable") ||
		strings.Contains(errText, "connection refused") ||
		strings.Contains(errText, "connection reset") ||
		strings.Contains(errText, "network unavailable"):
		return "networkUnavailable"
	case strings.Contains(errText, "permission denied") || strings.Contains(errText, "operation not permitted"):
		return "permissionDenied"
	case strings.Contains(errText, "x509") || strings.Contains(errText, "tls") || strings.Contains(errText, "certificate"):
		return "tlsValidation"
	case strings.Contains(errText, "no reachable node"):
		return "noReachableNode"
	case strings.Contains(errText, "failed to connect to the server"):
		return "serverRejected"
	case strings.Contains(errText, "unexpected response"):
		return "protocolRejected"
	default:
		return "unexpected"
	}
}
