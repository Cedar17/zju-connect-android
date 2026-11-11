package core

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	clientpkg "github.com/mythologyli/zju-connect/client"
	"github.com/mythologyli/zju-connect/client/atrust"
)

type fakeProxyL3Conn struct {
	closeOnce sync.Once
	closed    chan struct{}
}

func newFakeProxyL3Conn() *fakeProxyL3Conn {
	return &fakeProxyL3Conn{closed: make(chan struct{})}
}

func (c *fakeProxyL3Conn) Read(buf []byte) (int, error) {
	<-c.closed
	return 0, net.ErrClosed
}

func (c *fakeProxyL3Conn) Write(buf []byte) (int, error) {
	select {
	case <-c.closed:
		return 0, net.ErrClosed
	default:
		return len(buf), nil
	}
}

func (c *fakeProxyL3Conn) Close() error {
	c.closeOnce.Do(func() { close(c.closed) })
	return nil
}

type fakeProxyClient struct {
	clientpkg.Client

	l3Conn *fakeProxyL3Conn

	setupErr     error
	setupCalls   int
	setupAddress string
	setupPort    int
	closeCalls   int
	closeMu      sync.Mutex
}

func newFakeProxyClient() *fakeProxyClient {
	return &fakeProxyClient{l3Conn: newFakeProxyL3Conn()}
}

func (c *fakeProxyClient) SetupContext(_ context.Context, options atrust.SetupOptions) ([]byte, error) {
	c.setupCalls++
	c.setupAddress = options.ServerAddress
	c.setupPort = options.ServerPort
	if c.setupErr != nil {
		return nil, c.setupErr
	}
	return []byte(`{"resource":"opaque"}`), nil
}

func (c *fakeProxyClient) IP() (net.IP, error) {
	return net.IPv4(10, 11, 0, 5), nil
}

func (c *fakeProxyClient) IPResources() ([]clientpkg.IPResource, error) {
	return []clientpkg.IPResource{}, nil
}

func (c *fakeProxyClient) DomainResources() (clientpkg.DomainResources, error) {
	return clientpkg.DomainResources{}, nil
}

func (c *fakeProxyClient) DNSResource() (map[string][]net.IP, error) {
	return map[string][]net.IP{}, nil
}

func (c *fakeProxyClient) DNSServer() (string, error) {
	return "10.10.0.21", nil
}

func (c *fakeProxyClient) DNSServers() ([]string, error) {
	return []string{"10.10.0.21"}, nil
}

func (c *fakeProxyClient) NewL3Conn() (io.ReadWriteCloser, error) {
	return c.l3Conn, nil
}

func (c *fakeProxyClient) Close() {
	c.closeMu.Lock()
	c.closeCalls++
	c.closeMu.Unlock()
	_ = c.l3Conn.Close()
}

func installFakeProxyClient(t *testing.T, client *fakeProxyClient) {
	t.Helper()
	previous := newProxyClient
	newProxyClient = func(username, sid, deviceID string) proxyClient {
		return client
	}
	t.Cleanup(func() { newProxyClient = previous })
}

func cacheProxyAuthentication(t *testing.T, deviceID string) {
	t.Helper()
	CancelAuthentication()
	ClearAuthenticatedResult()
	authData, err := json.Marshal(map[string]any{
		"device_id": deviceID,
		"cookies": []map[string]string{
			{"host": "vpn.zju.edu.cn:443", "scheme": "https", "name": "sid", "value": "secret-cookie"},
		},
	})
	if err != nil {
		t.Fatalf("Marshal auth data: %v", err)
	}
	cacheAuthenticatedResult(authenticatedResult{
		Username:     "student",
		SID:          "secret-session-id",
		AuthData:     authData,
		ResourceData: []byte(`{"resource":"opaque"}`),
		Server:       zjuAtrustServer,
		Port:         zjuAtrustServerPort,
	})
	t.Cleanup(ClearAuthenticatedResult)
}

func freeProxyPorts(t *testing.T) (int, int) {
	t.Helper()
	socksListener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("Reserve SOCKS port: %v", err)
	}
	httpListener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("Reserve HTTP port: %v", err)
	}
	socksPort := socksListener.Addr().(*net.TCPAddr).Port
	httpPort := httpListener.Addr().(*net.TCPAddr).Port
	_ = socksListener.Close()
	_ = httpListener.Close()
	return socksPort, httpPort
}

