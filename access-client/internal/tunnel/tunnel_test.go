package tunnel

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// Answer is the joinNode golden master's body (golden-masters/a-target-to-join-…/joinNode.json).
const Answer = `{
  "peerId": "00000000-0000-4000-8000-000000000002",
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

func TestTheServicesAnswerParses(t *testing.T) {
	tun, err := Parse([]byte(Answer))
	if err != nil {
		t.Fatal(err)
	}
	if tun.TunnelIP != "100.96.0.10" || tun.Edge.ListenPort != 47812 || tun.Edge.MTU != 1380 || tun.Edge.Domain != "wohlben.eu" {
		t.Fatalf("parsed %+v", tun)
	}
}

func TestAnUnusableAnswerNamesEveryProblem(t *testing.T) {
	_, err := Parse([]byte(`{"peerId":"x","tunnelIp":"10.0.0.1","edge":{"publicKey":"nope","tunnelCidr":"100.96.0.0/24","dnsAddress":"100.96.0.1","listenPort":0,"mtu":1380,"domain":"d"}}`))
	if err == nil {
		t.Fatal("accepted")
	}
	for _, want := range []string{"tunnelIp 10.0.0.1 is outside", "edge.public key", "no edge.publicAddress", "listenPort 0"} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("%q does not name %q", err, want)
		}
	}
}

func TestSaveAndLoadRoundTripAt0600(t *testing.T) {
	tun, _ := Parse([]byte(Answer))
	path := filepath.Join(t.TempDir(), "tunnel.json")
	if err := Save(path, tun); err != nil {
		t.Fatal(err)
	}
	info, _ := os.Stat(path)
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("mode %v", info.Mode().Perm())
	}
	back, err := Load(path)
	if err != nil || back != tun {
		t.Fatalf("loaded %+v, %v", back, err)
	}
}
