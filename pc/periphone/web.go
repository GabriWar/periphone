package main

// The window: a small web UI on 127.0.0.1, opened as an app window (Chromium-family --app) or in
// the default browser.
//
// Access: the browser is launched with a one-time link (/open/<nonce>, single use, 60 s) that sets
// an HttpOnly SameSite=Strict cookie holding the session token; the page and the API need that
// cookie. On top of that:
//   - every connection must belong to this Linux user (socket owner, from /proc/net/tcp), so other
//     accounts on the PC can't use it even if they grab the one-time link from a command line;
//   - actions (POST) must come from this exact origin with a custom header, which a page from
//     another port of 127.0.0.1 (same "site" for cookies) can't send without a CORS preflight;
//   - other host names are refused (DNS rebinding).

import (
	"bufio"
	"crypto/rand"
	"crypto/subtle"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"time"
)

//go:embed ui.html
var uiHTML []byte

const cookieName = "periphone"

type webUI struct {
	host   string
	token  string
	mu     sync.Mutex
	nonces map[string]time.Time
}

func randHex(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	return hex.EncodeToString(b)
}

const notOpened = `<!doctype html><meta charset="utf-8"><title>Periphone</title>
<body style="background:#0F1115;color:#ECEFF4;font:15px system-ui;padding:32px">
<h2>Periphone</h2><p>This link was already used or has expired.</p>
<p>Open the window from the tray icon, or run <code>periphone</code> again.</p></body>`

// serveWeb starts the UI server; the returned func opens a window on it.
func serveWeb(c *controller, quit func()) (open func(), err error) {
	l, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	w := &webUI{host: l.Addr().String(), token: randHex(16), nonces: map[string]time.Time{}}

	authed := func(r *http.Request) bool {
		ck, err := r.Cookie(cookieName)
		return err == nil && subtle.ConstantTimeCompare([]byte(ck.Value), []byte(w.token)) == 1
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/open/", func(rw http.ResponseWriter, r *http.Request) {
		n := strings.TrimPrefix(r.URL.Path, "/open/")
		w.mu.Lock()
		t, ok := w.nonces[n]
		delete(w.nonces, n) // single use
		w.mu.Unlock()
		if !ok || time.Since(t) > time.Minute {
			rw.Header().Set("Content-Type", "text/html; charset=utf-8")
			rw.WriteHeader(http.StatusForbidden)
			rw.Write([]byte(notOpened))
			return
		}
		http.SetCookie(rw, &http.Cookie{Name: cookieName, Value: w.token, Path: "/",
			HttpOnly: true, SameSite: http.SameSiteStrictMode})
		http.Redirect(rw, r, "/", http.StatusSeeOther)
	})
	mux.HandleFunc("/{$}", func(rw http.ResponseWriter, r *http.Request) {
		rw.Header().Set("Content-Type", "text/html; charset=utf-8")
		rw.Header().Set("Cache-Control", "no-store")
		if !authed(r) {
			rw.WriteHeader(http.StatusForbidden)
			rw.Write([]byte(notOpened))
			return
		}
		rw.Write(uiHTML)
	})
	api := func(path string, h http.HandlerFunc) {
		mux.HandleFunc("/api/"+path, func(rw http.ResponseWriter, r *http.Request) {
			if !authed(r) {
				http.Error(rw, "not allowed", http.StatusForbidden)
				return
			}
			h(rw, r)
		})
	}
	api("state", func(rw http.ResponseWriter, r *http.Request) {
		rw.Header().Set("Content-Type", "application/json")
		json.NewEncoder(rw).Encode(c.snapshot())
	})
	// spectrogram columns: ?out=<seq>&mic=<seq>&phoneAudio=<seq> -> new columns (base64) per stream
	api("spec", func(rw http.ResponseWriter, r *http.Request) {
		lastWatch.Store(time.Now().UnixNano())
		type part struct {
			Seq  int64  `json:"seq"`
			Cols []byte `json:"cols"`
		}
		out := map[string]part{}
		for name, s := range specs {
			from, _ := strconv.ParseInt(r.URL.Query().Get(name), 10, 64)
			cols, seq := s.since(from)
			out[name] = part{seq, cols}
		}
		rw.Header().Set("Content-Type", "application/json")
		json.NewEncoder(rw).Encode(map[string]any{"rows": specRows, "streams": out})
	})
	post := func(path string, f func(r *http.Request) error) {
		api(path, func(rw http.ResponseWriter, r *http.Request) {
			if r.Method != http.MethodPost {
				http.Error(rw, "POST only", http.StatusMethodNotAllowed)
				return
			}
			if r.Header.Get("Origin") != "http://"+w.host || r.Header.Get("X-Periphone") != "1" {
				http.Error(rw, "not allowed", http.StatusForbidden)
				return
			}
			if err := f(r); err != nil {
				http.Error(rw, err.Error(), http.StatusBadRequest)
				return
			}
			rw.WriteHeader(http.StatusNoContent)
		})
	}
	post("apply", func(r *http.Request) error {
		var o options
		if err := json.NewDecoder(r.Body).Decode(&o); err != nil {
			return err
		}
		if o.Buffer != 0 && (o.Buffer < 10 || o.Buffer > 200) {
			o.Buffer = 0
		}
		c.mu.Lock()
		cur := c.opt
		c.mu.Unlock()
		if o.Link == "" {
			o.Link = cur.Link
		}
		if o.Codec == "" {
			o.Codec = "auto"
		}
		go c.apply(o)
		return nil
	})
	post("start", func(*http.Request) error { go c.restart(); return nil })
	post("stop", func(*http.Request) error { go c.stop(); return nil })
	post("pin", func(*http.Request) error { go c.newPIN(); return nil })
	post("camera", func(*http.Request) error { go c.enableCamera(); return nil })
	post("firewall", func(*http.Request) error {
		go func() {
			if err := openFirewall(); err != nil {
				logf("firewall: %v", err)
			}
		}()
		return nil
	})
	post("quit", func(*http.Request) error { go quit(); return nil })

	srv := &http.Server{Handler: http.HandlerFunc(func(rw http.ResponseWriter, r *http.Request) {
		if r.Host != w.host || !sameUser(r.RemoteAddr, w.host) { // DNS rebinding / other accounts
			http.NotFound(rw, r)
			return
		}
		mux.ServeHTTP(rw, r)
	})}
	go srv.Serve(l)
	logf("window: http://%s/", w.host)
	return w.open, nil
}

