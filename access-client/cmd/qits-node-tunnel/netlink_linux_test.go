//go:build linux

package main

import (
	"encoding/binary"
	"net/netip"
	"testing"

	"golang.org/x/sys/unix"
)

func TestTheRouteMessageIsADestinationThirtyTwoOutOfTheLink(t *testing.T) {
	m := routeMessage(3, 42, netip.MustParseAddr("100.96.0.1"))
	if got := binary.NativeEndian.Uint32(m[0:4]); int(got) != len(m) {
		t.Fatalf("nlmsg_len %d, message %d bytes", got, len(m))
	}
	if binary.NativeEndian.Uint16(m[4:6]) != unix.RTM_NEWROUTE {
		t.Fatal("not RTM_NEWROUTE")
	}
	rt := m[unix.SizeofNlMsghdr:]
	if rt[0] != unix.AF_INET || rt[1] != 32 || rt[4] != unix.RT_TABLE_MAIN || rt[6] != unix.RT_SCOPE_LINK {
		t.Fatalf("rtmsg % x", rt[:unix.SizeofRtMsg])
	}
	attrs := rt[unix.SizeofRtMsg:]
	// RTA_DST 100.96.0.1, then RTA_OIF 42.
	if binary.NativeEndian.Uint16(attrs[2:4]) != unix.RTA_DST || [4]byte(attrs[4:8]) != [4]byte{100, 96, 0, 1} {
		t.Fatalf("first attribute % x", attrs[:8])
	}
	if binary.NativeEndian.Uint16(attrs[10:12]) != unix.RTA_OIF || binary.NativeEndian.Uint32(attrs[12:16]) != 42 {
		t.Fatalf("second attribute % x", attrs[8:16])
	}
}

func TestTheAddressMessageIsAThirtyTwo(t *testing.T) {
	m := addressMessage(2, 7, netip.MustParseAddr("100.96.0.10"))
	if binary.NativeEndian.Uint16(m[4:6]) != unix.RTM_NEWADDR {
		t.Fatal("not RTM_NEWADDR")
	}
	ifa := m[unix.SizeofNlMsghdr:]
	if ifa[0] != unix.AF_INET || ifa[1] != 32 || binary.NativeEndian.Uint32(ifa[4:8]) != 7 {
		t.Fatalf("ifaddrmsg % x", ifa[:unix.SizeofIfAddrmsg])
	}
	if len(m) != unix.SizeofNlMsghdr+unix.SizeofIfAddrmsg+2*(unix.SizeofRtAttr+4) {
		t.Fatalf("%d bytes", len(m))
	}
}
