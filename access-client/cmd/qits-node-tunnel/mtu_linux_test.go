//go:build linux

package main

import (
	"encoding/binary"
	"errors"
	"net"
	"net/netip"
	"slices"
	"strings"
	"testing"
	"time"

	"golang.org/x/sys/unix"
)

func TestTheRouteLookupIsAPlainRequestForADestinationThirtyTwo(t *testing.T) {
	m := getRouteMessage(1, netip.MustParseAddr("46.224.171.33"))
	if got := binary.NativeEndian.Uint32(m[0:4]); int(got) != len(m) {
		t.Fatalf("nlmsg_len %d, message %d bytes", got, len(m))
	}
	if binary.NativeEndian.Uint16(m[4:6]) != unix.RTM_GETROUTE {
		t.Fatal("not RTM_GETROUTE")
	}
	if flags := binary.NativeEndian.Uint16(m[6:8]); flags != unix.NLM_F_REQUEST {
		t.Fatalf("flags %#x, want NLM_F_REQUEST alone", flags)
	}
	rt := m[unix.SizeofNlMsghdr:]
	if rt[0] != unix.AF_INET || rt[1] != 32 {
		t.Fatalf("rtmsg % x", rt[:unix.SizeofRtMsg])
	}
	attrs := rt[unix.SizeofRtMsg:]
	if len(attrs) != unix.SizeofRtAttr+4 ||
		binary.NativeEndian.Uint16(attrs[2:4]) != unix.RTA_DST || [4]byte(attrs[4:8]) != [4]byte{46, 224, 171, 33} {
		t.Fatalf("attributes % x", attrs)
	}
}

func TestTheMTUMessageSetsIflaMTUOnTheLink(t *testing.T) {
	m := mtuMessage(1, 9, 1300)
	if got := binary.NativeEndian.Uint32(m[0:4]); int(got) != len(m) {
		t.Fatalf("nlmsg_len %d, message %d bytes", got, len(m))
	}
	if binary.NativeEndian.Uint16(m[4:6]) != unix.RTM_NEWLINK {
		t.Fatal("not RTM_NEWLINK")
	}
	if flags := binary.NativeEndian.Uint16(m[6:8]); flags != unix.NLM_F_REQUEST|unix.NLM_F_ACK {
		t.Fatalf("flags %#x", flags)
	}
	ifi := m[unix.SizeofNlMsghdr:]
	if ifi[0] != unix.AF_UNSPEC || binary.NativeEndian.Uint32(ifi[4:8]) != 9 ||
		binary.NativeEndian.Uint32(ifi[8:12]) != 0 || binary.NativeEndian.Uint32(ifi[12:16]) != 0 {
		t.Fatalf("ifinfomsg % x", ifi[:unix.SizeofIfInfomsg])
	}
	attrs := ifi[unix.SizeofIfInfomsg:]
	if len(attrs) != unix.SizeofRtAttr+4 || binary.NativeEndian.Uint16(attrs[2:4]) != unix.IFLA_MTU ||
		binary.NativeEndian.Uint32(attrs[4:8]) != 1300 {
		t.Fatalf("attributes % x", attrs)
	}
}

// routeReply is what the kernel answers a lookup with: RTM_NEWROUTE, an rtmsg and its attributes.
func routeReply(seq uint32, attrs ...[]byte) []byte {
	body := make([]byte, unix.SizeofRtMsg)
	body[0] = unix.AF_INET
	body[1] = 32
	body = attribute(body, unix.RTA_TABLE, u32(unix.RT_TABLE_MAIN))
	body = attribute(body, unix.RTA_DST, []byte{46, 224, 171, 33})
	for _, a := range attrs {
		body = append(body, a...)
	}
	body = attribute(body, unix.RTA_PREFSRC, []byte{192, 168, 1, 2})
	return message(unix.RTM_NEWROUTE, 0, seq, body)
}

// errorReply is NLMSG_ERROR carrying -errno, then the request's header (left zero here).
func errorReply(seq uint32, errno unix.Errno) []byte {
	body := make([]byte, 4+unix.SizeofNlMsghdr)
	binary.NativeEndian.PutUint32(body[0:4], uint32(-int32(errno)))
	return message(unix.NLMSG_ERROR, 0, seq, body)
}

func u32(v uint32) []byte {
	b := make([]byte, 4)
	binary.NativeEndian.PutUint32(b, v)
	return b
}

