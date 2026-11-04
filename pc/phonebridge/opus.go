package main

// libopus, linked statically (build.sh builds libopus.a into .opus/).

/*
#cgo CFLAGS: -I${SRCDIR}/.opus/include
#cgo LDFLAGS: ${SRCDIR}/.opus/libopus.a -lm
#include <opus.h>
static int set_bitrate(OpusEncoder *e, int b) { return opus_encoder_ctl(e, OPUS_SET_BITRATE(b)); }
*/
import "C"

import (
	"errors"
	"unsafe"
)

type opusEnc struct {
	p   *C.OpusEncoder
	out []byte
}

func newOpusEnc(channels, bitrate int) (*opusEnc, error) {
	var err C.int
	p := C.opus_encoder_create(48000, C.int(channels), C.OPUS_APPLICATION_RESTRICTED_LOWDELAY, &err)
	if err != C.OPUS_OK || p == nil {
		return nil, errors.New("opus_encoder_create failed")
	}
	C.set_bitrate(p, C.int(bitrate))
	return &opusEnc{p: p, out: make([]byte, 1500)}, nil
}

// encode takes one 5 ms chunk of S16LE PCM.
func (e *opusEnc) encode(pcm []byte) ([]byte, error) {
	n := C.opus_encode(e.p, (*C.opus_int16)(unsafe.Pointer(&pcm[0])), frames,
		(*C.uchar)(unsafe.Pointer(&e.out[0])), C.opus_int32(len(e.out)))
	if n < 0 {
		return nil, errors.New("opus_encode failed")
	}
	return append([]byte(nil), e.out[:n]...), nil
}

func (e *opusEnc) close() { C.opus_encoder_destroy(e.p) }

type opusDec struct {
	p   *C.OpusDecoder
	out []byte
}

func newOpusDec(channels int) (*opusDec, error) {
	var err C.int
	p := C.opus_decoder_create(48000, C.int(channels), &err)
	if err != C.OPUS_OK || p == nil {
		return nil, errors.New("opus_decoder_create failed")
	}
	return &opusDec{p: p, out: make([]byte, frames*channels*2)}, nil
}

// decode returns one 5 ms chunk; pkt nil = the packet was lost, conceal it.
func (d *opusDec) decode(pkt []byte) []byte {
	var data *C.uchar
	if len(pkt) > 0 {
		data = (*C.uchar)(unsafe.Pointer(&pkt[0]))
	}
	n := C.opus_decode(d.p, data, C.opus_int32(len(pkt)), (*C.opus_int16)(unsafe.Pointer(&d.out[0])), frames, 0)
	out := make([]byte, len(d.out))
	if n == frames {
		copy(out, d.out)
	}
	return out
}

func (d *opusDec) close() { C.opus_decoder_destroy(d.p) }
