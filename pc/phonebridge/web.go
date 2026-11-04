package main

// The window: a small web UI on 127.0.0.1, opened as an app window (Chromium-family --app) or in
// the default browser. Only reachable with the random token in its URL, and only under the
// 127.0.0.1 host name: web pages you visit can't drive it (no CSRF, no DNS rebinding).

import (
	"crypto/rand"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

//go:embed ui.html
var uiHTML []byte

func urlFile() string { return filepath.Join(runtimeDir(), "phonebridge.url") }

// serveWeb starts the UI server and returns its URL.
func serveWeb(c *controller, quit func()) (string, error) {
	l, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return "", err
	}
	tok := make([]byte, 16)
	rand.Read(tok)
	token := hex.EncodeToString(tok)
	host := l.Addr().String()
	base := "/" + token + "/"

	mux := http.NewServeMux()
	mux.HandleFunc(base, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.Write(uiHTML)
	})
	mux.HandleFunc(base+"api/state", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(c.snapshot())
	})
	post := func(path string, f func(r *http.Request) error) {
		mux.HandleFunc(base+"api/"+path, func(w http.ResponseWriter, r *http.Request) {
			if r.Method != http.MethodPost {
				http.Error(w, "POST only", http.StatusMethodNotAllowed)
				return
			}
			if err := f(r); err != nil {
				http.Error(w, err.Error(), http.StatusBadRequest)
				return
			}
			w.WriteHeader(http.StatusNoContent)
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

	srv := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Host != host || !strings.HasPrefix(r.URL.Path, base) { // other sites / rebinding
			http.NotFound(w, r)
			return
		}
		mux.ServeHTTP(w, r)
	})}
	go srv.Serve(l)
	url := "http://" + host + base
	os.WriteFile(urlFile(), []byte(url+"\n"), 0o600)
	return url, nil
}

// openWindow opens the UI: an app window if a Chromium-family browser is there, else the browser.
func openWindow(url string) {
	for _, b := range []string{"chromium", "google-chrome-stable", "google-chrome", "brave", "brave-browser",
		"microsoft-edge-stable", "microsoft-edge", "vivaldi-stable", "vivaldi"} {
		if p, err := exec.LookPath(b); err == nil {
			exec.Command(p, "--app="+url, "--window-size=520,900", "--class=phonebridge").Start()
			return
		}
	}
	if exec.Command("xdg-open", url).Start() != nil {
		logf("open this in a browser: %s", url)
	}
}

// openRunningWindow: phonebridge is already running: show its window instead.
func openRunningWindow() bool {
	b, err := os.ReadFile(urlFile())
	if err != nil {
		return false
	}
	url := strings.TrimSpace(string(b))
	if url == "" {
		return false
	}
	openWindow(url)
	return true
}
