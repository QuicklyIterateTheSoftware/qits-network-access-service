package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakes records which dialer was asked for which address and what the tunnel resolver was asked.
type fakes struct {
	mu       sync.Mutex
	dials    []string // "<route> <address>"
	lookups  []string
	answers  map[string][]netip.Addr
	handshak time.Time
	log      bytes.Buffer
}

// echo is an upstream that answers every byte it reads with the same byte, prefixed by its route.
func (f *fakes) dialer(route Route) Dialer {
	return func(_ context.Context, network, address string) (net.Conn, error) {
		f.mu.Lock()
		f.dials = append(f.dials, string(route)+" "+address)
		f.mu.Unlock()
		if network != "tcp" {
			return nil, fmt.Errorf("network %s", network)
		}
		if strings.HasPrefix(address, "203.0.113.99:") {
			return nil, errors.New("connection refused")
		}
		ours, theirs := net.Pipe()
		go func() {
			defer theirs.Close()
			io.WriteString(theirs, string(route)+":")
			io.Copy(theirs, theirs)
		}()
		return ours, nil
	}
}

func newHarness(t *testing.T) (*fakes, *httptest.Server) {
	t.Helper()
	f := &fakes{answers: map[string][]netip.Addr{
		"network-access.qits.wohlben.eu": {netip.MustParseAddr("100.96.0.1")},
		"www.wohlben.eu":                 {netip.MustParseAddr("46.224.171.33")},
		"dual.qits.wohlben.eu":           {netip.MustParseAddr("46.224.171.33"), netip.MustParseAddr("100.96.0.1")},
	}}
	p := &Proxy{
		PeerID:     "00000000-0000-4000-8000-000000000001",
		TunnelIP:   "100.96.0.10",
		Endpoint:   "46.224.171.33:47812",
		Zone:       "wohlben.eu",
		TunnelCIDR: netip.MustParsePrefix("100.96.0.0/24"),
		TunnelLookup: func(_ context.Context, host string) ([]netip.Addr, error) {
			f.mu.Lock()
			defer f.mu.Unlock()
			f.lookups = append(f.lookups, host)
			if a, ok := f.answers[host]; ok {
				return a, nil
			}
			return nil, errors.New("no such host")
		},
		TunnelDial: f.dialer(RouteTunnel),
		DirectDial: f.dialer(RouteDirect),
		Handshake: func() (time.Time, error) {
			f.mu.Lock()
			defer f.mu.Unlock()
			return f.handshak, nil
		},
		Now: func() time.Time { return time.Date(2026, 11, 10, 12, 0, 0, 0, time.UTC) },
	}
	p.Log = log.New(&lockedWriter{mu: &f.mu, w: &f.log}, "", 0)
	server := httptest.NewServer(p)
	t.Cleanup(server.Close)
	return f, server
}

type lockedWriter struct {
	mu *sync.Mutex
	w  io.Writer
}

func (l *lockedWriter) Write(b []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.w.Write(b)
}

// connect sends a CONNECT, and on a 200 a ping through the tunnel; it answers the status and what
// came back.
func connect(t *testing.T, server *httptest.Server, authority string) (int, string) {
	t.Helper()
	c, err := net.Dial("tcp", server.Listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(5 * time.Second))
	fmt.Fprintf(c, "CONNECT %s HTTP/1.1\r\nHost: %s\r\n\r\n", authority, authority)
	reader := bufio.NewReader(c)
	resp, err := http.ReadResponse(reader, &http.Request{Method: http.MethodConnect})
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != http.StatusOK {
		return resp.StatusCode, ""
	}
	io.WriteString(c, "ping")
	got := make([]byte, len("tunnel:ping"))
	n, _ := io.ReadAtLeast(reader, got, len("direct:ping"))
	return resp.StatusCode, string(got[:n])
}

func (f *fakes) snapshot() ([]string, []string, string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string(nil), f.dials...), append([]string(nil), f.lookups...), f.log.String()
}

