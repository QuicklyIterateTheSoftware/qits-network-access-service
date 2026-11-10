package wgipc

import (
	"bufio"
	"fmt"
	"strconv"
	"strings"
	"time"
)

// LastHandshake reads the newest completed handshake out of the text wireguard-go's Device.IpcGet
// answers: the largest last_handshake_time_sec/last_handshake_time_nsec pair of any peer. A peer
// that never completed a handshake reports 0/0, and so does a device with no peer at all; both
// answer the zero time.
func LastHandshake(ipc string) (time.Time, error) {
	var newest time.Time
	var sec, nsec int64
	var seen bool
	flush := func() {
		if seen && (sec != 0 || nsec != 0) {
			if at := time.Unix(sec, nsec); at.After(newest) {
				newest = at
			}
		}
		sec, nsec, seen = 0, 0, false
	}
	scanner := bufio.NewScanner(strings.NewReader(ipc))
	for scanner.Scan() {
		key, value, ok := strings.Cut(scanner.Text(), "=")
		if !ok {
			continue
		}
		switch key {
		case "public_key":
			flush() // a new peer starts
		case "last_handshake_time_sec", "last_handshake_time_nsec":
			n, err := strconv.ParseInt(value, 10, 64)
			if err != nil {
				return time.Time{}, fmt.Errorf("%s=%q is not a number", key, value)
			}
			if key == "last_handshake_time_sec" {
				sec = n
			} else {
				nsec = n
			}
			seen = true
		}
	}
	if err := scanner.Err(); err != nil {
		return time.Time{}, err
	}
	flush()
	return newest, nil
}