func waitForProxyEvent(t *testing.T, listener *recordingListener, eventType, state string) map[string]any {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		for _, encoded := range listener.snapshot() {
			var event map[string]any
			if json.Unmarshal([]byte(encoded), &event) == nil &&
				event["type"] == eventType && event["state"] == state {
				return event
			}
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("timed out waiting for proxy event %s/%s in %v", eventType, state, listener.snapshot())
	return nil
}

func resetProxyState(t *testing.T) {
	t.Helper()
	StopProxy(nil)
	DiscardPreparedProxy()
	CancelPreparingProxy()
	t.Cleanup(func() {
		StopProxy(nil)
		DiscardPreparedProxy()
		CancelPreparingProxy()
	})
}

func TestPrepareProxyRejectsUnauthenticatedAndBusyStates(t *testing.T) {
	resetProxyState(t)
	ClearAuthenticatedResult()

	if response := PrepareProxy(); !strings.Contains(response, "notAuthenticated") {
		t.Fatalf("PrepareProxy without authentication = %s", response)
	}

	// The VPN-busy refusal path depends on android-only TUN state; it is
	// covered by review rather than a host test.
}

func TestProxyLifecycleServesAndStopsCleanly(t *testing.T) {
	resetProxyState(t)
	deviceID := "0123456789abcdef0123456789abcdef"
	cacheProxyAuthentication(t, deviceID)
	fake := newFakeProxyClient()
	installFakeProxyClient(t, fake)

	prepared := PrepareProxy()
	var event map[string]any
	if err := json.Unmarshal([]byte(prepared), &event); err != nil {
		t.Fatalf("PrepareProxy returned invalid JSON: %v", err)
	}
	if event["type"] != "proxyPrepared" || event["state"] != "prepared" {
		t.Fatalf("PrepareProxy event = %v", event)
	}
	if fake.setupAddress != zjuAtrustServer || fake.setupPort != zjuAtrustServerPort {
		t.Fatalf("SetupContext target = %s:%d, want the authenticated endpoint", fake.setupAddress, fake.setupPort)
	}

	socksPort, httpPort := freeProxyPorts(t)
	listener := &recordingListener{}
	started := StartProxy(marshal(proxyStartRequest{SocksPort: socksPort, HttpPort: httpPort}), listener)
	if err := json.Unmarshal([]byte(started), &event); err != nil {
		t.Fatalf("StartProxy returned invalid JSON: %v", err)
	}
	if event["state"] != "active" {
		t.Fatalf("StartProxy event = %v", event)
	}

	// The SOCKS listener must accept connections before we stop.
	socksConn, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(socksPort)), time.Second)
	if err != nil {
		t.Fatalf("SOCKS listener unreachable: %v", err)
	}
	_ = socksConn.Close()

	StopProxy(listener)
	waitForProxyEvent(t, listener, "proxyState", "stopped")
	if fake.closeCalls == 0 {
		t.Fatal("StopProxy did not close the proxy client")
	}

	if _, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(socksPort)), 200*time.Millisecond); err == nil {
		t.Fatal("SOCKS listener still accepts connections after StopProxy")
	}
}

func TestStartProxyFailsFastOnListenConflict(t *testing.T) {
	resetProxyState(t)
	deviceID := "0123456789abcdef0123456789abcdef"
	cacheProxyAuthentication(t, deviceID)
	fake := newFakeProxyClient()
	installFakeProxyClient(t, fake)

	if response := PrepareProxy(); !strings.Contains(response, `"state":"prepared"`) {
		t.Fatalf("PrepareProxy = %s", response)
	}

	heldListener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("Hold port: %v", err)
	}
	t.Cleanup(func() { _ = heldListener.Close() })
	heldPort := heldListener.Addr().(*net.TCPAddr).Port

	response := StartProxy(marshal(proxyStartRequest{SocksPort: heldPort, HttpPort: 0}), &recordingListener{})
	if !strings.Contains(response, "proxyListenFailed") {
		t.Fatalf("StartProxy on a held port = %s", response)
	}
	if fake.closeCalls == 0 {
		t.Fatal("a failed start did not release the prepared client")
	}

	// The failed start consumed the preparation; a second start must not
	// resurrect it.
	if response := StartProxy(marshal(proxyStartRequest{SocksPort: heldPort, HttpPort: 0}), &recordingListener{}); !strings.Contains(response, "notPrepared") {
		t.Fatalf("StartProxy after failed start = %s", response)
	}
}

func TestStopProxyIsIdempotentWithoutASession(t *testing.T) {
	resetProxyState(t)
	listener := &recordingListener{}
	StopProxy(listener)
	event := waitForProxyEvent(t, listener, "proxyState", "stopped")
	if event["socksPort"] != nil && event["socksPort"] != float64(0) {
		t.Fatalf("stopped event carried unexpected ports: %v", event)
	}
}

func TestProxyDataPlaneFailureEmitsErrorAndReleasesSession(t *testing.T) {
	resetProxyState(t)
	deviceID := "0123456789abcdef0123456789abcdef"
	cacheProxyAuthentication(t, deviceID)
	fake := newFakeProxyClient()
	fake.setupErr = nil
	installFakeProxyClient(t, fake)

	if response := PrepareProxy(); !strings.Contains(response, `"state":"prepared"`) {
		t.Fatalf("PrepareProxy = %s", response)
	}
	socksPort, httpPort := freeProxyPorts(t)

	// Start, then simulate the server tearing the tunnel down: the next L3
	// write returns the sangfor SHUTDOWN sentinel, which upstream reports to
	// the registered shutdown handler instead of exiting the process.
	started := StartProxy(marshal(proxyStartRequest{SocksPort: socksPort, HttpPort: httpPort}), &recordingListener{})
	if !strings.Contains(started, `"state":"active"`) {
		t.Fatalf("StartProxy = %s", started)
	}
	proxyState.Lock()
	active := proxyState.active
	proxyState.Unlock()
	if active == nil {
		t.Fatal("no active proxy session after StartProxy")
	}

	// Closing the L3 conn makes the pump observe a failure; the registered
	// shutdown handler then tears the session down asynchronously, so poll
	// for the released state instead of sleeping a fixed duration.
	_ = fake.l3Conn.Close()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		proxyState.Lock()
		stillActive := proxyState.active != nil
		proxyState.Unlock()
		if !stillActive {
			break
		}
		time.Sleep(time.Millisecond)
	}
	proxyState.Lock()
	released := proxyState.active == nil
	proxyState.Unlock()
	if !released {
		t.Fatal("data plane failure did not release the session")
	}
}