func TestAnInZoneHostAnsweredInsideTheTunnelIsDialledThroughIt(t *testing.T) {
	f, server := newHarness(t)
	status, echoed := connect(t, server, "network-access.qits.wohlben.eu:443")
	if status != http.StatusOK || echoed != "tunnel:ping" {
		t.Fatalf("status %d, echoed %q", status, echoed)
	}
	dials, lookups, logged := f.snapshot()
	if want := []string{"tunnel 100.96.0.1:443"}; fmt.Sprint(dials) != fmt.Sprint(want) {
		t.Fatalf("dials %v, want %v", dials, want)
	}
	if want := []string{"network-access.qits.wohlben.eu"}; fmt.Sprint(lookups) != fmt.Sprint(want) {
		t.Fatalf("tunnel lookups %v, want %v", lookups, want)
	}
	if want := "CONNECT host=network-access.qits.wohlben.eu:443 route=tunnel status=200\n"; logged != want {
		t.Fatalf("logged %q, want %q", logged, want)
	}
}

func TestAnInZoneHostAnsweredOutsideTheTunnelIsDialledDirectlyByThatAnswer(t *testing.T) {
	f, server := newHarness(t)
	status, echoed := connect(t, server, "www.wohlben.eu:80")
	if status != http.StatusOK || echoed != "direct:ping" {
		t.Fatalf("status %d, echoed %q", status, echoed)
	}
	dials, lookups, logged := f.snapshot()
	if want := []string{"direct 46.224.171.33:80"}; fmt.Sprint(dials) != fmt.Sprint(want) {
		t.Fatalf("dials %v, want %v", dials, want)
	}
	if len(lookups) != 1 {
		t.Fatalf("tunnel lookups %v, want the one in-zone name", lookups)
	}
	if !strings.Contains(logged, "route=direct status=200") {
		t.Fatalf("logged %q", logged)
	}
}

func TestATunnelAnswerWinsOverAPublicOneForTheSameName(t *testing.T) {
	f, server := newHarness(t)
	if status, echoed := connect(t, server, "dual.qits.wohlben.eu:443"); status != http.StatusOK || echoed != "tunnel:ping" {
		t.Fatalf("status %d, echoed %q", status, echoed)
	}
	if dials, _, _ := f.snapshot(); fmt.Sprint(dials) != "[tunnel 100.96.0.1:443]" {
		t.Fatalf("dials %v", dials)
	}
}

func TestAHostOutsideTheZoneIsDialledDirectlyByNameAndNeverAskedOfTheTunnel(t *testing.T) {
	f, server := newHarness(t)
	status, echoed := connect(t, server, "github.com:443")
	if status != http.StatusOK || echoed != "direct:ping" {
		t.Fatalf("status %d, echoed %q", status, echoed)
	}
	dials, lookups, _ := f.snapshot()
	if fmt.Sprint(dials) != "[direct github.com:443]" || len(lookups) != 0 {
		t.Fatalf("dials %v, lookups %v", dials, lookups)
	}
}

func TestALookalikeOfTheZoneIsOutsideIt(t *testing.T) {
	for host, want := range map[string]bool{
		"wohlben.eu": true, "a.b.WOHLBEN.eu.": true, "notwohlben.eu": false, "wohlben.eu.evil.com": false, "": false,
	} {
		if got := InZone(host, "wohlben.eu"); got != want {
			t.Errorf("InZone(%q) = %v, want %v", host, got, want)
		}
	}
}

func TestAnIPLiteralIsRoutedByTheTunnelNetwork(t *testing.T) {
	f, server := newHarness(t)
	if status, echoed := connect(t, server, "100.96.0.1:443"); status != http.StatusOK || echoed != "tunnel:ping" {
		t.Fatalf("status %d, echoed %q", status, echoed)
	}
	if status, echoed := connect(t, server, "46.224.171.33:443"); status != http.StatusOK || echoed != "direct:ping" {
		t.Fatalf("status %d, echoed %q", status, echoed)
	}
	if dials, lookups, _ := f.snapshot(); fmt.Sprint(dials) != "[tunnel 100.96.0.1:443 direct 46.224.171.33:443]" || len(lookups) != 0 {
		t.Fatalf("dials %v, lookups %v", dials, lookups)
	}
}

func TestAnInZoneNameTheTunnelCannotResolveIs502AndNeverFallsBackToTheContainer(t *testing.T) {
	f, server := newHarness(t)
	if status, _ := connect(t, server, "nope.qits.wohlben.eu:443"); status != http.StatusBadGateway {
		t.Fatalf("status %d, want 502", status)
	}
	if dials, _, logged := f.snapshot(); len(dials) != 0 || !strings.Contains(logged, "route=tunnel status=502") {
		t.Fatalf("dials %v, logged %q", dials, logged)
	}
}

