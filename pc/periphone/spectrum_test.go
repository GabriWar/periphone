package main

import (
	"math"
	"testing"
	"time"
)

// A 1 kHz tone at -6 dBFS lights the 1 kHz row, and the rest stays dark.
func TestSpectrumTone(t *testing.T) {
	lastWatch.Store(time.Now().UnixNano())
	s := &spectrum{}
	pcm := make([]int16, 48000/4)
	for i := range pcm {
		pcm[i] = int16(16384 * math.Sin(2*math.Pi*1000*float64(i)/48000))
	}
	s.pushInterleaved(pcm, 1)
	cols, seq := s.since(0)
	if seq == 0 {
		t.Fatal("no columns")
	}
	col := cols[len(cols)-specRows:]
	best := 0
	for r := range col {
		if col[r] > col[best] {
			best = r
		}
	}
	f := specFMin * math.Pow(specFMax/specFMin, (float64(best)+0.5)/specRows)
	if f < 900 || f > 1100 || col[best] < 200 || col[0] > 40 {
		t.Errorf("peak row %d (%.0f Hz) = %d, lowest row = %d", best, f, col[best], col[0])
	}
}
