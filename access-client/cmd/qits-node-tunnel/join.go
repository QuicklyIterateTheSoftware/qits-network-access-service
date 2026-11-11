package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"qits.wohlben.eu/access-client/internal/keys"
	"qits.wohlben.eu/access-client/internal/tunnel"
)

// tokenEnv names the registration token: the same name whatever ENV_PREFIX the install line uses,
// so the line passes it explicitly. Never an argument: argv is world-readable in /proc.
const tokenEnv = "QITS_SYSTEM_RUNNER_REGISTRATION_TOKEN"

const (
	unitName = "qits-node-tunnel"
	unitPath = "/etc/systemd/system/qits-node-tunnel.service"
	binPath  = "/usr/local/bin/qits-node-tunnel"

	// tunDevicePath is opened to prove the host can bring up a TUN device before anything is
	// registered. osreleasePath is read to tell a genuine no-systemd host from WSL, whose
	// /etc/wsl.conf can turn systemd on without a reboot.
	tunDevicePath = "/dev/net/tun"
	osreleasePath = "/proc/sys/kernel/osrelease"
)

// joiner is the join with every side effect a field, so the tests run it against a temporary
// directory, an httptest server and a recorded systemctl.
type joiner struct {
	dir      string
	unitPath string
	client   *http.Client
	// systemd says whether this host runs systemd.
	systemd func() bool
	// systemctl runs `systemctl <args>`; isActive answers `systemctl is-active --quiet`.
	systemctl func(args ...string) error
	isActive  func(unit string) bool
	hostname  func() (string, error)
	// tunPath is where the TUN device is opened (/dev/net/tun in production); osreleasePath is
	// read to spot WSL (/proc/sys/kernel/osrelease). Tests point both at temp files, the same way
	// systemd is stubbed.
	tunPath       string
	osreleasePath string
	stdout        io.Writer
	stderr        io.Writer
}

func newJoiner(stdout, stderr io.Writer) *joiner {
	return &joiner{
		dir:      stateDir,
		unitPath: unitPath,
		client:   &http.Client{Timeout: 30 * time.Second},
		systemd:  hasSystemd,
		systemctl: func(args ...string) error {
			cmd := exec.Command("systemctl", args...)
			cmd.Stdout, cmd.Stderr = stdout, stderr
			return cmd.Run()
		},
		isActive: func(unit string) bool {
			return exec.Command("systemctl", "is-active", "--quiet", unit).Run() == nil
		},
		hostname:      os.Hostname,
		tunPath:       tunDevicePath,
		osreleasePath: osreleasePath,
		stdout:        stdout,
		stderr:        stderr,
	}
}

// hasSystemd is sd_booted(3): /run/systemd/system exists exactly when systemd is PID 1.
func hasSystemd() bool {
	if info, err := os.Stat("/run/systemd/system"); err != nil || !info.IsDir() {
		return false
	}
	_, err := exec.LookPath("systemctl")
	return err == nil
}

// checkTun proves the TUN device can actually be opened, before anything is registered: a peer
// whose tunnel can never come up is worse than no peer at all.
func (j *joiner) checkTun() error {
	f, err := os.OpenFile(j.tunPath, os.O_RDWR, 0)
	if err != nil {
		return err
	}
	return f.Close()
}

// isWSL reports whether osreleasePath names a WSL kernel, whose systemd can be turned on in
// /etc/wsl.conf without reinstalling anything. A missing or unreadable file is not WSL.
func (j *joiner) isWSL() bool {
	data, err := os.ReadFile(j.osreleasePath)
	if err != nil {
		return false
	}
	return strings.Contains(strings.ToLower(string(data)), "microsoft")
}

type joinRequest struct {
	Name      string `json:"name"`
	PublicKey string `json:"publicKey"`
}

type refusal struct {
	Message string `json:"message"`
}

