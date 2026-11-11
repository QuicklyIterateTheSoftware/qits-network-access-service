package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"qits.wohlben.eu/access-client/internal/keys"
	"qits.wohlben.eu/access-client/internal/tunnel"
)

const target = "00000000-0000-4000-8000-000000000001"

// answer is the joinNode golden master's body.
const answer = `{"peerId":"00000000-0000-4000-8000-000000000002","tunnelIp":"100.96.0.10",
 "edge":{"publicKey":"AAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAc=","publicAddress":"46.224.171.33",
 "listenPort":47812,"dnsAddress":"100.96.0.1","tunnelCidr":"100.96.0.0/24","mtu":1380,"domain":"wohlben.eu"}}`

type harness struct {
	j       *joiner
	calls   []string
	active  bool
	stdout  bytes.Buffer
	stderr  bytes.Buffer
	request *http.Request
	body    joinRequest
}

func newHarness(t *testing.T, handler func(w http.ResponseWriter)) (*harness, string) {
	t.Helper()
	h := &harness{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h.request = r
		data, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(data, &h.body)
		handler(w)
	}))
	t.Cleanup(server.Close)
	dir := t.TempDir()
	tunPath := filepath.Join(dir, "tun")
	if err := os.WriteFile(tunPath, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	h.j = &joiner{
		dir:           filepath.Join(dir, "etc", "qits", "node-tunnel"),
		unitPath:      filepath.Join(dir, "qits-node-tunnel.service"),
		client:        server.Client(),
		systemd:       func() bool { return true },
		systemctl:     func(args ...string) error { h.calls = append(h.calls, strings.Join(args, " ")); return nil },
		isActive:      func(string) bool { return h.active },
		hostname:      func() (string, error) { return "Node-7.example.org", nil },
		tunPath:       tunPath,
		osreleasePath: filepath.Join(dir, "osrelease-absent"),
		stdout:        &h.stdout,
		stderr:        &h.stderr,
	}
	return h, server.URL + "/network-access"
}

func env(token string) func(string) string {
	return func(name string) string {
		if name == tokenEnv {
			return token
		}
		return ""
	}
}

func ok(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "application/json")
	_, _ = io.WriteString(w, answer)
}

func TestJoinRegistersTheKeyAndInstallsTheUnit(t *testing.T) {
	h, base := newHarness(t, ok)
	if code := h.j.join([]string{"--url", base, "--target", target}, env("qits_tok_registration")); code != exitJoined {
		t.Fatalf("exit %d: %s", code, h.stderr.String())
	}
	if h.request.Method != http.MethodPost || h.request.URL.Path != "/network-access/api/nodes/"+target+"/join" {
		t.Fatalf("asked %s %s", h.request.Method, h.request.URL.Path)
	}
	if got := h.request.Header.Get("Authorization"); got != "Bearer qits_tok_registration" {
		t.Fatalf("Authorization %q", got)
	}
	if h.body.Name != "node-7" {
		t.Fatalf("name %q, want the short host name lower-cased", h.body.Name)
	}
	private, err := keys.Load(filepath.Join(h.j.dir, "private.key"))
	if err != nil {
		t.Fatal(err)
	}
	public, _ := private.Public()
	if h.body.PublicKey != public.Base64() {
		t.Fatalf("sent %q, the key file's public half is %q", h.body.PublicKey, public.Base64())
	}
	if strings.Contains(h.stdout.String()+h.stderr.String(), private.Base64()) {
		t.Fatal("the private key was printed")
	}
	saved, err := tunnel.Load(filepath.Join(h.j.dir, "tunnel.json"))
	if err != nil || saved.TunnelIP != "100.96.0.10" || saved.Edge.ListenPort != 47812 {
		t.Fatalf("tunnel.json %+v, %v", saved, err)
	}
	if info, _ := os.Stat(filepath.Join(h.j.dir, "tunnel.json")); info.Mode().Perm() != 0o600 {
		t.Fatalf("tunnel.json mode %v", info.Mode().Perm())
	}
	unit, _ := os.ReadFile(h.j.unitPath)
	if string(unit) != unitFile() {
		t.Fatalf("unit file %q", unit)
	}
	if want := []string{"daemon-reload", "enable --now qits-node-tunnel"}; strings.Join(h.calls, "|") != strings.Join(want, "|") {
		t.Fatalf("systemctl %v, want %v", h.calls, want)
	}
}

func TestARejoinKeepsTheKeyAndRestartsARunningTunnel(t *testing.T) {
	h, base := newHarness(t, ok)
	args := []string{"--url", base + "/", "--target", target, "--name", "lab"}
	if code := h.j.join(args, env("tok")); code != exitJoined {
		t.Fatalf("first join exit %d: %s", code, h.stderr.String())
	}
	first := h.body.PublicKey
	h.calls, h.active = nil, true
	if code := h.j.join(args, env("tok")); code != exitJoined {
		t.Fatalf("second join exit %d: %s", code, h.stderr.String())
	}
	if h.body.PublicKey != first {
		t.Fatal("the re-join sent a new key")
	}
	if h.body.Name != "lab" {
		t.Fatalf("name %q", h.body.Name)
	}
	if got := strings.Join(h.calls, "|"); got != "daemon-reload|enable --now qits-node-tunnel|restart qits-node-tunnel" {
		t.Fatalf("systemctl %s", got)
	}
}

