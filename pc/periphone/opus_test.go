package main

import (
	"encoding/binary"
	"math"
	"testing"
)

// A 1 kHz tone goes through the encoder and decoder with its level intact.
func TestOpusRoundTrip(t *testing.T) {
	enc, err := newOpusEnc(2, 160000)
	if err != nil {
		t.Fatal(err)
	}
	defer enc.close()
	dec, err := newOpusDec(2)
	if err != nil {
		t.Fatal(err)
	}
	defer dec.close()
	var sumIn, sumOut float64
	for n := 0; n < 40; n++ { // 200 ms
		pcm := make([]byte, frames*4)
		for i := 0; i < frames; i++ {
			v := int16(10000 * math.Sin(2*math.Pi*1000*float64(n*frames+i)/48000))
			binary.LittleEndian.PutUint16(pcm[i*4:], uint16(v))
			binary.LittleEndian.PutUint16(pcm[i*4+2:], uint16(v))
		}
		pkt, err := enc.encode(pcm)
		if err != nil {
			t.Fatal(err)
		}
		out := dec.decode(pkt)
		if n >= 10 { // past the codec's start-up
			for i := 0; i+1 < len(out); i += 2 {
				a := float64(int16(binary.LittleEndian.Uint16(pcm[i:])))
				b := float64(int16(binary.LittleEndian.Uint16(out[i:])))
				sumIn += a * a
				sumOut += b * b
			}
		}
	}
	if r := sumOut / sumIn; r < 0.8 || r > 1.25 {
		t.Errorf("energy ratio out/in = %.2f", r)
	}
}
