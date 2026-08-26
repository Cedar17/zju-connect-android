package core

import "testing"

func TestZjuAtrustNodeTLSConfigDisablesSessionTickets(t *testing.T) {
	if !zjuAtrustNodeTLSConfig().SessionTicketsDisabled {
		t.Fatal("node TLS config must preserve the upstream tunnel session-ticket policy")
	}
}
