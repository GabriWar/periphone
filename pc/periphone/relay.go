package main

// Wi-Fi / Bluetooth side: UDP (audio) and TCP (camera) on netPort, RFCOMM via bt.go.
// One session per stream, after the PIN handshake (proto.go).

import (
	"encoding/binary"
	"errors"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

const (
	netPort     = 12350
	idleTimeout = 5 * time.Second
	tosVoice    = 0xB8 // DSCP EF: Wi-Fi voice queue
)

type session struct {
	b           *bridge
	role, flags byte
	sid         []byte
	tx, rx      *sealer
	send        func([]byte) error
	udp         bool
	label       string
	red         bool

	mu       sync.Mutex // onData
	seqOut   atomic.Uint32
	lastIn   int64
	lastSeen atomic.Int64
	dec      *opusDec
	cam      *io.PipeWriter
	untrack  func()
	done     chan struct{}
	once     sync.Once
}

func newSession(b *bridge, p *pending, send func([]byte) error, udp bool, label string) (*session, error) {
	s := &session{b: b, role: p.role, flags: p.flags, sid: p.sid, send: send, udp: udp, label: label,
		tx: newSealer(p.k.pcToPhone, p.flags&fEncrypt != 0), rx: newSealer(p.k.phoneToPC, p.flags&fEncrypt != 0),
		red: p.flags&fRedundant != 0 && p.role != roleCam, lastIn: -1, done: make(chan struct{})}
	s.lastSeen.Store(time.Now().UnixNano())
	opus := p.flags&fOpus != 0 && p.role != roleCam
	if opus && (p.role == roleMic || p.role == roleAudio) {
		ch := 2
		if p.role == roleMic {
			ch = 1
		}
		var err error
		if s.dec, err = newOpusDec(ch); err != nil {
			return nil, err
		}
	}
	if p.role == roleCam {
		cam, _ := b.getCam()
		if cam == nil {
			return nil, errors.New("camera receiver off")
		}
		r, w := io.Pipe()
		s.cam = w
		go cam.serve(r)
	}
	codec := "pcm"
	if opus {
		codec = "opus"
	} else if p.role == roleCam {
		codec = "h264"
	}
	enc := "encrypted"
	if p.flags&fEncrypt == 0 {
		enc = "NOT encrypted"
	}
	logf("%s: %s connected (%s, %s)", label, roleNames[p.role], codec, enc)
	b.connected(p.role, +1)
	s.untrack = b.track(p.role, p.flags&fEncrypt == 0, func() { s.close("settings changed on the PC") })
	return s, nil
}

// checkEncryption: this PC's setting wins; a phone asking for plain streams is refused.
func (b *bridge) checkEncryption(p *pending, label string) error {
	if b.getOpt().Encrypt && p.flags&fEncrypt == 0 {
		logf("%s: refused %s: the phone has encryption off, this PC requires it", label, roleNames[p.role])
		return hsError(eUnsupported)
	}
	return nil
}

// start: after the OK went out, so the phone never sees data before it.
func (s *session) start() {
	if s.role == roleSpk {
		go s.pump()
	}
}

func (s *session) sendData(payload []byte) error {
	return s.send(s.tx.seal(s.seqOut.Add(1)-1, payload))
}

// pump: PC audio -> phone, one 5 ms packet at a time.
func (s *session) pump() {
	ch := s.b.au.subscribe()
	defer s.b.au.unsubscribe(ch)
	var enc *opusEnc
	if s.flags&fOpus != 0 {
		var err error
		if enc, err = newOpusEnc(2, 160000); err != nil {
			s.close("opus: " + err.Error())
			return
		}
		defer enc.close()
	}
	var prev []byte
	for {
		select {
		case <-s.done:
			return
		case chunk := <-ch:
			cur := chunk
			if enc != nil {
				var err error
				if cur, err = enc.encode(chunk); err != nil {
					continue
				}
			}
			payload := cur
			if s.red {
				payload = binary.BigEndian.AppendUint16(nil, uint16(len(cur)))
				payload = append(payload, cur...)
				if 2+len(cur)+len(prev) <= maxRedundant {
					payload = append(payload, prev...)
				}
				prev = cur
			}
			if err := s.sendData(payload); err != nil && !s.udp {
				s.close("connection closed")
				return
			}
		}
	}
}

func (s *session) onData(m []byte) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(m) < hdrLen {
		return
	}
	seq := int64(binary.BigEndian.Uint32(m[1:]))
	gap := int64(0)
	if s.lastIn >= 0 {
		d := (seq - s.lastIn) & 0xFFFFFFFF
		if d == 0 || d > 0x80000000 { // duplicate, late or replayed
			return
		}
		gap = d - 1
	}
	payload, ok := s.rx.open(m)
	if !ok {
		return
	}
	s.lastIn = seq
	s.lastSeen.Store(time.Now().UnixNano())
	switch s.role {
	case roleCam:
		if _, err := s.cam.Write(payload); err != nil {
			go s.close("camera receiver closed")
		}
		return
	case roleSpk:
		return
	}
	var before []byte
	if s.red {
		if len(payload) < 2 {
			return
		}
		cl := int(binary.BigEndian.Uint16(payload))
		if 2+cl > len(payload) {
			return
		}
		payload, before = payload[2:2+cl], payload[2+cl:]
	}
	if gap > 0 && gap <= 50 { // lost packets: keep the timing, fill the hole
		holes := min(gap, 8)
		if len(before) > 0 {
			holes--
		}
		for i := int64(0); i < holes; i++ {
			s.writePCM(nil)
		}
		if len(before) > 0 { // the last lost one came back inside this packet
			s.writePCM(before)
		}
	}
	s.writePCM(payload)
}

