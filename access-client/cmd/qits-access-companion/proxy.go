package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"sync"
	"time"
)

// Route names which way a CONNECT left, as the log line spells it.
type Route string

const (
	RouteTunnel Route = "tunnel"
	RouteDirect Route = "direct"
)

// healthyWithin is how recent the last handshake must be for GET /healthz to answer 200. The peer
// keeps its tunnel alive every 25 s and WireGuard re-handshakes every two minutes while traffic
// flows, so three minutes without one means the edge is not answering.
const healthyWithin = 3 * time.Minute

const (
	lookupTimeout = 5 * time.Second
	dialTimeout   = 10 * time.Second
)

// Dialer opens a TCP connection: the netstack's DialContext for the tunnel, net.Dialer's for the
// container's own network, a fake in tests.
type Dialer func(ctx context.Context, network, address string) (net.Conn, error)

// Lookup resolves a name to its addresses. For the tunnel it is the netstack's resolver, which asks
// the edge's DNS address through the tunnel itself.
type Lookup func(ctx context.Context, host string) ([]netip.Addr, error)

// Handshake answers when the tunnel last completed a handshake; zero for never.
type Handshake func() (time.Time, error)

// Proxy is the companion's HTTP surface: CONNECT with split resolution, and GET /healthz. It is no
// open forward proxy: CONNECT only, to ports 80 and 443 only; everything else is 405.
type Proxy struct {
	PeerID     string
	TunnelIP   string
	Endpoint   string
	Zone       string       // the platform's domain; a host in it is resolved through the tunnel
	TunnelCIDR netip.Prefix // an answer inside it is dialled through the tunnel

	TunnelLookup Lookup
	TunnelDial   Dialer
	DirectDial   Dialer // a host outside the zone is dialled by name, so this resolves it too
	Handshake    Handshake
	Now          func() time.Time
	Log          *log.Logger
}

// decision is where one CONNECT goes: the route, and the address to dial on it.
type decision struct {
	route   Route
	address string
}

// InZone says whether host is the zone or a name under it, ignoring case and a trailing dot.
func InZone(host, zone string) bool {
	host = strings.ToLower(strings.TrimSuffix(host, "."))
	zone = strings.ToLower(strings.TrimSuffix(zone, "."))
	if zone == "" || host == "" {
		return false
	}
	return host == zone || strings.HasSuffix(host, "."+zone)
}

// decide is the split resolution:
//   - an IP literal goes through the tunnel when it lies in the tunnel network, else directly;
//   - a host in the zone is resolved through the tunnel; an answer in the tunnel network is dialled
//     through it, any other answer directly from the container, by that address;
//   - any other host is dialled directly, by name, through the container's own resolver.
func (p *Proxy) decide(ctx context.Context, host, port string) (decision, error) {
	if addr, err := netip.ParseAddr(host); err == nil {
		addr = addr.Unmap()
		if p.TunnelCIDR.Contains(addr) {
			return decision{RouteTunnel, net.JoinHostPort(addr.String(), port)}, nil
		}
		return decision{RouteDirect, net.JoinHostPort(addr.String(), port)}, nil
	}
	if !InZone(host, p.Zone) {
		return decision{RouteDirect, net.JoinHostPort(host, port)}, nil
	}
	ctx, cancel := context.WithTimeout(ctx, lookupTimeout)
	defer cancel()
	addrs, err := p.TunnelLookup(ctx, host)
	if err != nil {
		return decision{RouteTunnel, ""}, fmt.Errorf("resolve %s through the tunnel: %w", host, err)
	}
	var public netip.Addr
	for _, addr := range addrs {
		addr = addr.Unmap()
		if p.TunnelCIDR.Contains(addr) {
			return decision{RouteTunnel, net.JoinHostPort(addr.String(), port)}, nil
		}
		if !public.IsValid() {
			public = addr
		}
	}
	if !public.IsValid() {
		return decision{RouteTunnel, ""}, fmt.Errorf("the tunnel resolver has no address for %s", host)
	}
	return decision{RouteDirect, net.JoinHostPort(public.String(), port)}, nil
}

func (p *Proxy) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodConnect {
		p.connect(w, r)
		return
	}
	// Origin form only: an absolute-form GET is a forward-proxy request, which this is not.
	if r.Method == http.MethodGet && !r.URL.IsAbs() && r.URL.Path == "/healthz" {
		p.healthz(w)
		return
	}
	p.refuse(w, r, "only CONNECT is served")
}

