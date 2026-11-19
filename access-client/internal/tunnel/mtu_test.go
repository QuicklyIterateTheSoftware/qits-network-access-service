package tunnel

import (
	"strings"
	"testing"
)

func TestTheTunnelMTUIsTheEdgesLoweredToTheLinkMinusEighty(t *testing.T) {
	for _, c := range []struct {
		edge, link int
		want       Choice
		why        string
	}{
		{1380, 1500, Choice{MTU: 1380, Edge: 1380, Link: 1500}, "edge 1380, eth0 1500 - 80"},
		{1380, 1380, Choice{MTU: 1300, Edge: 1380, Link: 1380}, "edge 1380, eth0 1380 - 80"},
		{1280, 1380, Choice{MTU: 1280, Edge: 1280, Link: 1380}, "edge 1280, eth0 1380 - 80"},
		{1380, 600, Choice{MTU: 576, Edge: 1380, Link: 600, Floored: true},
			"edge 1380, eth0 600 - 80; warning: eth0 is too small for a full tunnel, floored at 576"},
		{1380, 0, Choice{MTU: 1380, Edge: 1380}, "edge 1380, eth0 unknown so the edge's MTU is used"},
		{1380, -1, Choice{MTU: 1380, Edge: 1380}, "edge 1380, eth0 unknown so the edge's MTU is used"},
	} {
		got := MTU(c.edge, c.link)
		if got != c.want {
			t.Errorf("MTU(%d, %d) = %+v, want %+v", c.edge, c.link, got, c.want)
		}
		if why := got.Why("eth0"); why != c.why {
			t.Errorf("MTU(%d, %d).Why = %q, want %q", c.edge, c.link, why, c.why)
		}
	}
}

func TestParseLinkMTU(t *testing.T) {
	for _, c := range []struct {
		in   string
		want int
		err  string
	}{
		{"", 0, ""},
		{" 1380 ", 1380, ""},
		{"576", 576, ""},
		{"9000", 9000, ""},
		{"abc", 0, "not an integer"},
		{"100", 0, "outside 576..9000"},
		{"99999", 0, "outside 576..9000"},
		{"-1", 0, "outside 576..9000"},
	} {
		got, err := ParseLinkMTU(c.in)
		if got != c.want || (c.err == "") != (err == nil) || (err != nil && !strings.Contains(err.Error(), c.err)) {
			t.Errorf("ParseLinkMTU(%q) = %d, %v; want %d, %q", c.in, got, err, c.want, c.err)
		}
	}
}