func (s *session) writePCM(data []byte) {
	r, size := s.b.au.mic, chunkMono
	if s.role == roleAudio {
		r, size = s.b.au.phone, chunkSt
	}
	var pcm []byte
	switch {
	case s.dec != nil:
		pcm = s.dec.decode(data)
	case data == nil:
		pcm = make([]byte, size)
	default:
		pcm = data
	}
	if len(pcm) == size {
		r.write(pcm)
	}
}

func (s *session) close(why string) {
	s.once.Do(func() {
		close(s.done)
		if s.cam != nil {
			s.cam.Close()
		}
		s.mu.Lock()
		if s.dec != nil {
			s.dec.close()
			s.dec = nil
		}
		s.mu.Unlock()
		if s.untrack != nil {
			s.untrack()
		}
		s.b.connected(s.role, -1)
		logf("%s: %s disconnected (%s)", s.label, roleNames[s.role], why)
	})
}

func (s *session) closed() bool {
	select {
	case <-s.done:
		return true
	default:
		return false
	}
}

// ---------------------------------------------------------------- UDP

type udpPending struct {
	p *pending
	t time.Time
}

type udpServer struct {
	b        *bridge
	conn     *net.UDPConn
	label    string
	mu       sync.Mutex
	sessions map[string]*session
	pend     map[string]*udpPending
	unknown  map[string]time.Time
}

func (b *bridge) serveUDP(label string) error {
	c, err := net.ListenUDP("udp4", &net.UDPAddr{Port: netPort})
	if err != nil {
		return err
	}
	if rc, err := c.SyscallConn(); err == nil {
		rc.Control(func(fd uintptr) { setTOS(int(fd), tosVoice) })
	}
	u := &udpServer{b: b, conn: c, label: label, sessions: map[string]*session{},
		pend: map[string]*udpPending{}, unknown: map[string]time.Time{}}
	b.closers = append(b.closers, c)
	go u.janitor()
	go func() {
		buf := make([]byte, 4096)
		for {
			n, addr, err := c.ReadFromUDP(buf)
			if err != nil {
				if errors.Is(err, net.ErrClosed) {
					u.closeAll()
					return
				}
				continue
			}
			if n > 0 {
				u.handle(append([]byte(nil), buf[:n]...), addr)
			}
		}
	}()
	return nil
}

func (u *udpServer) sender(addr *net.UDPAddr) func([]byte) error {
	return func(m []byte) error {
		u.conn.WriteToUDP(m, addr) // Wi-Fi hiccup: the packet is lost, the stream goes on
		return nil
	}
}

