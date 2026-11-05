package main

// Spectrograms for the window, the same math as the app's SpectrogramView: 1024-sample Hann FFT
// every 768 samples (16 ms), 128 log-spaced rows 50 Hz..24 kHz, -90..-20 dBFS mapped to 0..255.
// Only computed while the window is looking (it asks for columns), so a closed window costs
// nothing.

import (
	"math"
	"sync"
	"sync/atomic"
	"time"
)

const (
	specWin   = 1024
	specHop   = 768
	specRows  = 128
	specKeep  = 512 // columns kept for the window to catch up (~8 s)
	specFMin  = 50.0
	specFMax  = 24000.0
	dbFloor   = -90.0
	dbRange   = 70.0
	watchSecs = 3
)

var (
	specCos, specSin, specHann [specWin]float64
	specLo, specHi             [specRows]int
	lastWatch                  atomic.Int64 // unix nanos of the last window request
)

func init() {
	for i := 0; i < specWin; i++ {
		specCos[i] = math.Cos(-2 * math.Pi * float64(i) / specWin)
		specSin[i] = math.Sin(-2 * math.Pi * float64(i) / specWin)
		specHann[i] = 0.5 - 0.5*math.Cos(2*math.Pi*float64(i)/(specWin-1))
	}
	binHz := 48000.0 / specWin
	clamp := func(b int) int { return max(1, min(specWin/2-1, b)) }
	for r := 0; r < specRows; r++ {
		f0 := specFMin * math.Pow(specFMax/specFMin, float64(r)/specRows)
		f1 := specFMin * math.Pow(specFMax/specFMin, float64(r+1)/specRows)
		specLo[r] = clamp(int(math.Round(f0 / binHz)))
		specHi[r] = max(specLo[r]+1, clamp(int(math.Round(f1/binHz))))
	}
}

func watching() bool { return time.Now().UnixNano()-lastWatch.Load() < watchSecs*int64(time.Second) }

type spectrum struct {
	mu     sync.Mutex
	pend   []float64 // mono samples not analyzed yet
	cols   [specKeep][]byte
	seq    int64 // columns made so far
	re, im [specWin]float64
}

// specs: one per audio stream, as named in the window.
var specs = map[string]*spectrum{"out": {}, "mic": {}, "phoneAudio": {}}

// pushInterleaved adds S16 samples (1 or 2 channels; stereo is averaged to mono).
func (s *spectrum) pushInterleaved(pcm []int16, channels int) {
	if !watching() {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for i := 0; i+channels-1 < len(pcm); i += channels {
		v := float64(pcm[i])
		if channels == 2 {
			v = (v + float64(pcm[i+1])) / 2
		}
		s.pend = append(s.pend, v/32768)
	}
	for len(s.pend) >= specWin {
		s.column()
		s.pend = s.pend[specHop:]
	}
	if cap(s.pend) > 16*specWin { // don't let the backing array grow forever
		s.pend = append([]float64(nil), s.pend...)
	}
}

func (s *spectrum) column() {
	for i := 0; i < specWin; i++ {
		s.re[i], s.im[i] = s.pend[i]*specHann[i], 0
	}
	fft(&s.re, &s.im)
	const norm = 4.0 / specWin // full-scale sine peak with Hann = N/4
	col := make([]byte, specRows)
	for r := 0; r < specRows; r++ {
		m := 0.0
		for k := specLo[r]; k < specHi[r]; k++ {
			m = max(m, math.Hypot(s.re[k], s.im[k]))
		}
		db := 20 * math.Log10(m*norm+1e-9)
		t := math.Max(0, math.Min(1, (db-dbFloor)/dbRange))
		col[r] = byte(t * 255)
	}
	s.cols[s.seq%specKeep] = col
	s.seq++
}

// since returns the columns after seq (at most what's kept) and the new seq.
func (s *spectrum) since(seq int64) ([]byte, int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if seq < s.seq-specKeep || seq > s.seq {
		seq = max(0, s.seq-specKeep)
	}
	out := make([]byte, 0, (s.seq-seq)*specRows)
	for i := seq; i < s.seq; i++ {
		out = append(out, s.cols[i%specKeep]...)
	}
	return out, s.seq
}

func fft(re, im *[specWin]float64) {
	n := specWin
	for i, j := 1, 0; i < n; i++ {
		bit := n >> 1
		for ; j&bit != 0; bit >>= 1 {
			j ^= bit
		}
		j ^= bit
		if i < j {
			re[i], re[j] = re[j], re[i]
			im[i], im[j] = im[j], im[i]
		}
	}
	for size := 2; size <= n; size <<= 1 {
		half, step := size>>1, n/size
		for i := 0; i < n; i += size {
			for k := 0; k < half; k++ {
				wr, wi := specCos[k*step], specSin[k*step]
				a, b := i+k, i+k+half
				xr := re[b]*wr - im[b]*wi
				xi := re[b]*wi + im[b]*wr
				re[b], im[b] = re[a]-xr, im[a]-xi
				re[a] += xr
				im[a] += xi
			}
		}
	}
}
