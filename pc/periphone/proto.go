package main

// Wire protocol with the app over Wi-Fi / Bluetooth. Must match
// src/dev/gabriwar/periphone/Proto.java (message layout, SPAKE2 constants, key derivation).

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"math/big"
	"sync"
	"time"
)

const (
	protoVersion = 1

	roleSpk, roleMic, roleCam, roleAudio = 1, 2, 3, 4

	fEncrypt, fOpus, fRedundant = 1, 2, 4

	ePin, eDown, eUnknown, eBusy, eUnsupported = 1, 2, 3, 4, 5

	helloLen     = 1 + 3 + 8 + 256
	confirmLen   = 1 + 8 + 32
	hdrLen       = 5
	maxRedundant = 1400 // payload with the previous chunk: stays under the Wi-Fi MTU

	frames = 240 // 5 ms at 48 kHz
)

var roleNames = map[byte]string{
	roleSpk: "PC audio -> phone", roleMic: "phone mic -> PC",
	roleCam: "phone camera -> PC", roleAudio: "phone audio -> PC",
}

// SPAKE2 over the RFC 3526 2048-bit group.
var (
	gP, _ = new(big.Int).SetString(
		"FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A0879"+
			"8E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B"+
			"0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA4836"+
			"1C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804"+
			"F1746C08CA18217C32905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6"+
			"955817183995497CEA956AE515D2261898FA051015728E5A8AACAA68FFFFFFFFFFFFFFFF", 16)
	gQ = new(big.Int).Rsh(gP, 1)
	gG = big.NewInt(4)
	gM = hashToGroup("UAB-SPAKE2-M")
	gN = hashToGroup("UAB-SPAKE2-N")
)

func sha(parts ...[]byte) []byte {
	h := sha256.New()
	for _, p := range parts {
		h.Write(p)
	}
	return h.Sum(nil)
}

func mac(key []byte, parts ...[]byte) []byte {
	h := hmac.New(sha256.New, key)
	for _, p := range parts {
		h.Write(p)
	}
	return h.Sum(nil)
}

func hashToGroup(label string) *big.Int {
	var wide []byte
	for i := 0; i < 9; i++ {
		wide = append(wide, sha([]byte(label), []byte{byte(i)})...)
	}
	v := new(big.Int).SetBytes(wide)
	v.Mod(v, gP)
	return v.Exp(v, big.NewInt(2), gP)
}

func i2b(v *big.Int) []byte { return v.FillBytes(make([]byte, 256)) }

func inGroup(v *big.Int) bool {
	pm1 := new(big.Int).Sub(gP, big.NewInt(1))
	return v.Cmp(big.NewInt(1)) > 0 && v.Cmp(pm1) < 0 &&
		new(big.Int).Exp(v, gQ, gP).Cmp(big.NewInt(1)) == 0
}

func transcript(role, flags byte, sid, X, Y []byte) []byte {
	t := append([]byte("UAB1"), protoVersion, role, flags)
	t = append(t, sid...)
	t = append(t, X...)
	return append(t, Y...)
}

type keys struct{ phoneToPC, pcToPhone, pcConfirm, phoneConfirm []byte }

func deriveKeys(tt, K, w []byte) keys {
	h := sha(tt, K, w)
	return keys{
		phoneToPC:    mac(h, []byte("phone->pc"))[:16],
		pcToPhone:    mac(h, []byte("pc->phone"))[:16],
		pcConfirm:    mac(mac(h, []byte("confirm pc")), tt),
		phoneConfirm: mac(mac(h, []byte("confirm phone")), tt),
	}
}

type hsError byte

func (e hsError) Error() string { return "handshake error " + string('0'+byte(e)) }

// handshaker is the PC side of the PIN check. Every REPLY is one PIN guess for whoever sent the
// HELLO (they can check our proof against their guess), whether or not they confirm. So each
// REPLY spends a token and only a valid CONFIRM gives it back: a real phone costs nothing, a
// guesser gets ~1 try per 10 s (a 6-digit PIN would take ~2 months on average).
type handshaker struct {
	w, mInvW, nW *big.Int
	mu           sync.Mutex
	tokens       float64
	t, warned    time.Time
}

const bucket, refill = 8.0, 10 * time.Second

