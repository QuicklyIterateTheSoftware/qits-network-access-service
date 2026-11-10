package keys

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLoadOrCreatePersistsTheKey(t *testing.T) {
	path := filepath.Join(t.TempDir(), "node-tunnel", "private.key")
	first, created, err := LoadOrCreate(path)
	if err != nil || !created {
		t.Fatalf("first LoadOrCreate: created=%v err=%v", created, err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("mode %v, want 0600", info.Mode().Perm())
	}
	text, _ := os.ReadFile(path)
	if strings.TrimSpace(string(text)) != first.Base64() || len(strings.TrimSpace(string(text))) != 44 {
		t.Fatalf("file holds %q, want the key's base64", text)
	}
	second, created, err := LoadOrCreate(path)
	if err != nil || created {
		t.Fatalf("second LoadOrCreate: created=%v err=%v", created, err)
	}
	if second != first {
		t.Fatal("the second call generated a new key instead of loading the first")
	}
}

func TestAFileWithoutAKeyIsAnErrorNotReplaced(t *testing.T) {
	path := filepath.Join(t.TempDir(), "private.key")
	if err := os.WriteFile(path, []byte("garbage\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, _, err := LoadOrCreate(path); err == nil {
		t.Fatal("a corrupt key file was accepted")
	}
	text, _ := os.ReadFile(path)
	if string(text) != "garbage\n" {
		t.Fatal("the corrupt key file was overwritten")
	}
}

func TestThePublicKeyIsCurve25519OfThePrivate(t *testing.T) {
	// RFC 7748 section 6.1, Alice's pair.
	priv, err := ParsePrivate("dwdtCnMYpX08FsFyUbJmRd9ML4frwJkqsXf7pR25LCo=")
	if err != nil {
		t.Fatal(err)
	}
	pub, err := priv.Public()
	if err != nil {
		t.Fatal(err)
	}
	if got, want := pub.Hex(), "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"; got != want {
		t.Fatalf("public %s, want %s", got, want)
	}
}

func TestGenerateClamps(t *testing.T) {
	k, err := Generate()
	if err != nil {
		t.Fatal(err)
	}
	if k[0]&7 != 0 || k[31]&128 != 0 || k[31]&64 == 0 {
		t.Fatalf("not clamped: %x", k)
	}
}

func TestParseRefusesAnythingButThirtyTwoBytesOfBase64(t *testing.T) {
	for _, text := range []string{"", "short", "not base64 at all !!", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=="} {
		if _, err := ParsePublic(text); err == nil {
			t.Fatalf("%q parsed", text)
		}
	}
}
