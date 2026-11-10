//go:build linux

package main

import (
	"encoding/binary"
	"fmt"
	"net/netip"
	"syscall"

	"golang.org/x/sys/unix"
)

// configureLink sets qits0 up, gives it the tunnel address as a /32 and routes the edge's DNS
// address (a /32 too) through it, by rtnetlink — what `ip link set up`, `ip addr replace` and `ip
// route replace` do, without needing iproute2 on the node. Each request replaces, so a restart of
// the unit finds nothing in its way.
func configureLink(index int, address, route netip.Addr) error {
	fd, err := unix.Socket(unix.AF_NETLINK, unix.SOCK_RAW|unix.SOCK_CLOEXEC, unix.NETLINK_ROUTE)
	if err != nil {
		return fmt.Errorf("netlink socket: %w", err)
	}
	defer unix.Close(fd)
	if err := unix.Bind(fd, &unix.SockaddrNetlink{Family: unix.AF_NETLINK}); err != nil {
		return fmt.Errorf("netlink bind: %w", err)
	}
	for i, step := range []struct {
		what    string
		message []byte
	}{
		{"set the link up", linkUpMessage(1, index)},
		{"assign " + address.String() + "/32", addressMessage(2, index, address)},
		{"route " + route.String() + "/32", routeMessage(3, index, route)},
	} {
		if err := request(fd, uint32(i+1), step.message); err != nil {
			return fmt.Errorf("%s on %s: %w", step.what, ifName, err)
		}
	}
	return nil
}

// request sends one message and reads its acknowledgement.
func request(fd int, seq uint32, message []byte) error {
	if err := unix.Sendto(fd, message, 0, &unix.SockaddrNetlink{Family: unix.AF_NETLINK}); err != nil {
		return err
	}
	buf := make([]byte, 4096)
	for {
		n, _, err := unix.Recvfrom(fd, buf, 0)
		if err != nil {
			return err
		}
		messages, err := syscall.ParseNetlinkMessage(buf[:n])
		if err != nil {
			return err
		}
		for _, m := range messages {
			if m.Header.Seq != seq || m.Header.Type != unix.NLMSG_ERROR {
				continue
			}
			if len(m.Data) < 4 {
				return fmt.Errorf("short netlink acknowledgement")
			}
			if code := int32(binary.NativeEndian.Uint32(m.Data[:4])); code != 0 {
				return unix.Errno(-code)
			}
			return nil
		}
	}
}

const (
	flagsCreate = unix.NLM_F_REQUEST | unix.NLM_F_ACK | unix.NLM_F_CREATE | unix.NLM_F_REPLACE
	flagsChange = unix.NLM_F_REQUEST | unix.NLM_F_ACK
)

// linkUpMessage is RTM_NEWLINK setting IFF_UP on the link.
func linkUpMessage(seq uint32, index int) []byte {
	body := make([]byte, unix.SizeofIfInfomsg)
	body[0] = unix.AF_UNSPEC
	binary.NativeEndian.PutUint32(body[4:8], uint32(index))
	binary.NativeEndian.PutUint32(body[8:12], unix.IFF_UP)  // flags
	binary.NativeEndian.PutUint32(body[12:16], unix.IFF_UP) // change
	return message(unix.RTM_NEWLINK, flagsChange, seq, body)
}

// addressMessage is RTM_NEWADDR: address/32 on the link, local and peer address alike.
func addressMessage(seq uint32, index int, address netip.Addr) []byte {
	body := make([]byte, unix.SizeofIfAddrmsg)
	body[0] = unix.AF_INET
	body[1] = 32 // prefix length
	body[3] = unix.RT_SCOPE_UNIVERSE
	binary.NativeEndian.PutUint32(body[4:8], uint32(index))
	ip := address.As4()
	body = attribute(body, unix.IFA_LOCAL, ip[:])
	body = attribute(body, unix.IFA_ADDRESS, ip[:])
	return message(unix.RTM_NEWADDR, flagsCreate, seq, body)
}

// routeMessage is RTM_NEWROUTE: destination/32 out of the link, in the main table.
func routeMessage(seq uint32, index int, destination netip.Addr) []byte {
	body := make([]byte, unix.SizeofRtMsg)
	body[0] = unix.AF_INET
	body[1] = 32 // destination length
	body[4] = unix.RT_TABLE_MAIN
	body[5] = unix.RTPROT_BOOT
	body[6] = unix.RT_SCOPE_LINK
	body[7] = unix.RTN_UNICAST
	ip := destination.As4()
	body = attribute(body, unix.RTA_DST, ip[:])
	oif := make([]byte, 4)
	binary.NativeEndian.PutUint32(oif, uint32(index))
	body = attribute(body, unix.RTA_OIF, oif)
	return message(unix.RTM_NEWROUTE, flagsCreate, seq, body)
}

// attribute appends one rtattr, padded to four bytes.
func attribute(b []byte, kind uint16, data []byte) []byte {
	length := unix.SizeofRtAttr + len(data)
	header := make([]byte, unix.SizeofRtAttr)
	binary.NativeEndian.PutUint16(header[0:2], uint16(length))
	binary.NativeEndian.PutUint16(header[2:4], kind)
	b = append(b, header...)
	b = append(b, data...)
	for len(b)%4 != 0 {
		b = append(b, 0)
	}
	return b
}

// message wraps body in an nlmsghdr.
func message(kind, flags uint16, seq uint32, body []byte) []byte {
	out := make([]byte, unix.SizeofNlMsghdr, unix.SizeofNlMsghdr+len(body))
	binary.NativeEndian.PutUint32(out[0:4], uint32(unix.SizeofNlMsghdr+len(body)))
	binary.NativeEndian.PutUint16(out[4:6], kind)
	binary.NativeEndian.PutUint16(out[6:8], flags)
	binary.NativeEndian.PutUint32(out[8:12], seq)
	return append(out, body...)
}
