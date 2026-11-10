package wgipc

import (
	"testing"
	"time"
)

func TestLastHandshakeIsTheNewestPeersPair(t *testing.T) {
	ipc := "private_key=00\nlisten_port=51820\n" +
		"public_key=aa\nendpoint=46.224.171.33:47812\nlast_handshake_time_sec=1700000000\nlast_handshake_time_nsec=5\n" +
		"public_key=bb\nlast_handshake_time_sec=1700000100\nlast_handshake_time_nsec=7\nprotocol_version=1\n"
	got, err := LastHandshake(ipc)
	if err != nil {
		t.Fatal(err)
	}
	if want := time.Unix(1700000100, 7); !got.Equal(want) {
		t.Fatalf("LastHandshake = %v, want %v", got, want)
	}
}

func TestLastHandshakeIsZeroBeforeAnyHandshake(t *testing.T) {
	for _, ipc := range []string{
		"",
		"private_key=00\nlisten_port=51820\n",
		"public_key=aa\nlast_handshake_time_sec=0\nlast_handshake_time_nsec=0\n",
	} {
		got, err := LastHandshake(ipc)
		if err != nil {
			t.Fatal(err)
		}
		if !got.IsZero() {
			t.Fatalf("LastHandshake(%q) = %v, want zero", ipc, got)
		}
	}
}

func TestLastHandshakeRefusesAGarbledNumber(t *testing.T) {
	if _, err := LastHandshake("public_key=aa\nlast_handshake_time_sec=soon\n"); err == nil {
		t.Fatal("a garbled handshake time was read")
	}
}
