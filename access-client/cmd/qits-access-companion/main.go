// Command qits-access-companion is a person's way onto the platform's private edge without a TUN,
// NET_ADMIN or host DNS changes (epic qits-1977, the design's section 6). It rides inside the
// qits-network-access image at /work/companion/qits-access-companion, and `qits network-access up`
// starts it from there with what GET /companion answers:
//
//	docker run -d --name qits-network-access-companion --restart unless-stopped \
//	  --user 65532:65532 --entrypoint /work/companion/qits-access-companion \
//	  -p 127.0.0.1:47813:47813 -v qits-network-access-companion:/state <image>:<version> run
//
// — and holds a userspace WireGuard tunnel (wireguard-go on gVisor's netstack) to the private
// edge, in front of which it serves an HTTP CONNECT proxy with split resolution. The qits CLI dials
// through it. Three modes:
//
//	keygen     create /state/private.key if absent; print the PUBLIC key (never the private one)
//	configure  read the GET /peers/{id}/tunnel answer on stdin, validate it, write /state/tunnel.json
//	run        bring the tunnel up and serve CONNECT, GET /healthz and GET/HEAD /proxy.pac on
//	           0.0.0.0:47813; --advertise (or QITS_COMPANION_ADVERTISE) names the host-side address
//	           of that port, the one a browser's PAC file is told to use, since 0.0.0.0:47813 means
//	           nothing outside the container; --link-mtu (or QITS_COMPANION_LINK_MTU) is the host
//	           link's MTU as the CLI measured it, which lowers the tunnel's MTU to fit
//	           (tunnel.MTU: the edge's, or the link's minus 80 when that is smaller)
//
// See README.md, "The companion".
package main

import (
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"

	"qits.wohlben.eu/access-client/internal/keys"
	"qits.wohlben.eu/access-client/internal/tunnel"
)

const (
	exitOK     = 0
	exitFailed = 1
	exitUsage  = 2
)

// defaultState is the volume `qits-network-access-companion`, mounted by the CLI's docker run.
const defaultState = "/state"

// defaultListen is every container address: docker publishes the port to 127.0.0.1 only, so the
// host side is loopback even though the container side is not.
const defaultListen = "0.0.0.0:47813"

// defaultAdvertise is the host side of the docker publish `-p 127.0.0.1:47813:47813`: what
// /proxy.pac tells a browser to connect to. --advertise or QITS_COMPANION_ADVERTISE overrides it,
// for a published port that differs or a companion reached some other way.
const defaultAdvertise = "127.0.0.1:47813"

// adviseEnv is the environment fallback for --advertise.
const adviseEnv = "QITS_COMPANION_ADVERTISE"

// linkMTUEnv is the environment fallback for --link-mtu. The companion cannot measure the host's
// link from inside its container — its eth0 is docker's bridge, not the link the encrypted UDP
// finally leaves the host by — so the CLI measures it and passes it in.
const linkMTUEnv = "QITS_COMPANION_LINK_MTU"

func main() {
	os.Exit(dispatch(os.Args[1:], os.Stdin, os.Stdout, os.Stderr))
}

func dispatch(args []string, stdin io.Reader, stdout, stderr io.Writer) int {
	if len(args) == 0 {
		usage(stderr)
		return exitUsage
	}
	flags := flag.NewFlagSet(args[0], flag.ContinueOnError)
	flags.SetOutput(stderr)
	state := flags.String("state", defaultState, "the directory holding private.key and tunnel.json")
	listen := defaultListen
	advertise := defaultAdvertise
	if v := os.Getenv(adviseEnv); v != "" {
		advertise = v
	}
	linkMTU := os.Getenv(linkMTUEnv)
	if args[0] == "run" {
		flags.StringVar(&listen, "listen", defaultListen, "the proxy's listen address")
		flags.StringVar(&advertise, "advertise", advertise,
			"the proxy's host-side address, named in /proxy.pac (env "+adviseEnv+")")
		flags.StringVar(&linkMTU, "link-mtu", linkMTU,
			"the host link's MTU, as the CLI measured it; empty when unknown (env "+linkMTUEnv+")")
	}
	switch args[0] {
	case "keygen", "configure", "run":
		if err := flags.Parse(args[1:]); err != nil || flags.NArg() != 0 {
			usage(stderr)
			return exitUsage
		}
	case "-h", "--help", "help":
		usage(stdout)
		return exitOK
	default:
		usage(stderr)
		return exitUsage
	}

	var err error
	switch args[0] {
	case "keygen":
		err = keygen(*state, stdout, stderr)
	case "configure":
		err = configure(*state, stdin, stderr)
	case "run":
		err = run(*state, listen, advertise, parseLinkMTU(linkMTU, stderr), stderr)
	}
	if err != nil {
		fmt.Fprintf(stderr, "qits-access-companion %s: %v\n", args[0], err)
		return exitFailed
	}
	return exitOK
}

func usage(w io.Writer) {
	fmt.Fprint(w, `usage:
  qits-access-companion keygen [--state /state]
      create the private key if it is absent and print the public key
  qits-access-companion configure [--state /state] < tunnel.json
      keep the GET /peers/{id}/tunnel answer read on stdin
  qits-access-companion run [--state /state] [--listen 0.0.0.0:47813] [--advertise 127.0.0.1:47813]
                           [--link-mtu <host link MTU>]
      bring the tunnel up and serve the CONNECT proxy, GET /healthz and GET/HEAD /proxy.pac
      --advertise (env QITS_COMPANION_ADVERTISE) is the host-side address named in /proxy.pac
      --link-mtu (env QITS_COMPANION_LINK_MTU) is the host link's MTU; the tunnel's MTU is the
      edge's, lowered to it minus 80 when that is smaller
`)
}

// parseLinkMTU reads --link-mtu. An unusable value is a warning and an unknown link, never a
// failure: the edge's MTU is what the companion ran at before the CLI passed one, and a tunnel at
// it beats no tunnel.
func parseLinkMTU(value string, stderr io.Writer) int {
	mtu, err := tunnel.ParseLinkMTU(value)
	if err != nil {
		fmt.Fprintf(stderr, "warning: --link-mtu: %v; the host link is taken as unknown\n", err)
		return 0
	}
	return mtu
}

// keygen creates the key once and prints its public half. A second call prints the same key: the
// device registered under it must stay valid across container recreations.
func keygen(state string, stdout, stderr io.Writer) error {
	private, created, err := keys.LoadOrCreate(filepath.Join(state, "private.key"))
	if err != nil {
		return err
	}
	public, err := private.Public()
	if err != nil {
		return err
	}
	if created {
		fmt.Fprintf(stderr, "created %s\n", filepath.Join(state, "private.key"))
	}
	fmt.Fprintln(stdout, public.Base64())
	return nil
}

// configure keeps the tunnel answer, validated, as tunnel.json. It holds no secret.
func configure(state string, stdin io.Reader, stderr io.Writer) error {
	data, err := io.ReadAll(io.LimitReader(stdin, 1<<20))
	if err != nil {
		return fmt.Errorf("read the tunnel answer: %w", err)
	}
	t, err := tunnel.Parse(data)
	if err != nil {
		return err
	}
	path := filepath.Join(state, "tunnel.json")
	if err := tunnel.Save(path, t); err != nil {
		return err
	}
	fmt.Fprintf(stderr, "wrote %s: peer %s, %s via %s:%d\n",
		path, t.PeerID, t.TunnelIP, t.Edge.PublicAddress, t.Edge.ListenPort)
	return nil
}
