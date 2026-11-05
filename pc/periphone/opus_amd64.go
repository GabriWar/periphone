//go:build amd64

package main

// #cgo LDFLAGS: ${SRCDIR}/.opus/amd64/libopus.a -lm
import "C"