func (p *Proxy) refuse(w http.ResponseWriter, r *http.Request, why string) {
	p.Log.Printf("%s host=%s route=- status=%d (%s)", r.Method, r.Host, http.StatusMethodNotAllowed, why)
	w.Header().Set("Allow", http.MethodConnect)
	http.Error(w, why, http.StatusMethodNotAllowed)
}

func (p *Proxy) connect(w http.ResponseWriter, r *http.Request) {
	host, port, err := net.SplitHostPort(r.Host)
	if err != nil || host == "" {
		p.Log.Printf("CONNECT host=%s route=- status=%d (no host:port)", r.Host, http.StatusBadRequest)
		http.Error(w, "CONNECT needs host:port", http.StatusBadRequest)
		return
	}
	if port != "443" && port != "80" {
		p.refuse(w, r, "CONNECT is served to ports 443 and 80 only")
		return
	}
	d, err := p.decide(r.Context(), host, port)
	if err != nil {
		p.fail(w, r.Host, d.route, http.StatusBadGateway, err)
		return
	}
	dial := p.DirectDial
	if d.route == RouteTunnel {
		dial = p.TunnelDial
	}
	ctx, cancel := context.WithTimeout(r.Context(), dialTimeout)
	upstream, err := dial(ctx, "tcp", d.address)
	cancel()
	if err != nil {
		p.fail(w, r.Host, d.route, http.StatusBadGateway, err)
		return
	}
	defer upstream.Close()

	hijacker, ok := w.(http.Hijacker)
	if !ok {
		p.fail(w, r.Host, d.route, http.StatusInternalServerError, errors.New("the connection cannot be hijacked"))
		return
	}
	client, buffered, err := hijacker.Hijack()
	if err != nil {
		p.fail(w, r.Host, d.route, http.StatusInternalServerError, err)
		return
	}
	defer client.Close()
	if _, err := io.WriteString(client, "HTTP/1.1 200 Connection established\r\n\r\n"); err != nil {
		p.Log.Printf("CONNECT host=%s route=%s status=%d (client gone: %v)", r.Host, d.route, http.StatusOK, err)
		return
	}
	p.Log.Printf("CONNECT host=%s route=%s status=%d", r.Host, d.route, http.StatusOK)
	// Whatever the client sent after the request line, already read into the server's buffer,
	// belongs upstream too.
	var head io.Reader = client
	if n := buffered.Reader.Buffered(); n > 0 {
		pending, _ := buffered.Reader.Peek(n)
		head = io.MultiReader(strings.NewReader(string(pending)), client)
	}
	splice(client, head, upstream)
}

func (p *Proxy) fail(w http.ResponseWriter, host string, route Route, status int, err error) {
	if route == "" {
		route = "-"
	}
	p.Log.Printf("CONNECT host=%s route=%s status=%d (%v)", host, route, status, err)
	http.Error(w, err.Error(), status)
}

// splice copies both ways until either side is done, half-closing where the connection can.
func splice(client net.Conn, fromClient io.Reader, upstream net.Conn) {
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		io.Copy(upstream, fromClient)
		closeWrite(upstream)
	}()
	go func() {
		defer wg.Done()
		io.Copy(client, upstream)
		closeWrite(client)
	}()
	wg.Wait()
}

func closeWrite(c net.Conn) {
	if cw, ok := c.(interface{ CloseWrite() error }); ok {
		cw.CloseWrite()
		return
	}
	c.Close()
}

// Health is the GET /healthz body.
type Health struct {
	PeerID        string     `json:"peerId"`
	TunnelIP      string     `json:"tunnelIp"`
	LastHandshake *time.Time `json:"lastHandshake"`
	Endpoint      string     `json:"endpoint"`
}

func (p *Proxy) healthz(w http.ResponseWriter) {
	body := Health{PeerID: p.PeerID, TunnelIP: p.TunnelIP, Endpoint: p.Endpoint}
	status := http.StatusServiceUnavailable
	last, err := p.Handshake()
	if err == nil && !last.IsZero() {
		utc := last.UTC()
		body.LastHandshake = &utc
		if p.Now().Sub(last) <= healthyWithin {
			status = http.StatusOK
		}
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(body)
}
