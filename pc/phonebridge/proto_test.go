package main

import (
	"encoding/hex"
	"math/big"
	"testing"
)

// Vectors computed independently (Python, same formulas as Proto.java).
func TestVectors(t *testing.T) {
	h := func(b []byte) string { return hex.EncodeToString(sha(b))[:16] }
	if got := h(i2b(gM)); got != "1c7eac5d6891d8e7" {
		t.Errorf("M: %s", got)
	}
	if got := h(i2b(gN)); got != "76be639351968fd9" {
		t.Errorf("N: %s", got)
	}
	k := deriveKeys([]byte("tt"), []byte("K"), []byte("w"))
	if got := hex.EncodeToString(k.phoneToPC) + hex.EncodeToString(k.pcConfirm)[:8]; got != "ea21bc324cb48f1ccb5de6466af8367f80474a4f" {
		t.Errorf("keys: %s", got)
	}
}

// A phone (computing like Proto.Client) completes a handshake with the PC side.
func TestHandshake(t *testing.T) {
	pcSide := newHandshaker("424242")
	for _, pin := range []string{"424242", "000000"} {
		w := new(big.Int).SetBytes(sha([]byte("UAB-PIN"), []byte(pin)))
		w.Mod(w, gQ)
		x := big.NewInt(123456789)
		X := new(big.Int).Exp(gG, x, gP)
		X.Mul(X, new(big.Int).Exp(gM, w, gP)).Mod(X, gP)
		sid := []byte("12345678")
		hello := append([]byte{'H', 1, 1, 1}, sid...)
		hello = append(hello, i2b(X)...)
		p, err := pcSide.reply(hello)
		if err != nil {
			t.Fatal(err)
		}
		Yb := p.reply[9 : 9+256]
		Y := new(big.Int).SetBytes(Yb)
		e := new(big.Int).Sub(gQ, w)
		e.Mod(e, gQ)
		K := new(big.Int).Mul(Y, new(big.Int).Exp(gN, e, gP))
		K.Mod(K, gP).Exp(K, x, gP)
		k := deriveKeys(transcript(1, 1, sid, i2b(X), Yb), i2b(K), i2b(w))
		pcProofOK := string(p.reply[9+256:]) == string(k.pcConfirm)
		confirmOK := pcSide.check(p, append(append([]byte{'C'}, sid...), k.phoneConfirm...))
		if want := pin == "424242"; pcProofOK != want || confirmOK != want {
			t.Errorf("pin %s: proof %v confirm %v", pin, pcProofOK, confirmOK)
		}
	}
	tx, rx := newSealer(make([]byte, 16), true), newSealer(make([]byte, 16), true)
	m := tx.seal(7, []byte("hello"))
	if p, ok := rx.open(m); !ok || string(p) != "hello" {
		t.Error("seal/open")
	}
	m[len(m)-1] ^= 1
	if _, ok := rx.open(m); ok {
		t.Error("tampered packet accepted")
	}
}
