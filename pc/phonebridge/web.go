package main

// The window: a small web UI on 127.0.0.1, opened as an app window (Chromium-family --app) or in
// the default browser.
//
// Access: the browser is launched with a one-time link (/open/<nonce>, single use, 60 s) that sets
// an HttpOnly SameSite=Strict cookie holding the session token; the page and the API need that
// cookie. So the token never appears on a command line (/proc is readable by other local users)
// or in the log, web pages you visit can't drive it (no CSRF), and other host names are refused
// (no DNS rebinding).

import (
	"crypto/rand"
	"crypto/subtle"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"net"
	"net/http"
	"os/exec"
	"strings"
	"sync"
	"time"
)

//go:embed ui.html
var uiHTML []byte

const cookieName = "phonebridge"

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

const notOpened = `<!doctype html><meta charset="utf-8"><title>Phone Bridge</title>
<body style="background:#0F1115;color:#ECEFF4;font:15px system-ui;padding:32px">
<h2>Phone Bridge</h2><p>This link was already used or has expired.</p>
<p>Open the window from the tray icon, or run <code>phonebridge</code> again.</p></body>`

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
	post := func(path string, f func(r *http.Request) error) {
		api(path, func(rw http.ResponseWriter, r *http.Request) {
			if r.Method != http.MethodPost {
				http.Error(rw, "POST only", http.StatusMethodNotAllowed)
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
	post("quit", func(*http.Request) error { go quit(); return nil })

	srv := &http.Server{Handler: http.HandlerFunc(func(rw http.ResponseWriter, r *http.Request) {
		if r.Host != w.host { // other sites / DNS rebinding
			http.NotFound(rw, r)
			return
		}
		mux.ServeHTTP(rw, r)
	})}
	go srv.Serve(l)
	logf("window: http://%s/", w.host)
	return w.open, nil
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
			exec.Command(p, "--app="+url, "--window-size=520,900", "--class=phonebridge").Start()
			return
		}
	}
	if exec.Command("xdg-open", url).Start() != nil {
		logf("couldn't open a browser: open the window from the tray")
	}
}
