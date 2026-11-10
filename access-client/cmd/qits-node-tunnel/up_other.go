//go:build !linux

package main

import (
	"errors"
	"io"
)

// up needs a Linux TUN and rtnetlink; nodes are Linux hosts.
func up(string, io.Writer) error {
	return errors.New("qits-node-tunnel up runs on Linux only")
}