func TestAnUpstreamThatRefusesIs502(t *testing.T) {
	_, server := newHarness(t)
	if status, _ := connect(t, server, "203.0.113.99:443"); status != http.StatusBadGateway {
		t.Fatalf("status %d, want 502", status)
	}
}

func TestOnlyPorts443And80AreServed(t *testing.T) {
	f, server := newHarness(t)
	for _, authority := range []string{"network-access.qits.wohlben.eu:22", "github.com:8443", "100.96.0.1:53"} {
		if status, _ := connect(t, server, authority); status != http.StatusMethodNotAllowed {
			t.Errorf("CONNECT %s: status %d, want 405", authority, status)
		}
	}
	if dials, lookups, _ := f.snapshot(); len(dials) != 0 || len(lookups) != 0 {
		t.Fatalf("a refused CONNECT dialled %v / resolved %v", dials, lookups)
	}
}

func TestEverythingButCONNECTAndHealthzIs405(t *testing.T) {
	f, server := newHarness(t)
	for _, req := range []string{
		"GET / HTTP/1.1\r\nHost: x\r\n\r\n",
		"POST /healthz HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n",
		// a forward-proxy request, absolute form, even for the health path
		"GET http://github.com/healthz HTTP/1.1\r\nHost: github.com\r\n\r\n",
		"GET http://network-access.qits.wohlben.eu/ HTTP/1.1\r\nHost: network-access.qits.wohlben.eu\r\n\r\n",
	} {
		c, err := net.Dial("tcp", server.Listener.Addr().String())
		if err != nil {
			t.Fatal(err)
		}
		io.WriteString(c, req)
		resp, err := http.ReadResponse(bufio.NewReader(c), nil)
		c.Close()
		if err != nil {
			t.Fatal(err)
		}
		if resp.StatusCode != http.StatusMethodNotAllowed || resp.Header.Get("Allow") != "CONNECT" {
			t.Errorf("%q: status %d, Allow %q", req, resp.StatusCode, resp.Header.Get("Allow"))
		}
	}
	if dials, _, _ := f.snapshot(); len(dials) != 0 {
		t.Fatalf("a refused request dialled %v", dials)
	}
}

func healthz(t *testing.T, server *httptest.Server) (int, Health, map[string]any) {
	t.Helper()
	resp, err := http.Get(server.URL + "/healthz")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	var h Health
	var fields map[string]any
	if err := json.Unmarshal(raw, &h); err != nil {
		t.Fatalf("%s: %v", raw, err)
	}
	json.Unmarshal(raw, &fields)
	return resp.StatusCode, h, fields
}

func TestHealthzIs503BeforeAnyHandshake(t *testing.T) {
	_, server := newHarness(t)
	status, h, fields := healthz(t, server)
	if status != http.StatusServiceUnavailable {
		t.Fatalf("status %d, want 503", status)
	}
	if h.PeerID != "00000000-0000-4000-8000-000000000001" || h.TunnelIP != "100.96.0.10" ||
		h.Endpoint != "46.224.171.33:47812" {
		t.Fatalf("body %+v", h)
	}
	if v, ok := fields["lastHandshake"]; !ok || v != nil {
		t.Fatalf("lastHandshake %v (present %v), want an explicit null", v, ok)
	}
}

func TestHealthzIs200WithinThreeMinutesOfAHandshakeAnd503After(t *testing.T) {
	f, server := newHarness(t)
	now := time.Date(2026, 11, 10, 12, 0, 0, 0, time.UTC)
	for _, c := range []struct {
		ago  time.Duration
		want int
	}{{10 * time.Second, 200}, {3 * time.Minute, 200}, {3*time.Minute + time.Second, 503}} {
		f.mu.Lock()
		f.handshak = now.Add(-c.ago)
		f.mu.Unlock()
		status, h, _ := healthz(t, server)
		if status != c.want {
			t.Errorf("a handshake %s ago: status %d, want %d", c.ago, status, c.want)
		}
		if h.LastHandshake == nil || !h.LastHandshake.Equal(now.Add(-c.ago)) {
			t.Errorf("a handshake %s ago: lastHandshake %v", c.ago, h.LastHandshake)
		}
	}
}