func (j *joiner) join(args []string, env func(string) string) int {
	flags := flag.NewFlagSet("join", flag.ContinueOnError)
	flags.SetOutput(j.stderr)
	base := flags.String("url", "", "qits-network-access's base, e.g. https://network-access.qits.<domain>/network-access")
	target := flags.String("target", "", "this node's qits-system target id")
	name := flags.String("name", "", "this node's name (default: the short host name)")
	if err := flags.Parse(args); err != nil {
		return exitUsage
	}
	if flags.NArg() > 0 || *base == "" || *target == "" {
		fmt.Fprintln(j.stderr, "join needs --url and --target")
		return exitUsage
	}
	endpoint, err := joinURL(*base, *target)
	if err != nil {
		fmt.Fprintln(j.stderr, err)
		return exitUsage
	}
	token := strings.TrimSpace(env(tokenEnv))
	if token == "" {
		fmt.Fprintf(j.stderr, "join needs the registration token in %s\n", tokenEnv)
		return exitUsage
	}
	if *name == "" {
		*name = j.shortHostname()
	}
	// Before anything is registered: a peer nobody can bring up would only hold an address.
	if !j.systemd() {
		message := "this host does not run systemd, so qits-node-tunnel cannot keep the tunnel up"
		if j.isWSL() {
			message += ` This is WSL: put "[boot]" and "systemd=true" in /etc/wsl.conf, run "wsl --shutdown" from Windows, reopen the distro and run the install line again.`
		}
		fmt.Fprintln(j.stderr, message)
		return exitNoSystemd
	}
	if err := j.checkTun(); err != nil {
		fmt.Fprintf(j.stderr, "qits-node-tunnel: this host has no usable /dev/net/tun (%s), so the tunnel cannot come up; nothing was registered. An LXC container needs the tun device passed through (lxc.cgroup2.devices.allow: c 10:200 rwm and lxc.mount.entry: /dev/net dev/net none bind,create=dir).\n", err)
		return exitNoTun
	}

	private, created, err := keys.LoadOrCreate(filepath.Join(j.dir, "private.key"))
	if err != nil {
		fmt.Fprintln(j.stderr, "the node's key:", err)
		return exitFailed
	}
	public, err := private.Public()
	if err != nil {
		fmt.Fprintln(j.stderr, err)
		return exitFailed
	}
	if created {
		fmt.Fprintf(j.stdout, "generated this node's key in %s\n", filepath.Join(j.dir, "private.key"))
	}

	answer, code := j.post(endpoint, token, joinRequest{Name: *name, PublicKey: public.Base64()})
	if code != exitJoined {
		return code
	}
	if err := tunnel.Save(filepath.Join(j.dir, "tunnel.json"), answer); err != nil {
		fmt.Fprintln(j.stderr, "keep the tunnel answer:", err)
		return exitFailed
	}
	if err := os.WriteFile(j.unitPath, []byte(unitFile()), 0o644); err != nil {
		fmt.Fprintln(j.stderr, "install the systemd unit:", err)
		return exitFailed
	}
	wasActive := j.isActive(unitName)
	if err := j.systemctl("daemon-reload"); err != nil {
		fmt.Fprintln(j.stderr, "systemctl daemon-reload:", err)
		return exitFailed
	}
	if err := j.systemctl("enable", "--now", unitName); err != nil {
		fmt.Fprintln(j.stderr, "systemctl enable --now:", err)
		return exitFailed
	}
	if wasActive {
		// A running tunnel holds the old answer; a re-join may have changed key or address.
		if err := j.systemctl("restart", unitName); err != nil {
			fmt.Fprintln(j.stderr, "systemctl restart:", err)
			return exitFailed
		}
	}
	fmt.Fprintf(j.stdout, "joined the private network as %s (peer %s), via %s:%d\n",
		answer.TunnelIP, answer.PeerID, answer.Edge.PublicAddress, answer.Edge.ListenPort)
	return exitJoined
}

// joinURL is <base>/api/nodes/<target>/join; the base must be an absolute http(s) URL.
func joinURL(base, target string) (string, error) {
	u, err := url.Parse(strings.TrimRight(base, "/"))
	if err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Host == "" {
		return "", fmt.Errorf("--url %q is not an http(s) URL", base)
	}
	if strings.ContainsAny(target, "/?#") || strings.TrimSpace(target) == "" {
		return "", fmt.Errorf("--target %q is not a target id", target)
	}
	return u.String() + "/api/nodes/" + url.PathEscape(target) + "/join", nil
}

func (j *joiner) post(endpoint, token string, body joinRequest) (tunnel.Tunnel, int) {
	payload, _ := json.Marshal(body)
	req, err := http.NewRequest(http.MethodPost, endpoint, bytes.NewReader(payload))
	if err != nil {
		fmt.Fprintln(j.stderr, err)
		return tunnel.Tunnel{}, exitUsage
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	resp, err := j.client.Do(req)
	if err != nil {
		fmt.Fprintln(j.stderr, "qits-network-access cannot be reached:", redact(err, token))
		return tunnel.Tunnel{}, exitRefused
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		fmt.Fprintln(j.stderr, "read the join answer:", err)
		return tunnel.Tunnel{}, exitRefused
	}
	if resp.StatusCode != http.StatusOK {
		var r refusal
		message := strings.TrimSpace(string(data))
		if json.Unmarshal(data, &r) == nil && r.Message != "" {
			message = r.Message
		}
		fmt.Fprintf(j.stderr, "qits-network-access refused the join: %d %s\n", resp.StatusCode, message)
		return tunnel.Tunnel{}, exitRefused
	}
	answer, err := tunnel.Parse(data)
	if err != nil {
		fmt.Fprintln(j.stderr, err)
		return tunnel.Tunnel{}, exitFailed
	}
	return answer, exitJoined
}

// shortHostname is the host name up to its first dot, lower-cased: the DNS label the service
// wants (qits-system's own target name wins over it for a registration token's join).
func (j *joiner) shortHostname() string {
	host, err := j.hostname()
	if err != nil {
		return ""
	}
	host, _, _ = strings.Cut(host, ".")
	return strings.ToLower(host)
}

// redact keeps the token out of an error that quotes the request.
func redact(err error, token string) string {
	text := err.Error()
	if token != "" {
		text = strings.ReplaceAll(text, token, "<token>")
	}
	var urlErr *url.Error
	if errors.As(err, &urlErr) && urlErr.Timeout() {
		text += " (timed out)"
	}
	return text
}

// unitFile is the systemd unit `join` installs: `up` in the foreground, restarted whenever it
// exits, after the network is online.
func unitFile() string {
	return `# Installed by qits-node-tunnel join (epic qits-1977): this node's tunnel to the platform's
# private edge. Its key and tunnel answer are in ` + stateDir + `.
[Unit]
Description=qits private network tunnel (qits0)
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
ExecStart=` + binPath + ` up
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
`
}
