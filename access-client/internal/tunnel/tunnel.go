// Package tunnel is the tunnel answer of qits-network-access — the body of a node's join and of
// GET /peers/{id}/tunnel — and the tunnel.json the access clients keep it in:
//
//	{"peerId": "...", "tunnelIp": "100.96.0.10",
//	 "edge": {"publicKey": "...", "publicAddress": "46.224.171.33", "listenPort": 47812,
//	          "dnsAddress": "100.96.0.1", "tunnelCidr": "100.96.0.0/24", "mtu": 1380,
//	          "domain": "wohlben.eu"}}
//
// It holds no secret: the private key is kept beside it, in a file of its own.
package tunnel

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/netip"
	"os"
	"strings"

	"qits.wohlben.eu/access-client/internal/keys"
)

// Edge is what a client needs to reach the private edge.
type Edge struct {
	PublicKey     string `json:"publicKey"`
	PublicAddress string `json:"publicAddress"`
	ListenPort    int    `json:"listenPort"`
	DNSAddress    string `json:"dnsAddress"`
	TunnelCIDR    string `json:"tunnelCidr"`
	MTU           int    `json:"mtu"`
	Domain        string `json:"domain"`
}

// Tunnel is one peer's tunnel parameters.
type Tunnel struct {
	PeerID   string `json:"peerId"`
	TunnelIP string `json:"tunnelIp"`
	Edge     Edge   `json:"edge"`
}

// Parse reads a tunnel answer and validates it.
func Parse(data []byte) (Tunnel, error) {
	var t Tunnel
	if err := json.Unmarshal(data, &t); err != nil {
		return Tunnel{}, fmt.Errorf("the tunnel answer is not JSON: %w", err)
	}
	if err := t.Validate(); err != nil {
		return Tunnel{}, err
	}
	return t, nil
}

// Validate checks every field the clients use: the addresses parse as IPv4, the tunnel address
// and the DNS address lie in the tunnel network, the key is a WireGuard key and the port and MTU
// are in range.
func (t Tunnel) Validate() error {
	var problems []string
	if strings.TrimSpace(t.PeerID) == "" {
		problems = append(problems, "no peerId")
	}
	prefix, err := netip.ParsePrefix(t.Edge.TunnelCIDR)
	if err != nil || !prefix.Addr().Is4() {
		problems = append(problems, fmt.Sprintf("edge.tunnelCidr %q is not an IPv4 CIDR", t.Edge.TunnelCIDR))
	}
	for name, value := range map[string]string{"tunnelIp": t.TunnelIP, "edge.dnsAddress": t.Edge.DNSAddress} {
		addr, err := netip.ParseAddr(value)
		if err != nil || !addr.Is4() {
			problems = append(problems, fmt.Sprintf("%s %q is not an IPv4 address", name, value))
		} else if prefix.IsValid() && !prefix.Contains(addr) {
			problems = append(problems, fmt.Sprintf("%s %s is outside %s", name, value, prefix))
		}
	}
	if _, err := keys.ParsePublic(t.Edge.PublicKey); err != nil {
		problems = append(problems, "edge."+err.Error())
	}
	if strings.TrimSpace(t.Edge.PublicAddress) == "" {
		problems = append(problems, "no edge.publicAddress")
	}
	if t.Edge.ListenPort < 1 || t.Edge.ListenPort > 65535 {
		problems = append(problems, fmt.Sprintf("edge.listenPort %d is not a port", t.Edge.ListenPort))
	}
	if t.Edge.MTU < 576 || t.Edge.MTU > 9000 {
		problems = append(problems, fmt.Sprintf("edge.mtu %d is out of range", t.Edge.MTU))
	}
	if strings.TrimSpace(t.Edge.Domain) == "" {
		problems = append(problems, "no edge.domain")
	}
	if len(problems) > 0 {
		return errors.New("the tunnel answer is unusable: " + strings.Join(problems, "; "))
	}
	return nil
}

// Load reads and validates the tunnel.json at path.
func Load(path string) (Tunnel, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return Tunnel{}, err
	}
	t, err := Parse(data)
	if err != nil {
		return Tunnel{}, fmt.Errorf("%s: %w", path, err)
	}
	return t, nil
}

// Save writes t to path, 0600.
func Save(path string, t Tunnel) error {
	data, err := json.MarshalIndent(t, "", "  ")
	if err != nil {
		return err
	}
	return keys.WriteFile(path, append(data, '\n'))
}
