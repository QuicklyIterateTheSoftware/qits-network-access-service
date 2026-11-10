package main

import (
	"bufio"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strconv"
	"strings"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"

	"qits.wohlben.eu/access-client/internal/keys"
	"qits.wohlben.eu/access-client/internal/tunnel"
)

// TestTheCompanionReachesAnInZoneNameThroughARealTunnel runs two userspace WireGuard devices in
// this process, over loopback UDP, as wireguard-go's own netstack examples do: a stand-in private
// edge at 100.96.0.1 that answers DNS for the zone and echoes on :443, and the companion's own
// device built by Up. A CONNECT to an in-zone name must be resolved by the edge's resolver through
// the tunnel and dialled through it, and /healthz must see the handshake that made it possible.
func TestTheCompanionReachesAnInZoneNameThroughARealTunnel(t *testing.T) {
	edgeKey, _ := keys.Generate()
	edgePublic, _ := edgeKey.Public()
	companionKey, _ := keys.Generate()
	companionPublic, _ := companionKey.Public()
	quiet := device.NewLogger(device.LogLevelSilent, "")

	// --- the stand-in edge -------------------------------------------------------------------
	edgeAddr := netip.MustParseAddr("100.96.0.1")
	edgeTun, edgeNet, err := netstack.CreateNetTUN([]netip.Addr{edgeAddr}, nil, 1420)
	if err != nil {
		t.Fatal(err)
	}
	edge := device.NewDevice(edgeTun, conn.NewDefaultBind(), quiet)
	t.Cleanup(edge.Close)
	if err := edge.IpcSet(fmt.Sprintf("private_key=%s\nlisten_port=0\npublic_key=%s\nallowed_ip=100.96.0.10/32\n",
		edgeKey.Hex(), companionPublic.Hex())); err != nil {
		t.Fatal(err)
	}
	if err := edge.Up(); err != nil {
		t.Fatal(err)
	}
	port := listenPort(t, edge)
	serveDNS(t, edgeNet, edgeAddr, map[string]netip.Addr{"edge.qits.wohlben.eu.": edgeAddr})
	serveEcho(t, edgeNet, netip.AddrPortFrom(edgeAddr, 443))

	// --- the companion -----------------------------------------------------------------------
	answer := tunnel.Tunnel{
		PeerID:   "00000000-0000-4000-8000-000000000001",
		TunnelIP: "100.96.0.10",
		Edge: tunnel.Edge{
			PublicKey: edgePublic.Base64(), PublicAddress: "127.0.0.1", ListenPort: port,
			DNSAddress: "100.96.0.1", TunnelCIDR: "100.96.0.0/24", MTU: 1420, Domain: "wohlben.eu",
		},
	}
	if err := answer.Validate(); err != nil {
		t.Fatal(err)
	}
	endpoint := net.JoinHostPort("127.0.0.1", strconv.Itoa(port))
	tun, err := Up(companionKey, answer, endpoint, quiet)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(tun.Device.Close)
	var logged strings.Builder
	proxy, err := NewProxy(answer, endpoint, tun, log.New(&logged, "", 0))
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(proxy)
	t.Cleanup(server.Close)

	c, err := net.Dial("tcp", server.Listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(20 * time.Second))
	fmt.Fprint(c, "CONNECT edge.qits.wohlben.eu:443 HTTP/1.1\r\nHost: edge.qits.wohlben.eu:443\r\n\r\n")
	reader := bufio.NewReader(c)
	resp, err := http.ReadResponse(reader, &http.Request{Method: http.MethodConnect})
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("CONNECT status %d; log %q", resp.StatusCode, logged.String())
	}
	io.WriteString(c, "hello edge")
	got := make([]byte, len("hello edge"))
	if _, err := io.ReadFull(reader, got); err != nil || string(got) != "hello edge" {
		t.Fatalf("echoed %q, %v", got, err)
	}
	if !strings.Contains(logged.String(), "CONNECT host=edge.qits.wohlben.eu:443 route=tunnel status=200") {
		t.Fatalf("logged %q", logged.String())
	}

	health, err := http.Get(server.URL + "/healthz")
	if err != nil {
		t.Fatal(err)
	}
	health.Body.Close()
	if health.StatusCode != http.StatusOK {
		t.Fatalf("healthz %d after a handshake", health.StatusCode)
	}
}

func listenPort(t *testing.T, dev *device.Device) int {
	t.Helper()
	text, err := dev.IpcGet()
	if err != nil {
		t.Fatal(err)
	}
	for _, line := range strings.Split(text, "\n") {
		if v, ok := strings.CutPrefix(line, "listen_port="); ok {
			port, err := strconv.Atoi(v)
			if err == nil && port > 0 {
				return port
			}
		}
	}
	t.Fatalf("no listen_port in %q", text)
	return 0
}

// serveDNS answers A queries for the names it is given, and NXDOMAIN for everything else, on
// addr:53 inside the edge's netstack.
func serveDNS(t *testing.T, tnet *netstack.Net, addr netip.Addr, names map[string]netip.Addr) {
	t.Helper()
	pc, err := tnet.ListenUDPAddrPort(netip.AddrPortFrom(addr, 53))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pc.Close() })
	go func() {
		buf := make([]byte, 1500)
		for {
			n, from, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			var query dnsmessage.Message
			if err := query.Unpack(buf[:n]); err != nil || len(query.Questions) != 1 {
				continue
			}
			q := query.Questions[0]
			reply := dnsmessage.Message{
				Header:    dnsmessage.Header{ID: query.ID, Response: true, Authoritative: true},
				Questions: query.Questions,
			}
			if a, ok := names[strings.ToLower(q.Name.String())]; ok && q.Type == dnsmessage.TypeA {
				reply.Answers = []dnsmessage.Resource{{
					Header: dnsmessage.ResourceHeader{Name: q.Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 60},
					Body:   &dnsmessage.AResource{A: a.As4()},
				}}
			} else if !ok {
				reply.RCode = dnsmessage.RCodeNameError
			}
			packed, err := reply.Pack()
			if err == nil {
				pc.WriteTo(packed, from)
			}
		}
	}()
}

func serveEcho(t *testing.T, tnet *netstack.Net, at netip.AddrPort) {
	t.Helper()
	l, err := tnet.ListenTCPAddrPort(at)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				io.Copy(c, c)
			}()
		}
	}()
}
