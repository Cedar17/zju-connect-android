package core

// SocketProtector is implemented by Android's VpnService. Go invokes it from
// net.Dialer.Control after the operating system creates the socket and before
// the socket connects to the aTrust transport.
type SocketProtector interface {
	Protect(socketFD int) bool
}