func (u *udpServer) handle(m []byte, addr *net.UDPAddr) {
	key := addr.String()
	label := u.label + " " + addr.IP.String()
	u.mu.Lock()
	s := u.sessions[key]
	p := u.pend[key]
	u.mu.Unlock()
	switch m[0] {
	case 'D':
		if s != nil {
			s.onData(m)
		} else {
			u.tellUnknown(addr)
		}
	case 'P':
		if s != nil {
			s.lastSeen.Store(time.Now().UnixNano())
		} else {
			u.tellUnknown(addr)
		}
	case 'W': // discovery broadcast
		u.conn.WriteToUDP([]byte{'w'}, addr)
	case 'B':
		if s != nil {
			u.drop(key, s, "stopped on the phone")
		}
	case 'H':
		if len(m) < 12 {
			return
		}
		sid := m[4:12]
		if p != nil && string(p.p.sid) == string(sid) { // retransmitted HELLO
			u.conn.WriteToUDP(p.p.reply, addr)
			return
		}
		if s != nil && string(s.sid) == string(sid) {
			return
		}
		np, err := u.b.hs.reply(m)
		if err == nil && np.role == roleCam { // the camera goes over TCP (lossless)
			err = hsError(eUnsupported)
		}
		if err == nil {
			err = u.b.checkEncryption(np, label)
		}
		if err == nil && !u.b.wants(np.role) {
			err = hsError(eDown)
		}
		if err != nil {
			var code byte = eUnsupported
			var he hsError
			if errors.As(err, &he) {
				code = byte(he)
			}
			u.conn.WriteToUDP(errorMsg(sid, code), addr)
			return
		}
		u.mu.Lock()
		u.pend[key] = &udpPending{p: np, t: time.Now()}
		u.mu.Unlock()
		u.conn.WriteToUDP(np.reply, addr)
	case 'C':
		if len(m) < 9 {
			return
		}
		sid := m[1:9]
		if s != nil && string(s.sid) == string(sid) { // our OK got lost
			u.conn.WriteToUDP(append([]byte{'O'}, sid...), addr)
			return
		}
		if p == nil || string(p.p.sid) != string(sid) {
			u.conn.WriteToUDP(errorMsg(sid, eUnknown), addr)
			return
		}
		u.mu.Lock()
		delete(u.pend, key)
		u.mu.Unlock()
		if !u.b.hs.check(p.p, m) {
			logf("%s: wrong PIN", label)
			u.conn.WriteToUDP(errorMsg(sid, ePin), addr)
			return
		}
		ns, err := newSession(u.b, p.p, u.sender(addr), true, label)
		if err != nil {
			u.conn.WriteToUDP(errorMsg(sid, eDown), addr)
			return
		}
		if s != nil {
			u.drop(key, s, "replaced")
		}
		u.mu.Lock()
		u.sessions[key] = ns
		u.mu.Unlock()
		u.conn.WriteToUDP(append([]byte{'O'}, sid...), addr)
		ns.start()
	}
}

// tellUnknown: data from a phone we don't know (we restarted?): tell it to shake hands again.
func (u *udpServer) tellUnknown(addr *net.UDPAddr) {
	u.mu.Lock()
	last := u.unknown[addr.String()]
	ok := time.Since(last) > time.Second
	if ok {
		u.unknown[addr.String()] = time.Now()
	}
	u.mu.Unlock()
	if ok {
		u.conn.WriteToUDP(errorMsg(nil, eUnknown), addr)
	}
}

func (u *udpServer) drop(key string, s *session, why string) {
	u.mu.Lock()
	if u.sessions[key] == s {
		delete(u.sessions, key)
	}
	u.mu.Unlock()
	s.close(why)
}

