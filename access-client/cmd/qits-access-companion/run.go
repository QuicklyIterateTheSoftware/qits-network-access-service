package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/netip"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"

	"qits.wohlben.eu/access-client/internal/keys"
	"qits.wohlben.eu/access-client/internal/tunnel"
	"qits.wohlben.eu/access-client/internal/wgipc"
)

// Tunnel is a running userspace WireGuard device on gVisor's netstack: the device, and the network
// stack behind it that dials and resolves through the tunnel.
type Tunnel struct {
	Device *device.Device
	Net    *netstack.Net
}

// Up brings the device up at t.TunnelIP with the edge as its only peer, allowed only the edge's DNS
// address, at the answer's MTU and with the keepalive every edge peer uses (wgipc.Config). The
// netstack's resolver asks t.Edge.DNSAddress, through the tunnel.
func Up(private keys.Private, t tunnel.Tunnel, endpoint string, logger *device.Logger) (*Tunnel, error) {
	address, err := netip.ParseAddr(t.TunnelIP)
	if err != nil {
		return nil, err
	}
	dns, err := netip.ParseAddr(t.Edge.DNSAddress)
	if err != nil {
		return nil, err
	}
	config, err := wgipc.Config(private, t, endpoint)
	if err != nil {
		return nil, err
	}
	dev, tnet, err := netstack.CreateNetTUN([]netip.Addr{address}, []netip.Addr{dns}, t.Edge.MTU)
	if err != nil {
		return nil, fmt.Errorf("create the netstack device: %w", err)
	}
	wg := device.NewDevice(dev, conn.NewDefaultBind(), logger)
	if err := wg.IpcSet(config); err != nil {
		wg.Close()
		return nil, fmt.Errorf("configure the WireGuard device: %w", err)
	}
	if err := wg.Up(); err != nil {
		wg.Close()
		return nil, fmt.Errorf("bring the WireGuard device up: %w", err)
	}
	return &Tunnel{Device: wg, Net: tnet}, nil
}

// Lookup resolves through the tunnel, keeping the IPv4 answers.
func (t *Tunnel) Lookup(ctx context.Context, host string) ([]netip.Addr, error) {
	names, err := t.Net.LookupContextHost(ctx, host)
	if err != nil {
		return nil, err
	}
	var addrs []netip.Addr
	for _, name := range names {
		if addr, err := netip.ParseAddr(name); err == nil {
			addrs = append(addrs, addr)
		}
	}
	return addrs, nil
}

// Handshake reads the device's last handshake out of IpcGet.
func (t *Tunnel) Handshake() (time.Time, error) {
	text, err := t.Device.IpcGet()
	if err != nil {
		return time.Time{}, err
	}
	return wgipc.LastHandshake(text)
}

// NewProxy is the companion's proxy over a running tunnel, dialling everything outside it from
// the container's own network. advertise is the proxy's host-side address, already validated by
// validateAdvertise: what /proxy.pac tells a browser to connect to, as opposed to listen, which is
// where the proxy actually binds inside the container.
func NewProxy(t tunnel.Tunnel, endpoint string, tun *Tunnel, advertise string, logger *log.Logger) (*Proxy, error) {
	cidr, err := netip.ParsePrefix(t.Edge.TunnelCIDR)
	if err != nil {
		return nil, err
	}
	direct := &net.Dialer{}
	return &Proxy{
		PeerID:       t.PeerID,
		TunnelIP:     t.TunnelIP,
		Endpoint:     endpoint,
		Zone:         t.Edge.Domain,
		TunnelCIDR:   cidr.Masked(),
		Advertise:    advertise,
		TunnelLookup: tun.Lookup,
		TunnelDial:   tun.Net.DialContext,
		DirectDial:   direct.DialContext,
		Handshake:    tun.Handshake,
		Now:          time.Now,
		Log:          logger,
	}, nil
}

// validateAdvertise checks that advertise parses as host:port, with a non-empty host and a port in
// range: what a browser's PAC PROXY entry needs, and the one check run.go can do before it ever
// serves a request.
func validateAdvertise(advertise string) error {
	host, port, err := net.SplitHostPort(advertise)
	if err != nil {
		return fmt.Errorf("--advertise %q is not host:port: %w", advertise, err)
	}
	if strings.TrimSpace(host) == "" {
		return fmt.Errorf("--advertise %q has no host", advertise)
	}
	if n, err := strconv.Atoi(port); err != nil || n < 1 || n > 65535 {
		return fmt.Errorf("--advertise %q has no usable port", advertise)
	}
	return nil
}

// run is the container's long-running mode: the tunnel, then the proxy, until SIGTERM or SIGINT.
func run(state, listen, advertise string, stderr io.Writer) error {
	if err := validateAdvertise(advertise); err != nil {
		return err
	}
	t, err := tunnel.Load(filepath.Join(state, "tunnel.json"))
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return fmt.Errorf("%w (run `configure` first)", err)
		}
		return err
	}
	private, err := keys.Load(filepath.Join(state, "private.key"))
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return fmt.Errorf("%w (run `keygen` first)", err)
		}
		return err
	}
	endpoint, err := wgipc.Endpoint(t.Edge, func(host string) ([]netip.Addr, error) {
		return net.DefaultResolver.LookupNetIP(context.Background(), "ip", host)
	})
	if err != nil {
		return err
	}
	logger := log.New(stderr, "", log.LstdFlags|log.LUTC)
	tun, err := Up(private, t, endpoint, device.NewLogger(device.LogLevelError, "(wg) "))
	if err != nil {
		return err
	}
	defer tun.Device.Close()

	proxy, err := NewProxy(t, endpoint, tun, advertise, logger)
	if err != nil {
		return err
	}
	listener, err := net.Listen("tcp", listen)
	if err != nil {
		return err
	}
	server := &http.Server{Handler: proxy, ReadHeaderTimeout: 10 * time.Second}
	logger.Printf("peer %s at %s via %s; proxy on %s (advertised as %s), %s resolved through %s",
		t.PeerID, t.TunnelIP, endpoint, listener.Addr(), advertise, t.Edge.Domain, t.Edge.DNSAddress)

	served := make(chan error, 1)
	go func() { served <- server.Serve(listener) }()
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGTERM, syscall.SIGINT)
	select {
	case s := <-signals:
		logger.Printf("%s, closing the tunnel", s)
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		server.Shutdown(ctx)
		return nil
	case err := <-served:
		return err
	case <-tun.Device.Wait():
		return errors.New("the WireGuard device closed")
	}
}