// sameUser: the client socket (127.0.0.1:port -> us) belongs to this Linux user.
func sameUser(remote, local string) bool {
	_, cport, err1 := net.SplitHostPort(remote)
	_, lport, err2 := net.SplitHostPort(local)
	cp, err3 := strconv.Atoi(cport)
	lp, err4 := strconv.Atoi(lport)
	if err1 != nil || err2 != nil || err3 != nil || err4 != nil {
		return false
	}
	f, err := os.Open("/proc/net/tcp")
	if err != nil {
		return false
	}
	defer f.Close()
	want := fmt.Sprintf("0100007F:%04X 0100007F:%04X", cp, lp) // client socket: local=client, remote=us
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		fl := strings.Fields(sc.Text())
		if len(fl) > 7 && fl[1]+" "+fl[2] == want {
			return fl[7] == strconv.Itoa(os.Getuid())
		}
	}
	return false
}

// open launches a window with a fresh one-time link.
func (w *webUI) open() {
	n := randHex(16)
	w.mu.Lock()
	for k, t := range w.nonces {
		if time.Since(t) > time.Minute {
			delete(w.nonces, k)
		}
	}
	w.nonces[n] = time.Now()
	w.mu.Unlock()
	openWindow("http://" + w.host + "/open/" + n)
}

// openWindow: an app window if a Chromium-family browser is there, else the default browser.
func openWindow(url string) {
	for _, b := range []string{"chromium", "google-chrome-stable", "google-chrome", "brave", "brave-browser",
		"microsoft-edge-stable", "microsoft-edge", "vivaldi-stable", "vivaldi"} {
		if p, err := exec.LookPath(b); err == nil {
			exec.Command(p, "--app="+url, "--window-size=1180,780", "--class=periphone").Start()
			return
		}
	}
	if exec.Command("xdg-open", url).Start() != nil {
		logf("couldn't open a browser: open the window from the tray")
	}
}
