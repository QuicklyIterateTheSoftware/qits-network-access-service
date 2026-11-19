package tunnel

import (
	"fmt"
	"strconv"
	"strings"
)

// Overhead is what WireGuard adds around every inner packet, counted for the worst case of an IPv6
// outer header: 40 (IPv6) + 8 (UDP) + 32 (WireGuard's header and tag). It is wg-quick's rule — the
// tunnel's MTU is the outgoing link's minus 80 — and an IPv4 outer header only leaves 20 bytes
// unused.
const Overhead = 80

// MinMTU is the smallest tunnel MTU a client sets, the same lower bound Validate holds edge.mtu to:
// below it IPv4 itself stops working, so a link too small for a full tunnel still gets 576 and a
// warning rather than a tunnel nothing fits through.
const MinMTU = 576

// MaxMTU is the upper bound Validate holds edge.mtu to, and ParseLinkMTU a passed link MTU to.
const MaxMTU = 9000

// Choice is the tunnel MTU a client sets and the two inputs it came from.
type Choice struct {
	MTU     int  // what the tunnel device is created with
	Edge    int  // the edge's announcement, edge.mtu
	Link    int  // the MTU of the link the tunnel's UDP leaves by; 0 when unknown
	Floored bool // the link minus the overhead was below MinMTU, and MinMTU was used
}

// MTU chooses the tunnel MTU: the edge's announcement, lowered to linkMTU - Overhead when the link
// the encrypted packets leave by is too small to carry a full-size inner packet plus WireGuard's
// overhead. Without it a peer whose link MTU is below edge MTU + 80 (WSL's eth0 at 1380 is the
// case that bit) has every full-size packet refused by its own kernel — `sendmmsg: message too
// long` — and TLS stalls on the first large record. A linkMTU of zero or less means the link is
// unknown, and the edge's MTU is used as is.
func MTU(edgeMTU, linkMTU int) Choice {
	c := Choice{MTU: edgeMTU, Edge: edgeMTU, Link: max(linkMTU, 0)}
	if c.Link == 0 {
		return c
	}
	c.MTU = min(edgeMTU, linkMTU-Overhead)
	if c.MTU < MinMTU {
		c.MTU, c.Floored = MinMTU, true
	}
	return c
}

// Why names both inputs, for a log line such as `qits0 mtu 1300 (edge 1380, eth0 1380 - 80)`;
// link is what the caller calls the link (an interface name, or "host link"). The MTU printed
// beside it says which input won.
func (c Choice) Why(link string) string {
	if c.Link == 0 {
		return fmt.Sprintf("edge %d, %s unknown so the edge's MTU is used", c.Edge, link)
	}
	why := fmt.Sprintf("edge %d, %s %d - %d", c.Edge, link, c.Link, Overhead)
	if c.Floored {
		why += fmt.Sprintf("; warning: %s is too small for a full tunnel, floored at %d", link, MinMTU)
	}
	return why
}

// String is Why with the link called "link".
func (c Choice) String() string { return c.Why("link") }

// ParseLinkMTU reads a link MTU handed to a client as text (the companion's --link-mtu): empty is
// unknown (0), anything else must be an integer from MinMTU to MaxMTU.
func ParseLinkMTU(s string) (int, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return 0, nil
	}
	n, err := strconv.Atoi(s)
	if err != nil {
		return 0, fmt.Errorf("link MTU %q is not an integer", s)
	}
	if n < MinMTU || n > MaxMTU {
		return 0, fmt.Errorf("link MTU %d is outside %d..%d", n, MinMTU, MaxMTU)
	}
	return n, nil
}
