//go:build android

package core

import (
	"context"
	"fmt"
	"net"
	"sync"
	"syscall"
)

// androidUnderlayDialer keeps Android's VpnService protection policy in the
// Android bridge while implementing upstream's generic client.UnderlayDialer.
// Before the TUN exists it follows normal system routing. Once VpnService is
// attached, every newly-created transport socket is protected before connect.
type androidUnderlayDialer struct {
	mu        sync.RWMutex
	protector SocketProtector
}

func newAndroidUnderlayDialer() *androidUnderlayDialer {
	return &androidUnderlayDialer{}
}

func (d *androidUnderlayDialer) SetSocketProtector(protector SocketProtector) {
	if d == nil {
		return
	}
	d.mu.Lock()
	d.protector = protector
	d.mu.Unlock()
}

// ExcludeIP satisfies upstream's UnderlayDialer contract. Android does not
// bind the underlay to a Linux interface: VpnService.protect is the authority
// once the VPN interface exists, and no VPN loop is possible before that TUN
// is established.
func (d *androidUnderlayDialer) ExcludeIP(net.IP) {}

func (d *androidUnderlayDialer) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	if d == nil {
		return (&net.Dialer{}).DialContext(ctx, network, address)
	}

	d.mu.RLock()
	protector := d.protector
	d.mu.RUnlock()
	if protector == nil {
		return (&net.Dialer{}).DialContext(ctx, network, address)
	}

	dialer := &net.Dialer{
		ControlContext: func(_ context.Context, _, _ string, rawConn syscall.RawConn) error {
			var protectErr error
			if err := rawConn.Control(func(fd uintptr) {
				if !protector.Protect(int(fd)) {
					protectErr = fmt.Errorf("VpnService.protect returned false")
				}
			}); err != nil {
				return err
			}
			return protectErr
		},
	}
	return dialer.DialContext(ctx, network, address)
}
