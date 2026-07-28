//go:build android

package core

import "syscall"

// Android VpnService establishes a blocking TUN descriptor. A blocking read
// in one thread is not reliably interrupted when another thread closes the
// same descriptor. Switching to nonblocking before os.NewFile lets Go's
// runtime poller own the wait and wake it promptly during Close.
func prepareTunFileDescriptor(fd int) error {
	return syscall.SetNonblock(fd, true)
}
