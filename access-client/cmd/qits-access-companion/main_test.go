package main

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"qits.wohlben.eu/access-client/internal/keys"
	"qits.wohlben.eu/access-client/internal/tunnel"
)

// answer is the getPeerTunnel golden master's body: what GET /peers/{id}/tunnel answers.
const answer = `{
  "peerId": "00000000-0000-4000-8000-000000000001",
  "tunnelIp": "100.96.0.10",
  "edge": {
    "publicKey": "AAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAc=",
    "publicAddress": "46.224.171.33",
    "listenPort": 47812,
    "dnsAddress": "100.96.0.1",
    "tunnelCidr": "100.96.0.0/24",
    "mtu": 1380,
    "domain": "wohlben.eu"
  }
}`

func companion(t *testing.T, stdin string, args ...string) (int, string, string) {
	t.Helper()
	var stdout, stderr bytes.Buffer
	code := dispatch(args, strings.NewReader(stdin), &stdout, &stderr)
	return code, stdout.String(), stderr.String()
}

func TestKeygenCreatesTheKeyOnceAndPrintsOnlyItsPublicHalf(t *testing.T) {
	state := t.TempDir()
	code, first, _ := companion(t, "", "keygen", "--state", state)
	if code != exitOK {
		t.Fatalf("keygen exit %d", code)
	}
	path := filepath.Join(state, "private.key")
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("private.key mode %v, want 0600", info.Mode().Perm())
	}
	private, err := keys.Load(path)
	if err != nil {
		t.Fatal(err)
	}
	public, _ := private.Public()
	if strings.TrimSpace(first) != public.Base64() {
		t.Fatalf("printed %q, want the public key %s", first, public.Base64())
	}
	if strings.Contains(first, private.Base64()) {
		t.Fatal("keygen printed the private key")
	}

	code, second, stderr := companion(t, "", "keygen", "--state", state)
	if code != exitOK || second != first {
		t.Fatalf("a second keygen: exit %d, printed %q, want the same key %q", code, second, first)
	}
	if strings.Contains(stderr, "created") {
		t.Fatalf("a second keygen created a key: %q", stderr)
	}
	again, _ := keys.Load(path)
	if again != private {
		t.Fatal("a second keygen replaced the key")
	}
}

func TestKeygenRefusesToReplaceAnUnreadableKeyFile(t *testing.T) {
	state := t.TempDir()
	path := filepath.Join(state, "private.key")
	os.WriteFile(path, []byte("not a key\n"), 0o600)
	if code, _, _ := companion(t, "", "keygen", "--state", state); code != exitFailed {
		t.Fatalf("exit %d, want %d", code, exitFailed)
	}
	if got, _ := os.ReadFile(path); string(got) != "not a key\n" {
		t.Fatalf("the file was rewritten: %q", got)
	}
}

func TestConfigureKeepsTheTunnelAnswer(t *testing.T) {
	state := t.TempDir()
	if code, _, stderr := companion(t, answer, "configure", "--state", state); code != exitOK {
		t.Fatalf("configure exit %d: %s", code, stderr)
	}
	path := filepath.Join(state, "tunnel.json")
	got, err := tunnel.Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if got.PeerID != "00000000-0000-4000-8000-000000000001" || got.TunnelIP != "100.96.0.10" ||
		got.Edge.ListenPort != 47812 || got.Edge.Domain != "wohlben.eu" {
		t.Fatalf("kept %+v", got)
	}
	if info, _ := os.Stat(path); info.Mode().Perm() != 0o600 {
		t.Fatalf("tunnel.json mode %v", info.Mode().Perm())
	}
}

func TestConfigureRefusesAnUnusableAnswerAndWritesNothing(t *testing.T) {
	state := t.TempDir()
	bad := strings.Replace(answer, `"100.96.0.10"`, `"10.0.0.10"`, 1)
	code, _, stderr := companion(t, bad, "configure", "--state", state)
	if code != exitFailed || !strings.Contains(stderr, "outside 100.96.0.0/24") {
		t.Fatalf("exit %d, stderr %q", code, stderr)
	}
	if _, err := os.Stat(filepath.Join(state, "tunnel.json")); !os.IsNotExist(err) {
		t.Fatalf("tunnel.json written: %v", err)
	}
}

func TestRunWithoutStateSaysWhatToRunFirst(t *testing.T) {
	code, _, stderr := companion(t, "", "run", "--state", t.TempDir(), "--listen", "127.0.0.1:0")
	if code != exitFailed || !strings.Contains(stderr, "run `configure` first") {
		t.Fatalf("exit %d, stderr %q", code, stderr)
	}
}

func TestRunDefaultsAdvertiseTo127001AndACompanionFlagOverridesIt(t *testing.T) {
	// The default, unreached because --state is empty, still has to get past validateAdvertise
	// before run() fails on the missing tunnel.json: a bad default would fail with a different
	// message than "run `configure` first".
	code, _, stderr := companion(t, "", "run", "--state", t.TempDir(), "--listen", "127.0.0.1:0")
	if code != exitFailed || !strings.Contains(stderr, "run `configure` first") {
		t.Fatalf("default advertise: exit %d, stderr %q", code, stderr)
	}

	code, _, stderr = companion(t, "", "run", "--state", t.TempDir(), "--listen", "127.0.0.1:0",
		"--advertise", "not-host-port")
	if code != exitFailed || !strings.Contains(stderr, `--advertise "not-host-port"`) {
		t.Fatalf("bad --advertise: exit %d, stderr %q", code, stderr)
	}

	code, _, stderr = companion(t, "", "run", "--state", t.TempDir(), "--listen", "127.0.0.1:0",
		"--advertise", "203.0.113.1:9")
	if code != exitFailed || !strings.Contains(stderr, "run `configure` first") {
		t.Fatalf("usable --advertise override: exit %d, stderr %q", code, stderr)
	}
}

func TestRunAdvertiseEnvFallbackIsOverriddenByTheFlag(t *testing.T) {
	t.Setenv("QITS_COMPANION_ADVERTISE", "not-host-port")
	code, _, stderr := companion(t, "", "run", "--state", t.TempDir(), "--listen", "127.0.0.1:0")
	if code != exitFailed || !strings.Contains(stderr, `--advertise "not-host-port"`) {
		t.Fatalf("env fallback: exit %d, stderr %q", code, stderr)
	}

	code, _, stderr = companion(t, "", "run", "--state", t.TempDir(), "--listen", "127.0.0.1:0",
		"--advertise", "203.0.113.1:9")
	if code != exitFailed || !strings.Contains(stderr, "run `configure` first") {
		t.Fatalf("flag overrides env: exit %d, stderr %q", code, stderr)
	}
}

func TestUsage(t *testing.T) {
	for _, args := range [][]string{{}, {"up"}, {"keygen", "extra"}, {"configure", "--listen", "x"}} {
		if code, _, _ := companion(t, "", args...); code != exitUsage {
			t.Errorf("%v: exit %d, want %d", args, code, exitUsage)
		}
	}
	if code, out, _ := companion(t, "", "--help"); code != exitOK || !strings.Contains(out, "keygen") {
		t.Fatalf("--help: exit %d, %q", code, out)
	}
}