func newHandshaker(pin string) *handshaker {
	w := new(big.Int).SetBytes(sha([]byte("UAB-PIN"), []byte(pin)))
	w.Mod(w, gQ)
	e := new(big.Int).Sub(gQ, w)
	e.Mod(e, gQ)
	return &handshaker{
		w: w, mInvW: new(big.Int).Exp(gM, e, gP), nW: new(big.Int).Exp(gN, w, gP),
		tokens: bucket, t: time.Now(),
	}
}

type pending struct {
	role, flags byte
	sid, reply  []byte
	k           keys
}

func (h *handshaker) reply(hello []byte) (*pending, error) {
	if len(hello) != helloLen || hello[0] != 'H' {
		return nil, hsError(eUnsupported)
	}
	ver, role, flags := hello[1], hello[2], hello[3]
	if ver != protoVersion || roleNames[role] == "" {
		return nil, hsError(eUnsupported)
	}
	Xb := hello[12:]
	X := new(big.Int).SetBytes(Xb)
	if !inGroup(X) {
		return nil, hsError(eUnsupported)
	}
	h.mu.Lock()
	now := time.Now()
	h.tokens = min(bucket, h.tokens+now.Sub(h.t).Seconds()/refill.Seconds())
	h.t = now
	if h.tokens < 1 {
		if now.Sub(h.warned) > time.Minute {
			h.warned = now
			logf("many unfinished or wrong PIN attempts: slowing down handshakes" +
				" (someone guessing the PIN? change it: periphone pin new)")
		}
		h.mu.Unlock()
		return nil, hsError(eBusy)
	}
	h.tokens--
	h.mu.Unlock()

	y, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 256))
	y.Add(y, big.NewInt(1))
	Y := new(big.Int).Exp(gG, y, gP)
	Y.Mul(Y, h.nW).Mod(Y, gP)
	K := new(big.Int).Mul(X, h.mInvW)
	K.Mod(K, gP).Exp(K, y, gP)
	sid := append([]byte(nil), hello[4:12]...)
	Yb := i2b(Y)
	k := deriveKeys(transcript(role, flags, sid, Xb, Yb), i2b(K), i2b(h.w))
	reply := append([]byte{'R'}, sid...)
	reply = append(reply, Yb...)
	reply = append(reply, k.pcConfirm...)
	return &pending{role: role, flags: flags, sid: sid, reply: reply, k: k}, nil
}

func (h *handshaker) check(p *pending, confirm []byte) bool {
	ok := len(confirm) == confirmLen && hmac.Equal(confirm[9:], p.k.phoneConfirm)
	if ok {
		h.mu.Lock()
		h.tokens = min(bucket, h.tokens+1)
		h.mu.Unlock()
	}
	return ok
}

func errorMsg(sid []byte, code byte) []byte {
	if len(sid) != 8 {
		sid = make([]byte, 8)
	}
	return append(append([]byte{'E'}, sid...), code)
}

// sealer: AES-128-GCM for one direction, or plain when encryption is off. nonce = 0^8 | seq.
type sealer struct{ aead cipher.AEAD }

func newSealer(key []byte, encrypt bool) *sealer {
	if !encrypt {
		return &sealer{}
	}
	b, _ := aes.NewCipher(key)
	a, _ := cipher.NewGCM(b)
	return &sealer{aead: a}
}

func nonce(seq uint32) []byte {
	n := make([]byte, 12)
	binary.BigEndian.PutUint32(n[8:], seq)
	return n
}

func (s *sealer) seal(seq uint32, payload []byte) []byte {
	m := make([]byte, hdrLen, hdrLen+len(payload)+16)
	m[0] = 'D'
	binary.BigEndian.PutUint32(m[1:], seq)
	if s.aead == nil {
		return append(m, payload...)
	}
	return s.aead.Seal(m, nonce(seq), payload, m[:hdrLen])
}

func (s *sealer) open(m []byte) ([]byte, bool) {
	if len(m) < hdrLen {
		return nil, false
	}
	if s.aead == nil {
		return m[hdrLen:], true
	}
	p, err := s.aead.Open(nil, nonce(binary.BigEndian.Uint32(m[1:])), m[hdrLen:], m[:hdrLen])
	return p, err == nil
}