func TestTheRouteReplyNamesTheOutputLinkAndTheRouteMTU(t *testing.T) {
	oif := attribute(nil, unix.RTA_OIF, u32(3))
	metrics := attribute(nil, unix.RTAX_ADVMSS, u32(1340))
	metrics = attribute(metrics, unix.RTAX_MTU, u32(1280))
	withMetrics := attribute(nil, unix.RTA_METRICS, metrics)

	for _, c := range []struct {
		name      string
		reply     []byte
		oif, mtu  int
		wantError string
	}{
		{"RTA_OIF only", routeReply(1, oif), 3, 0, ""},
		{"RTA_METRICS with RTAX_MTU", routeReply(1, oif, withMetrics), 3, 1280, ""},
		{"RTA_METRICS without RTAX_MTU", routeReply(1, oif, attribute(nil, unix.RTA_METRICS,
			attribute(nil, unix.RTAX_ADVMSS, u32(1340)))), 3, 0, ""},
		{"no RTA_OIF", routeReply(1), 0, 0, "no output link"},
		{"NLMSG_ERROR", errorReply(1, unix.ENETUNREACH), 0, 0,
			unix.ENETUNREACH.Error()},
	} {
		got, mtu, done, err := parseRouteReply(c.reply, 1)
		if !done {
			t.Errorf("%s: not done", c.name)
		}
		if c.wantError != "" {
			if err == nil || !strings.Contains(err.Error(), c.wantError) {
				t.Errorf("%s: error %v, want %q", c.name, err, c.wantError)
			}
			continue
		}
		if err != nil || got != c.oif || mtu != c.mtu {
			t.Errorf("%s: oif %d, mtu %d, %v; want %d, %d", c.name, got, mtu, err, c.oif, c.mtu)
		}
	}

	if _, _, done, err := parseRouteReply(routeReply(7, oif), 1); done || err != nil {
		t.Fatalf("another sequence's answer: done %v, %v", done, err)
	}
	if _, _, _, err := parseRouteReply(errorReply(1, unix.ENETUNREACH), 1); !errors.Is(err, unix.ENETUNREACH) {
		t.Fatalf("NLMSG_ERROR is %v, want ENETUNREACH", err)
	}
}

func TestTheWatchSetsAChangedMTUOnlyAndKeepsItOnErrors(t *testing.T) {
	var log strings.Builder
	var applied []int
	link, linkMTU, measureErr := "eth0", 1500, error(nil)
	var applyErr error
	w := &mtuWatch{
		edge: 1380, current: 1380, log: &log,
		measure: func() (string, int, error) { return link, linkMTU, measureErr },
		apply: func(mtu int) error {
			if applyErr != nil {
				return applyErr
			}
			applied = append(applied, mtu)
			return nil
		},
	}
	step := func(name string, wantCurrent int, wantApplied []int, wantLog string) {
		t.Helper()
		log.Reset()
		w.recompute()
		if w.current != wantCurrent || !slices.Equal(applied, wantApplied) || log.String() != wantLog {
			t.Fatalf("%s: current %d, applied %v, log %q; want %d, %v, %q",
				name, w.current, applied, log.String(), wantCurrent, wantApplied, wantLog)
		}
	}

	step("unchanged", 1380, nil, "")
	linkMTU = 1380
	step("the link shrinks", 1300, []int{1300}, "qits0 mtu 1300 (edge 1380, eth0 1380 - 80)\n")
	step("our own change's event", 1300, []int{1300}, "")
	measureErr = errors.New("network is unreachable")
	step("a measurement fails", 1300, []int{1300}, "warning: qits0 mtu: network is unreachable; keeping 1300\n")
	step("the same failure again", 1300, []int{1300}, "")
	measureErr = nil
	link, linkMTU = "wlan0", 1500
	applyErr = errors.New("operation not permitted")
	step("an apply fails", 1300, []int{1300}, "warning: qits0 mtu: set 1380: operation not permitted; keeping 1300\n")
	step("the same apply failure again", 1300, []int{1300}, "")
	applyErr = nil
	step("the link grows back", 1380, []int{1300, 1380}, "qits0 mtu 1380 (edge 1380, wlan0 1500 - 80)\n")
}

// A route lookup and a link watch need no privilege, so both run for real here, against
// loopback.
func TestTheLinkTowardsLoopbackIsLo(t *testing.T) {
	index, name, mtu, err := linkTowards(netip.MustParseAddr("127.0.0.1"))
	if err != nil {
		t.Skipf("no rtnetlink here: %v", err)
	}
	lo, _ := net.InterfaceByIndex(index)
	if lo == nil || name != lo.Name || mtu != lo.MTU || lo.Flags&net.FlagLoopback == 0 {
		t.Fatalf("index %d, %s, mtu %d; loopback is %+v", index, name, mtu, lo)
	}
}

func TestTheWatchStopsWhenAsked(t *testing.T) {
	w := &mtuWatch{edge: 1380, current: 1380, log: &strings.Builder{},
		measure: func() (string, int, error) { return "eth0", 1500, nil },
		apply:   func(int) error { return errors.New("not in a test") }}
	stop, err := w.watch()
	if err != nil {
		t.Skipf("no rtnetlink here: %v", err)
	}
	stopped := make(chan struct{})
	go func() { stop(); close(stopped) }()
	select {
	case <-stopped:
	case <-time.After(5 * time.Second):
		t.Fatal("the watch did not stop")
	}
}
