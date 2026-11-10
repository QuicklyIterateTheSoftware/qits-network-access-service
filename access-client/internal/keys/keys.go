// Package keys holds a WireGuard (Curve25519) key pair the way the access clients keep it: the
// private key in a file of its own, 0600, as the base64 of its 32 bytes — the format `wg genkey`
// prints — and the public key derived from it whenever it is needed. The private key never leaves
// the machine it was generated on: only the public key is sent to qits-network-access.
package keys

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"golang.org/x/crypto/curve25519"
)

// Size is the length of a WireGuard key in bytes.
const Size = 32

// Private is a clamped Curve25519 private key.
type Private [Size]byte

// Public is a Curve25519 public key.
type Public [Size]byte

// Generate makes a new private key from the system's randomness, clamped as WireGuard clamps it.
func Generate() (Private, error) {
	var k Private
	if _, err := rand.Read(k[:]); err != nil {
		return Private{}, fmt.Errorf("generate a private key: %w", err)
	}
	k.clamp()
	return k, nil
}

func (k *Private) clamp() {
	k[0] &= 248
	k[31] = (k[31] & 127) | 64
}

// Public derives the key's public half.
func (k Private) Public() (Public, error) {
	out, err := curve25519.X25519(k[:], curve25519.Basepoint)
	if err != nil {
		return Public{}, fmt.Errorf("derive the public key: %w", err)
	}
	var p Public
	copy(p[:], out)
	return p, nil
}

// Base64 is the key as `wg` prints it.
func (k Private) Base64() string { return base64.StdEncoding.EncodeToString(k[:]) }

// Hex is the key as the WireGuard UAPI (IpcSet) takes it.
func (k Private) Hex() string { return hex.EncodeToString(k[:]) }

// Base64 is the key as `wg pubkey` prints it, and as qits-network-access stores it.
func (p Public) Base64() string { return base64.StdEncoding.EncodeToString(p[:]) }

// Hex is the key as the WireGuard UAPI (IpcSet) takes it.
func (p Public) Hex() string { return hex.EncodeToString(p[:]) }

// ParsePrivate reads a base64 private key: 44 characters decoding to 32 bytes.
func ParsePrivate(text string) (Private, error) {
	raw, err := decode(text)
	if err != nil {
		return Private{}, fmt.Errorf("private key: %w", err)
	}
	var k Private
	copy(k[:], raw)
	return k, nil
}

// ParsePublic reads a base64 public key: 44 characters decoding to 32 bytes.
func ParsePublic(text string) (Public, error) {
	raw, err := decode(text)
	if err != nil {
		return Public{}, fmt.Errorf("public key: %w", err)
	}
	var p Public
	copy(p[:], raw)
	return p, nil
}

func decode(text string) ([]byte, error) {
	text = strings.TrimSpace(text)
	raw, err := base64.StdEncoding.DecodeString(text)
	if err != nil {
		return nil, errors.New("not base64")
	}
	if len(raw) != Size {
		return nil, fmt.Errorf("%d bytes, not %d", len(raw), Size)
	}
	return raw, nil
}

// Load reads the private key file at path.
func Load(path string) (Private, error) {
	text, err := os.ReadFile(path)
	if err != nil {
		return Private{}, err
	}
	k, err := ParsePrivate(string(text))
	if err != nil {
		return Private{}, fmt.Errorf("%s: %w", path, err)
	}
	return k, nil
}

// LoadOrCreate reads the private key file at path, or generates a key and writes it there (0600,
// its directory 0700) when the file does not exist. created says which happened. An existing file
// that holds no key is an error, never silently replaced: the peer registered under it would be
// orphaned.
func LoadOrCreate(path string) (k Private, created bool, err error) {
	k, err = Load(path)
	if err == nil {
		return k, false, nil
	}
	if !errors.Is(err, os.ErrNotExist) {
		return Private{}, false, err
	}
	if k, err = Generate(); err != nil {
		return Private{}, false, err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return Private{}, false, err
	}
	if err := WriteFile(path, []byte(k.Base64()+"\n")); err != nil {
		return Private{}, false, err
	}
	return k, true, nil
}

// WriteFile writes data to path with mode 0600, through a temporary file renamed into place, so a
// reader never sees half a file and the mode holds from the first byte.
func WriteFile(path string, data []byte) error {
	tmp, err := os.CreateTemp(filepath.Dir(path), "."+filepath.Base(path)+".*")
	if err != nil {
		return err
	}
	name := tmp.Name()
	defer os.Remove(name)
	if err := tmp.Chmod(0o600); err != nil {
		tmp.Close()
		return err
	}
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(name, path)
}
