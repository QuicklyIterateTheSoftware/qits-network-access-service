// Package wgipc renders the WireGuard UAPI text (wireguard-go's Device.IpcSet) for a tunnel: this
// device's private key and the private edge as its one peer, allowed only the edge's DNS address,
// with the keepalive that holds a NAT mapping open. The edge is a /32 on purpose: a client reaches
// the platform's private names through it and nothing else.
package wgipc

import (
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"

	"qits.wohlben.eu/access-client/internal/keys"
	"qits.wohlben.eu/access-client/internal/tunnel"
)

// Keepalive is the persistent keepalive interval, in seconds, every peer of the edge uses.
const Keepalive = 25

// Resolver looks a host up; net.DefaultResolver's LookupNetIP in production, a fake in tests.
type Resolver func(host string) ([]netip.Addr, error)

// Endpoint is the edge's publicAddress and listenPort as the UAPI takes them, an IP and a port: a
// host name is resolved, and its first IPv4 address wins.
func Endpoint(edge tunnel.Edge, resolve Resolver) (string, error) {
	port := strconv.Itoa(edge.ListenPort)
	if addr, err := netip.ParseAddr(edge.PublicAddress); err == nil {
		return net.JoinHostPort(addr.String(), port), nil
	}
	addrs, err := resolve(edge.PublicAddress)
	if err != nil {
		return "", fmt.Errorf("resolve the edge %s: %w", edge.PublicAddress, err)
	}
	for _, addr := range addrs {
		if addr.Is4() || addr.Is4In6() {
			return net.JoinHostPort(addr.Unmap().String(), port), nil
		}
	}
	return "", fmt.Errorf("the edge %s has no IPv4 address", edge.PublicAddress)
}

// Config is the IpcSet text: the device key, then the edge as the only peer.
func Config(private keys.Private, t tunnel.Tunnel, endpoint string) (string, error) {
	edge, err := keys.ParsePublic(t.Edge.PublicKey)
	if err != nil {
		return "", err
	}
	var b strings.Builder
	fmt.Fprintf(&b, "private_key=%s\n", private.Hex())
	b.WriteString("replace_peers=true\n")
	fmt.Fprintf(&b, "public_key=%s\n", edge.Hex())
	fmt.Fprintf(&b, "endpoint=%s\n", endpoint)
	fmt.Fprintf(&b, "persistent_keepalive_interval=%d\n", Keepalive)
	b.WriteString("replace_allowed_ips=true\n")
	fmt.Fprintf(&b, "allowed_ip=%s/32\n", t.Edge.DNSAddress)
	return b.String(), nil
}
