// Command qits-node-tunnel joins a qits-system target (a node) to the platform's private network
// and keeps its tunnel up (epic qits-1977). The node's install line downloads it from
// qits-network-access (GET /nodes/qits-node-tunnel) and runs
//
//	QITS_SYSTEM_RUNNER_REGISTRATION_TOKEN=<token> qits-node-tunnel join --url <base> --target <id> --name <host>
//
// as root; `join` registers the node's WireGuard key, keeps the answer and installs the systemd
// unit whose ExecStart is `qits-node-tunnel up`. See README.md, "The node helper".
package main

import (
	"fmt"
	"io"
	"os"
)

// Exit codes, as the install line and the design page read them.
const (
	exitJoined    = 0
	exitFailed    = 1
	exitUsage     = 2
	exitRefused   = 3
	exitNoSystemd = 4
)

// stateDir is where the node keeps its private key and its tunnel answer.
const stateDir = "/etc/qits/node-tunnel"

func main() {
	os.Exit(run(os.Args[1:], os.Getenv, os.Stdout, os.Stderr))
}

func run(args []string, env func(string) string, stdout, stderr io.Writer) int {
	if len(args) == 0 {
		usage(stderr)
		return exitUsage
	}
	switch args[0] {
	case "join":
		if os.Geteuid() != 0 {
			fmt.Fprintln(stderr, "qits-node-tunnel join must run as root: it writes /etc and installs a systemd unit")
			return exitUsage
		}
		return newJoiner(stdout, stderr).join(args[1:], env)
	case "up":
		if err := up(stateDir, stderr); err != nil {
			fmt.Fprintln(stderr, "qits-node-tunnel up:", err)
			return exitFailed
		}
		return exitJoined
	case "-h", "--help", "help":
		usage(stdout)
		return exitJoined
	default:
		usage(stderr)
		return exitUsage
	}
}

func usage(w io.Writer) {
	fmt.Fprint(w, `usage:
  qits-node-tunnel join --url <network-access base> --target <target id> [--name <host name>]
      (as root; the registration token in QITS_SYSTEM_RUNNER_REGISTRATION_TOKEN)
  qits-node-tunnel up
      (the systemd unit's ExecStart: brings up qits0 and runs until SIGTERM)
`)
}
