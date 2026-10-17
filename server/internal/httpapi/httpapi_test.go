// Pixel Dungeon
// Copyright (C) 2012-2015 Oleg Dolya
//
// Shattered Pixel Dungeon
// Copyright (C) 2014-2026 Evan Debenham
//
// Logic Link PD — Copyright (C) 2026 Logic Link PD contributors
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>

package httpapi

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func newServer(t *testing.T, shutdown context.Context) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(New(Options{
		Version:  "test-1",
		Logger:   slog.New(slog.NewTextHandler(io.Discard, nil)),
		Shutdown: shutdown,
	}))
	t.Cleanup(srv.Close)
	return srv
}

func TestHealthz(t *testing.T) {
	srv := newServer(t, nil)
	res, err := http.Get(srv.URL + "/healthz")
	if err != nil {
		t.Fatalf("GET /healthz: %v", err)
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200", res.StatusCode)
	}
	if ct := res.Header.Get("Content-Type"); ct != "application/json" {
		t.Errorf("Content-Type = %q", ct)
	}
	var body map[string]string
	if err := json.NewDecoder(res.Body).Decode(&body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if body["status"] != "ok" || body["version"] != "test-1" {
		t.Fatalf("body = %v", body)
	}
}

func TestUnknownPathIs404(t *testing.T) {
	srv := newServer(t, nil)
	for _, path := range []string{"/", "/admin", "/healthz/x"} {
		res, err := http.Get(srv.URL + path)
		if err != nil {
			t.Fatalf("GET %s: %v", path, err)
		}
		res.Body.Close()
		if res.StatusCode != http.StatusNotFound {
			t.Errorf("GET %s = %d, want 404", path, res.StatusCode)
		}
	}
}

func dial(t *testing.T, srv *httptest.Server) (*websocket.Conn, context.Context) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	t.Cleanup(cancel)
	conn, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http")+"/ws", nil)
	if err != nil {
		t.Fatalf("dial /ws: %v", err)
	}
	t.Cleanup(func() { _ = conn.CloseNow() })
	return conn, ctx
}

func TestWebsocketAnswersNotImplemented(t *testing.T) {
	srv := newServer(t, nil)
	conn, ctx := dial(t, srv)

	for _, msg := range []string{`{"t":"HELLO"}`, `anything`} {
		if err := conn.Write(ctx, websocket.MessageText, []byte(msg)); err != nil {
			t.Fatalf("write: %v", err)
		}
		typ, data, err := conn.Read(ctx)
		if err != nil {
			t.Fatalf("read: %v", err)
		}
		if typ != websocket.MessageText || string(data) != string(NotImplementedReply) {
			t.Fatalf("reply = %v %q, want text %q", typ, data, NotImplementedReply)
		}
	}
	if err := conn.Close(websocket.StatusNormalClosure, ""); err != nil {
		t.Fatalf("close: %v", err)
	}
}

func TestWebsocketRejectsOversizedMessage(t *testing.T) {
	srv := newServer(t, nil)
	conn, ctx := dial(t, srv)

	if err := conn.Write(ctx, websocket.MessageText, make([]byte, MaxMessageBytes+1)); err != nil {
		t.Fatalf("write: %v", err)
	}
	_, _, err := conn.Read(ctx)
	if got := websocket.CloseStatus(err); got != websocket.StatusMessageTooBig {
		t.Fatalf("close status = %v (err %v), want StatusMessageTooBig", got, err)
	}
}

func TestShutdownClosesWebsockets(t *testing.T) {
	shutdown, stop := context.WithCancel(context.Background())
	srv := newServer(t, shutdown)
	conn, ctx := dial(t, srv)

	stop()
	_, _, err := conn.Read(ctx)
	if got := websocket.CloseStatus(err); got != websocket.StatusGoingAway {
		t.Fatalf("close status = %v (err %v), want StatusGoingAway", got, err)
	}
}
