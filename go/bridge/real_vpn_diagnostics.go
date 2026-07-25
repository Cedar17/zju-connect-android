package core

import (
	"errors"
	"os"
	"syscall"
)

// classifyTunWriteError exposes only stable, non-sensitive diagnostics across
// the Go/Android callback boundary. The underlying error can contain device
// details and must remain local to the process.
func classifyTunWriteError(err error) string {
	switch {
	case errors.Is(err, os.ErrClosed), errors.Is(err, syscall.EBADF):
		return "fdClosed"
	case errors.Is(err, syscall.EAGAIN), errors.Is(err, syscall.EWOULDBLOCK):
		return "wouldBlock"
	case errors.Is(err, syscall.EMSGSIZE):
		return "packetTooLarge"
	case errors.Is(err, syscall.EINVAL):
		return "invalidPacket"
	case errors.Is(err, syscall.EIO):
		return "tunUnavailable"
	default:
		return "io"
	}
}
