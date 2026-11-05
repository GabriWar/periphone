//go:build arm

package main

// #cgo LDFLAGS: ${SRCDIR}/.opus/arm/libopus.a -lm
import "C"
