//go:build linux

package main

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"sync"
	"syscall"

	"golang.org/x/sys/unix"

	"qits.wohlben.eu/access-client/internal/tunnel"
)

// linkTowards asks the kernel which link a packet to dst leaves by — `ip route get <dst>`, by
// rtnetlink — and that link's MTU: the interface's own, or the route's when the route carries a
// lower one (an `ip route ... mtu` lock, or a path-MTU exception the kernel learned). That is the
// MTU the tunnel's encrypted UDP has to fit, so it is what tunnel.MTU lowers the tunnel to.
func linkTowards(dst netip.Addr) (index int, name string, mtu int, err error) {
	if !dst.Is4() {
		return 0, "", 0, fmt.Errorf("%s is not an IPv4 address", dst)
	}
	fd, err := openRoute(0, 0)
	if err != nil {
		return 0, "", 0, err
	}
	defer unix.Close(fd)
	oif, routeMTU, err := routeLookup(fd, 1, getRouteMessage(1, dst))
	if err != nil {
		return 0, "", 0, fmt.Errorf("route towards %s: %w", dst, err)
	}
	link, err := net.InterfaceByIndex(oif)
	if err != nil {
		return 0, "", 0, fmt.Errorf("the link towards %s (index %d): %w", dst, oif, err)
	}
	mtu = link.MTU
	if routeMTU > 0 && routeMTU < mtu {
		mtu = routeMTU
	}
	return oif, link.Name, mtu, nil
}

// getRouteMessage is RTM_GETROUTE for destination/32: the kernel's route lookup, not a dump, so
// NLM_F_REQUEST alone — no NLM_F_DUMP and no NLM_F_ACK (a lookup answers with the route itself).
func getRouteMessage(seq uint32, destination netip.Addr) []byte {
	body := make([]byte, unix.SizeofRtMsg)
	body[0] = unix.AF_INET
	body[1] = 32 // destination length
	ip := destination.As4()
	body = attribute(body, unix.RTA_DST, ip[:])
	return message(unix.RTM_GETROUTE, unix.NLM_F_REQUEST, seq, body)
}

// routeLookup sends a RTM_GETROUTE and reads its answer. Unlike request's, a lookup that succeeds
// answers with an RTM_NEWROUTE message, not an NLMSG_ERROR of zero; a failed one with NLMSG_ERROR
// and the negative errno (ENETUNREACH when there is no route).
func routeLookup(fd int, seq uint32, message []byte) (oif, mtu int, err error) {
	if err := unix.Sendto(fd, message, 0, &unix.SockaddrNetlink{Family: unix.AF_NETLINK}); err != nil {
		return 0, 0, err
	}
	buf := make([]byte, 8192)
	for {
		n, _, err := unix.Recvfrom(fd, buf, 0)
		if err != nil {
			return 0, 0, err
		}
		oif, mtu, done, err := parseRouteReply(buf[:n], seq)
		if done || err != nil {
			return oif, mtu, err
		}
	}
}

// parseRouteReply reads one datagram of a route lookup's answer: done once the message for seq is
// in it, with RTA_OIF and, when the route carries one, RTAX_MTU out of the nested RTA_METRICS.
func parseRouteReply(b []byte, seq uint32) (oif, mtu int, done bool, err error) {
	messages, err := syscall.ParseNetlinkMessage(b)
	if err != nil {
		return 0, 0, false, err
	}
	for _, m := range messages {
		if m.Header.Seq != seq {
			continue
		}
		switch m.Header.Type {
		case unix.NLMSG_ERROR:
			if len(m.Data) < 4 {
				return 0, 0, true, errors.New("short netlink error")
			}
			if code := int32(binary.NativeEndian.Uint32(m.Data[:4])); code != 0 {
				return 0, 0, true, unix.Errno(-code)
			}
			return 0, 0, true, errors.New("acknowledged without a route")
		case unix.RTM_NEWROUTE:
			attrs, err := syscall.ParseNetlinkRouteAttr(&m)
			if err != nil {
				return 0, 0, true, err
			}
			for _, a := range attrs {
				switch a.Attr.Type {
				case unix.RTA_OIF:
					if len(a.Value) >= 4 {
						oif = int(binary.NativeEndian.Uint32(a.Value[:4]))
					}
				case unix.RTA_METRICS:
					mtu = metricsMTU(a.Value)
				}
			}
			if oif == 0 {
				return 0, 0, true, errors.New("the route names no output link")
			}
			return oif, mtu, true, nil
		}
	}
	return 0, 0, false, nil
}

