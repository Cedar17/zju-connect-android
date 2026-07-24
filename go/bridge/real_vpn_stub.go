//go:build !android

package core

// The production implementation is Android-only because zju-connect exposes
// a different TUN stack constructor on desktop platforms. Keeping a safe stub
// lets the bridge's host-side protocol and redaction tests run on Windows.

type realVpnRoute struct {
	Address      string `json:"address"`
	PrefixLength int    `json:"prefixLength"`
}

type realVpnPreparedEvent struct {
	SchemaVersion int            `json:"schemaVersion"`
	Type          string         `json:"type"`
	State         string         `json:"state"`
	Code          string         `json:"code,omitempty"`
	Message       string         `json:"message"`
	Address       string         `json:"address,omitempty"`
	MTU           int            `json:"mtu,omitempty"`
	Routes        []realVpnRoute `json:"routes,omitempty"`
}

func PrepareRealVpn() string {
	return realVpnError("unsupportedPlatform", "The real VPN data plane is only available on Android")
}

func StartRealVpn(_ int, _ SocketProtector, listener BridgeListener) {
	if listener != nil {
		listener.OnEvent(realVpnError("unsupportedPlatform", "The real VPN data plane is only available on Android"))
	}
}

func StopRealVpn() {}

func realVpnError(code, message string) string {
	return marshal(realVpnPreparedEvent{
		SchemaVersion: schemaVersion,
		Type:          "error",
		State:         "error",
		Code:          code,
		Message:       message,
	})
}
