//go:build arm64

package main

// #cgo LDFLAGS: ${SRCDIR}/.opus/arm64/libopus.a -lm
import "C"
