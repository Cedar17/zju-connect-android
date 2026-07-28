//go:build !android

package core

// Host tests use pipes and sockets whose shutdown behavior is already
// deterministic; the Android-only nonblocking conversion is unnecessary.
func prepareTunFileDescriptor(_ int) error {
	return nil
}
