//go:build android

package core

import (
	"context"
	"fmt"
	"net"
	"sync"
	"syscall"
)

// androidUnderlayDialer implements upstream's generic underlay while keeping
// VpnService.protect policy in the Android bridge.
type androidUnderlayDialer struct {
	mu        sync.RWMutex
	protector SocketProtector
}

func newAndroidUnderlayDialer() *androidUnderlayDialer {
	return &androidUnderlayDialer{}
}

func (d *androidUnderlayDialer) SetSocketProtector(protector SocketProtector) {
	d.mu.Lock()
	d.protector = protector
	d.mu.Unlock()
}

// ExcludeIP is unused on Android; VpnService.protect controls underlay routing.
func (d *androidUnderlayDialer) ExcludeIP(net.IP) {}

func (d *androidUnderlayDialer) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	d.mu.RLock()
	protector := d.protector
	d.mu.RUnlock()

	dialer := &net.Dialer{}
	if protector != nil {
		dialer.ControlContext = func(_ context.Context, _, _ string, rawConn syscall.RawConn) error {
			var protectErr error
			if err := rawConn.Control(func(fd uintptr) {
				if !protector.Protect(int(fd)) {
					protectErr = fmt.Errorf("VpnService.protect returned false")
				}
			}); err != nil {
				return err
			}
			return protectErr
		}
	}
	return dialer.DialContext(ctx, network, address)
}