func TestARefusalIsExitThreeAndWritesNoUnit(t *testing.T) {
	h, base := newHarness(t, func(w http.ResponseWriter) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusForbidden)
		_, _ = io.WriteString(w, `{"message":"not this target's registration token"}`)
	})
	if code := h.j.join([]string{"--url", base, "--target", target}, env("tok")); code != exitRefused {
		t.Fatalf("exit %d", code)
	}
	if !strings.Contains(h.stderr.String(), "403 not this target's registration token") {
		t.Fatalf("stderr %q", h.stderr.String())
	}
	if _, err := os.Stat(h.j.unitPath); !os.IsNotExist(err) {
		t.Fatal("a refused join installed the unit")
	}
	if len(h.calls) != 0 {
		t.Fatalf("systemctl %v", h.calls)
	}
}

func TestAnUnreachableServiceIsExitThree(t *testing.T) {
	h, _ := newHarness(t, ok)
	if code := h.j.join([]string{"--url", "http://127.0.0.1:1/network-access", "--target", target}, env("tok")); code != exitRefused {
		t.Fatalf("exit %d", code)
	}
	if strings.Contains(h.stderr.String(), "tok") && !strings.Contains(h.stderr.String(), "<token>") {
		t.Logf("stderr %q", h.stderr.String())
	}
}

func TestAnUnusableAnswerIsAFailure(t *testing.T) {
	h, base := newHarness(t, func(w http.ResponseWriter) { _, _ = io.WriteString(w, `{"peerId":"x"}`) })
	if code := h.j.join([]string{"--url", base, "--target", target}, env("tok")); code != exitFailed {
		t.Fatalf("exit %d", code)
	}
	if _, err := os.Stat(filepath.Join(h.j.dir, "tunnel.json")); !os.IsNotExist(err) {
		t.Fatal("an unusable answer was kept")
	}
}

func TestUsageErrorsAreExitTwo(t *testing.T) {
	h, base := newHarness(t, ok)
	for _, c := range []struct {
		args  []string
		token string
	}{
		{nil, "tok"},
		{[]string{"--url", base}, "tok"},
		{[]string{"--target", target}, "tok"},
		{[]string{"--url", "ftp://x", "--target", target}, "tok"},
		{[]string{"--url", base, "--target", target}, ""},
		{[]string{"--url", base, "--target", target, "extra"}, "tok"},
		{[]string{"--bogus"}, "tok"},
	} {
		if code := h.j.join(c.args, env(c.token)); code != exitUsage {
			t.Errorf("%v (token %q): exit %d", c.args, c.token, code)
		}
	}
	if h.request != nil {
		t.Fatal("a usage error reached the service")
	}
}

func TestNoSystemdIsExitFourBeforeAnythingIsRegistered(t *testing.T) {
	h, base := newHarness(t, ok)
	h.j.systemd = func() bool { return false }
	if code := h.j.join([]string{"--url", base, "--target", target}, env("tok")); code != exitNoSystemd {
		t.Fatalf("exit %d", code)
	}
	if h.request != nil {
		t.Fatal("a host without systemd registered a peer")
	}
}

func TestNoSystemdOnWSLMentionsWslConf(t *testing.T) {
	h, base := newHarness(t, ok)
	h.j.systemd = func() bool { return false }
	if err := os.WriteFile(h.j.osreleasePath, []byte("5.15.167.4-microsoft-standard-WSL2\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if code := h.j.join([]string{"--url", base, "--target", target}, env("tok")); code != exitNoSystemd {
		t.Fatalf("exit %d", code)
	}
	if !strings.Contains(h.stderr.String(), "wsl.conf") {
		t.Fatalf("stderr %q, want it to mention wsl.conf", h.stderr.String())
	}
}

func TestNoSystemdOnOtherKernelOmitsWslConf(t *testing.T) {
	h, base := newHarness(t, ok)
	h.j.systemd = func() bool { return false }
	if err := os.WriteFile(h.j.osreleasePath, []byte("6.1.0-amd64\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if code := h.j.join([]string{"--url", base, "--target", target}, env("tok")); code != exitNoSystemd {
		t.Fatalf("exit %d", code)
	}
	if strings.Contains(h.stderr.String(), "wsl.conf") {
		t.Fatalf("stderr %q, want no mention of wsl.conf", h.stderr.String())
	}
}

func TestNoTunIsExitFiveBeforeAnythingIsRegistered(t *testing.T) {
	h, base := newHarness(t, ok)
	h.j.tunPath = filepath.Join(t.TempDir(), "no-such-tun")
	if code := h.j.join([]string{"--url", base, "--target", target}, env("tok")); code != exitNoTun {
		t.Fatalf("exit %d: %s", code, h.stderr.String())
	}
	if h.request != nil {
		t.Fatal("a host without a usable TUN device registered a peer")
	}
	if _, err := os.Stat(filepath.Join(h.j.dir, "private.key")); !os.IsNotExist(err) {
		t.Fatal("a host without a usable TUN device wrote a private key")
	}
	if _, err := os.Stat(filepath.Join(h.j.dir, "tunnel.json")); !os.IsNotExist(err) {
		t.Fatal("a host without a usable TUN device wrote tunnel.json")
	}
	if _, err := os.Stat(h.j.unitPath); !os.IsNotExist(err) {
		t.Fatal("a host without a usable TUN device installed the unit")
	}
	if !strings.Contains(h.stderr.String(), "/dev/net/tun") || !strings.Contains(h.stderr.String(), "lxc.cgroup2.devices.allow") {
		t.Fatalf("stderr %q", h.stderr.String())
	}
}

func TestTheUnitRunsUpAndRestartsIt(t *testing.T) {
	unit := unitFile()
	for _, want := range []string{
		"\nExecStart=/usr/local/bin/qits-node-tunnel up\n",
		"\nRestart=always\n",
		"\nAfter=network-online.target\n",
		"\nWants=network-online.target\n",
		"\nWantedBy=multi-user.target\n",
	} {
		if !strings.Contains(unit, want) {
			t.Errorf("the unit lacks %q:\n%s", strings.TrimSpace(want), unit)
		}
	}
}
