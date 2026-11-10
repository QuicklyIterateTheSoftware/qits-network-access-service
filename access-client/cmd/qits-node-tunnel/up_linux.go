//go:build linux

package main

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"syscall"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"

	"qits.wohlben.eu/access-client/internal/keys"
	"qits.wohlben.eu/access-client/internal/tunnel"
	"qits.wohlben.eu/access-client/internal/wgipc"
)

// ifName is the tunnel interface on every node.
const ifName = "qits0"

// up brings the node's tunnel up and holds it until SIGTERM or SIGINT: TUN qits0 at the answer's
// MTU, wireguard-go in this process, the tunnel address and the one route (the edge's DNS address)
// by netlink, and split DNS for the platform's zone when systemd-resolved runs.
func up(dir string, log io.Writer) error {
	t, err := tunnel.Load(filepath.Join(dir, "tunnel.json"))
	if err != nil {
		return err
	}
	private, err := keys.Load(filepath.Join(dir, "private.key"))
	if err != nil {
		return err
	}
	endpoint, err := wgipc.Endpoint(t.Edge, func(host string) ([]netip.Addr, error) {
		return net.DefaultResolver.LookupNetIP(context.Background(), "ip", host)
	})
	if err != nil {
		return err
	}
	config, err := wgipc.Config(private, t, endpoint)
	if err != nil {
		return err
	}

	dev, err := tun.CreateTUN(ifName, t.Edge.MTU)
	if err != nil {
		return fmt.Errorf("create TUN %s: %w", ifName, err)
	}
	logger := device.NewLogger(device.LogLevelError, "("+ifName+") ")
	wg := device.NewDevice(dev, conn.NewDefaultBind(), logger)
	defer wg.Close()
	if err := wg.IpcSet(config); err != nil {
		return fmt.Errorf("configure the WireGuard device: %w", err)
	}
	if err := wg.Up(); err != nil {
		return fmt.Errorf("bring the WireGuard device up: %w", err)
	}

	link, err := net.InterfaceByName(ifName)
	if err != nil {
		return err
	}
	tunnelIP := netip.MustParseAddr(t.TunnelIP)
	dns := netip.MustParseAddr(t.Edge.DNSAddress)
	if err := configureLink(link.Index, tunnelIP, dns); err != nil {
		return err
	}
	fmt.Fprintf(log, "%s up: %s/32 via %s, route %s/32\n", ifName, tunnelIP, endpoint, dns)
	splitDNS(t, log)

	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGTERM, syscall.SIGINT)
	select {
	case s := <-signals:
		fmt.Fprintf(log, "%s: %s, closing the tunnel\n", ifName, s)
	case <-wg.Wait():
		return fmt.Errorf("the WireGuard device closed")
	}
	return nil
}

// splitDNS sends the platform's zone, and only it, to the edge's resolver: `resolvectl dns` and
// `resolvectl domain ~<zone>` on qits0. Without systemd-resolved it says so and leaves DNS alone —
// the tunnel still carries traffic to the edge's address.
func splitDNS(t tunnel.Tunnel, log io.Writer) {
	if info, err := os.Stat("/run/systemd/resolve"); err != nil || !info.IsDir() {
		fmt.Fprintf(log, "warning: systemd-resolved is not running, so split DNS for %s is not set\n", t.Edge.Domain)
		return
	}
	for _, args := range [][]string{
		{"dns", ifName, t.Edge.DNSAddress},
		{"domain", ifName, "~" + t.Edge.Domain},
	} {
		cmd := exec.Command("resolvectl", args...)
		cmd.Stdout, cmd.Stderr = log, log
		if err := cmd.Run(); err != nil {
			fmt.Fprintf(log, "warning: resolvectl %v: %v; split DNS for %s is not set\n", args, err, t.Edge.Domain)
			return
		}
	}
}