// metricsMTU is RTAX_MTU out of an RTA_METRICS payload, itself a run of rtattrs; 0 when absent.
func metricsMTU(b []byte) int {
	for len(b) >= unix.SizeofRtAttr {
		length := int(binary.NativeEndian.Uint16(b[0:2]))
		kind := binary.NativeEndian.Uint16(b[2:4]) &^ (unix.NLA_F_NESTED | unix.NLA_F_NET_BYTEORDER)
		if length < unix.SizeofRtAttr || length > len(b) {
			return 0
		}
		if kind == unix.RTAX_MTU && length >= unix.SizeofRtAttr+4 {
			return int(binary.NativeEndian.Uint32(b[unix.SizeofRtAttr : unix.SizeofRtAttr+4]))
		}
		next := (length + unix.RTA_ALIGNTO - 1) &^ (unix.RTA_ALIGNTO - 1)
		if next >= len(b) {
			return 0
		}
		b = b[next:]
	}
	return 0
}

// mtuMessage is RTM_NEWLINK setting IFLA_MTU on the link — `ip link set <link> mtu <mtu>`.
func mtuMessage(seq uint32, index, mtu int) []byte {
	body := make([]byte, unix.SizeofIfInfomsg)
	body[0] = unix.AF_UNSPEC
	binary.NativeEndian.PutUint32(body[4:8], uint32(index))
	value := make([]byte, 4)
	binary.NativeEndian.PutUint32(value, uint32(mtu))
	body = attribute(body, unix.IFLA_MTU, value)
	return message(unix.RTM_NEWLINK, flagsChange, seq, body)
}

// setMTU sets the link's MTU. On qits0 wireguard-go follows by itself: its TUN's netlink listener
// sees the RTM_NEWLINK and the device re-reads the MTU.
func setMTU(index, mtu int) error {
	fd, err := openRoute(0, 0)
	if err != nil {
		return err
	}
	defer unix.Close(fd)
	return request(fd, 1, mtuMessage(1, index, mtu))
}

// mtuWatch keeps the tunnel's MTU at tunnel.MTU(edge, the link towards the endpoint) after start:
// a link that shrinks or grows later (WSL's eth0 coming back at another MTU, a VPN taking the
// default route) moves qits0 with it. It compares against the MTU last applied, so its own change
// to qits0 — which raises a link event like any other — recomputes to the same value and does
// nothing.
type mtuWatch struct {
	edge    int
	current int
	measure func() (link string, mtu int, err error)
	apply   func(mtu int) error
	log     io.Writer
	warned  string // the last warning written, so a repeated failure is written once
}

// recompute measures and, when the chosen MTU differs from the one applied, applies it. A failed
// measurement or apply keeps the current MTU.
func (w *mtuWatch) recompute() {
	link, linkMTU, err := w.measure()
	if err != nil {
		w.warn(fmt.Sprintf("warning: %s mtu: %v; keeping %d", ifName, err, w.current))
		return
	}
	c := tunnel.MTU(w.edge, linkMTU)
	if c.MTU == w.current {
		w.warned = ""
		return
	}
	if err := w.apply(c.MTU); err != nil {
		w.warn(fmt.Sprintf("warning: %s mtu: set %d: %v; keeping %d", ifName, c.MTU, err, w.current))
		return
	}
	w.warned = ""
	w.current = c.MTU
	fmt.Fprintf(w.log, "%s mtu %d (%s)\n", ifName, c.MTU, c.Why(link))
}

func (w *mtuWatch) warn(line string) {
	if line != w.warned {
		fmt.Fprintln(w.log, line)
		w.warned = line
	}
}

// watch recomputes on every link and IPv4 route event (RTMGRP_LINK | RTMGRP_IPV4_ROUTE), without
// reading them: any of them may have moved the link towards the endpoint or its MTU, and a lookup
// is cheap. The returned stop closes the socket and waits for the reader to end.
func (w *mtuWatch) watch() (stop func(), err error) {
	fd, err := openRoute(unix.SOCK_NONBLOCK, unix.RTMGRP_LINK|unix.RTMGRP_IPV4_ROUTE)
	if err != nil {
		return nil, err
	}
	// A non-blocking fd in an os.File rides Go's poller, so Close unblocks the pending Read.
	events := os.NewFile(uintptr(fd), "rtnetlink")
	var done sync.WaitGroup
	done.Add(1)
	go func() {
		defer done.Done()
		buf := make([]byte, 1<<16)
		for {
			if _, err := events.Read(buf); err != nil {
				if errors.Is(err, os.ErrClosed) {
					return
				}
				if !errors.Is(err, unix.ENOBUFS) { // ENOBUFS: events were dropped, so look again
					fmt.Fprintf(w.log, "warning: %s mtu: the link watch stopped: %v\n", ifName, err)
					return
				}
			}
			w.recompute()
		}
	}()
	return func() {
		events.Close()
		done.Wait()
	}, nil
}
