package wgipc

import (
	"errors"
	"net/netip"
	"strings"
	"testing"

	"qits.wohlben.eu/access-client/internal/keys"
	"qits.wohlben.eu/access-client/internal/tunnel"
)

func sample() tunnel.Tunnel {
	return tunnel.Tunnel{
		PeerID:   "00000000-0000-4000-8000-000000000002",
		TunnelIP: "100.96.0.10",
		Edge: tunnel.Edge{
			PublicKey:     "AAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAc=",
			PublicAddress: "46.224.171.33",
			ListenPort:    47812,
			DNSAddress:    "100.96.0.1",
			TunnelCIDR:    "100.96.0.0/24",
			MTU:           1380,
			Domain:        "wohlben.eu",
		},
	}
}

func TestConfigNamesTheEdgeAsTheOnlyPeerAllowedOnlyItsDNSAddress(t *testing.T) {
	var private keys.Private
	private[0] = 0x40
	text, err := Config(private, sample(), "46.224.171.33:47812")
	if err != nil {
		t.Fatal(err)
	}
	want := "private_key=4000000000000000000000000000000000000000000000000000000000000000\n" +
		"replace_peers=true\n" +
		"public_key=0000000100000000000000000000000000000000000000000000000000000007\n" +
		"endpoint=46.224.171.33:47812\n" +
		"persistent_keepalive_interval=25\n" +
		"replace_allowed_ips=true\n" +
		"allowed_ip=100.96.0.1/32\n"
	if text != want {
		t.Fatalf("got\n%s\nwant\n%s", text, want)
	}
}

func TestEndpointResolvesAHostName(t *testing.T) {
	edge := sample().Edge
	edge.PublicAddress = "edge.example"
	got, err := Endpoint(edge, func(host string) ([]netip.Addr, error) {
		return []netip.Addr{netip.MustParseAddr("2001:db8::1"), netip.MustParseAddr("192.0.2.7")}, nil
	})
	if err != nil || got != "192.0.2.7:47812" {
		t.Fatalf("got %q, %v", got, err)
	}
	if _, err := Endpoint(edge, func(string) ([]netip.Addr, error) { return nil, errors.New("nxdomain") }); err == nil || !strings.Contains(err.Error(), "nxdomain") {
		t.Fatalf("resolution failure: %v", err)
	}
}

func TestEndpointTakesAnAddressAsItIs(t *testing.T) {
	got, err := Endpoint(sample().Edge, func(string) ([]netip.Addr, error) { t.Fatal("resolved an address"); return nil, nil })
	if err != nil || got != "46.224.171.33:47812" {
		t.Fatalf("got %q, %v", got, err)
	}
}