func (u *udpServer) janitor() {
	for range time.Tick(time.Second) {
		if u.b.isClosed() {
			return
		}
		u.mu.Lock()
		now := time.Now()
		var dead []string
		for k, s := range u.sessions {
			if s.closed() || now.Sub(time.Unix(0, s.lastSeen.Load())) > idleTimeout {
				dead = append(dead, k)
			}
		}
		for k, p := range u.pend {
			if now.Sub(p.t) > 10*time.Second {
				delete(u.pend, k)
			}
		}
		if len(u.unknown) > 1000 {
			u.unknown = map[string]time.Time{}
		}
		live := map[string]*session{}
		for k, s := range u.sessions {
			live[k] = s
		}
		u.mu.Unlock()
		for _, k := range dead {
			u.drop(k, live[k], "phone stopped answering")
		}
		for k, s := range live {
			if !s.closed() {
				if a, err := net.ResolveUDPAddr("udp4", k); err == nil {
					u.conn.WriteToUDP([]byte{'P'}, a)
				}
			}
		}
	}
}

func (u *udpServer) closeAll() {
	u.mu.Lock()
	all := u.sessions
	u.sessions = map[string]*session{}
	u.mu.Unlock()
	for _, s := range all {
		s.close("bridge stopped")
	}
}

// ---------------------------------------------------------------- streams: TCP camera, RFCOMM

func (b *bridge) serveTCP(label string) error {
	l, err := net.ListenTCP("tcp4", &net.TCPAddr{Port: netPort})
	if err != nil {
		return err
	}
	b.closers = append(b.closers, l)
	go func() {
		for {
			c, err := l.AcceptTCP()
			if err != nil {
				return
			}
			c.SetNoDelay(true)
			go b.serveStream(c, map[byte]bool{roleCam: true}, label+" "+c.RemoteAddr().(*net.TCPAddr).IP.String())
		}
	}()
	return nil
}

// serveStream: one connection = one stream, messages framed as [length u32][message].
func (b *bridge) serveStream(c io.ReadWriteCloser, roles map[byte]bool, label string) {
	defer c.Close()
	var wmu sync.Mutex
	send := func(m []byte) error {
		wmu.Lock()
		defer wmu.Unlock()
		_, err := c.Write(append(binary.BigEndian.AppendUint32(nil, uint32(len(m))), m...))
		return err
	}
	read := func() ([]byte, error) {
		var h [4]byte
		if _, err := io.ReadFull(c, h[:]); err != nil {
			return nil, err
		}
		n := binary.BigEndian.Uint32(h[:])
		if n == 0 || n > 8<<20 {
			return nil, errors.New("bad message length")
		}
		m := make([]byte, n)
		_, err := io.ReadFull(c, m)
		return m, err
	}
	setDeadline := func(t time.Time) {
		if d, ok := c.(interface{ SetDeadline(time.Time) error }); ok {
			d.SetDeadline(t)
		}
	}
	setDeadline(time.Now().Add(10 * time.Second))
	hello, err := read()
	if err != nil || len(hello) < 12 {
		return
	}
	p, err := b.hs.reply(hello)
	if err == nil && !roles[p.role] {
		err = hsError(eUnsupported)
	}
	if err == nil {
		err = b.checkEncryption(p, label)
	}
	if err == nil && !b.wants(p.role) {
		err = hsError(eDown)
	}
	if err != nil {
		var code byte = eUnsupported
		var he hsError
		if errors.As(err, &he) {
			code = byte(he)
		}
		send(errorMsg(hello[4:12], code))
		return
	}
	send(p.reply)
	confirm, err := read()
	if err != nil {
		return
	}
	if !b.hs.check(p, confirm) {
		logf("%s: wrong PIN", label)
		send(errorMsg(p.sid, ePin))
		return
	}
	setDeadline(time.Time{})
	s, err := newSession(b, p, send, false, label)
	if err != nil {
		send(errorMsg(p.sid, eDown))
		return
	}
	send(append([]byte{'O'}, p.sid...))
	s.start()
	go func() { <-b.done; c.Close() }()
	defer s.close("connection closed")
	if p.role == roleSpk { // nothing comes back: wait for the close
		buf := make([]byte, 64)
		for !s.closed() {
			if _, err := c.Read(buf); err != nil {
				return
			}
		}
		return
	}
	for !s.closed() {
		m, err := read()
		if err != nil {
			return
		}
		switch m[0] {
		case 'D':
			s.onData(m)
		case 'B':
			return
		}
	}
}
