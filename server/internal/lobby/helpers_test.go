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

package lobby

import (
	"context"
	"io"
	"log/slog"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/omarmaarouf18/spd-coop/server/internal/protocol"
	"github.com/omarmaarouf18/spd-coop/server/internal/store"
)

// testServer is a Hub behind httptest, on its own SQLite file.
type testServer struct {
	t      *testing.T
	hub    *Hub
	srv    *httptest.Server
	url    string
	dbPath string
	stop   context.CancelFunc
}

func newTestServer(t *testing.T, dbPath string, tweak func(*Options)) *testServer {
	t.Helper()
	if dbPath == "" {
		dbPath = filepath.Join(t.TempDir(), "coop.db")
	}
	st, err := store.Open(context.Background(), dbPath)
	if err != nil {
		t.Fatal(err)
	}
	shutdown, stop := context.WithCancel(context.Background())
	opts := Options{Store: st, Logger: slog.New(slog.NewTextHandler(io.Discard, nil)), Shutdown: shutdown}
	if tweak != nil {
		tweak(&opts)
	}
	hub := New(opts)
	srv := httptest.NewServer(hub)
	ts := &testServer{t: t, hub: hub, srv: srv, url: "ws" + strings.TrimPrefix(srv.URL, "http"), dbPath: dbPath, stop: stop}
	t.Cleanup(func() { ts.close(); _ = st.Close() })
	return ts
}

// close simulates a server stop: every websocket is closed, the listener
// goes away. The database file stays.
func (ts *testServer) close() {
	ts.stop()
	ts.srv.CloseClientConnections()
	ts.srv.Close()
}

// client is a minimal protocol v1 client for tests.
type client struct {
	t     *testing.T
	ws    *websocket.Conn
	seq   int64
	ack   int64 // highest s2c seq seen
	ctx   context.Context
	token string
	epoch string
	id    string
}

func dial(t *testing.T, ts *testServer) *client {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	t.Cleanup(cancel)
	ws, _, err := websocket.Dial(ctx, ts.url, nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	t.Cleanup(func() { _ = ws.CloseNow() })
	return &client{t: t, ws: ws, ctx: ctx}
}

func (c *client) send(env protocol.Envelope, p protocol.Payload) {
	c.t.Helper()
	c.seq++
	env.Seq = c.seq
	frame, err := protocol.Encode(protocol.C2S, env, p)
	if err != nil {
		c.t.Fatalf("encode %s: %v", p.Type(), err)
	}
	c.raw(frame)
}

func (c *client) raw(frame []byte) {
	c.t.Helper()
	if err := c.ws.Write(c.ctx, websocket.MessageText, frame); err != nil {
		c.t.Fatalf("write: %v", err)
	}
}

// recv reads the next message, which must decode as valid s2c.
func (c *client) recv() protocol.Message {
	c.t.Helper()
	ctx, cancel := context.WithTimeout(c.ctx, 5*time.Second)
	defer cancel()
	_, data, err := c.ws.Read(ctx)
	if err != nil {
		c.t.Fatalf("read: %v", err)
	}
	m, err := protocol.Decode(protocol.S2C, data)
	if err != nil {
		c.t.Fatalf("server sent an invalid message %s: %v", data, err)
	}
	if m.Seq > c.ack {
		c.ack = m.Seq
	}
	return m
}

// expect reads until a message of type t arrives (skipping others) and
// returns it.
func (c *client) expect(t string) protocol.Message {
	c.t.Helper()
	for i := 0; i < 20; i++ {
		m := c.recv()
		if m.T == t {
			return m
		}
	}
	c.t.Fatalf("no %s within 20 messages", t)
	return protocol.Message{}
}

// next reads the next message and requires its type.
func (c *client) next(t string) protocol.Message {
	c.t.Helper()
	m := c.recv()
	if m.T != t {
		c.t.Fatalf("got %s %+v, want %s", m.T, m.Payload, t)
	}
	return m
}

func (c *client) expectError(code string) *protocol.Error {
	c.t.Helper()
	m := c.next(protocol.TError)
	e := m.Payload.(*protocol.Error)
	if e.Code != code {
		c.t.Fatalf("ERROR code = %s (%s), want %s", e.Code, e.Msg, code)
	}
	return e
}

// expectClose reads until the connection closes and returns its status.
func (c *client) expectClose() websocket.StatusCode {
	c.t.Helper()
	ctx, cancel := context.WithTimeout(c.ctx, 5*time.Second)
	defer cancel()
	for {
		_, _, err := c.ws.Read(ctx)
		if err != nil {
			return websocket.CloseStatus(err)
		}
	}
}

func (c *client) hello(platform string) *protocol.Welcome {
	c.t.Helper()
	c.send(protocol.Envelope{}, &protocol.Hello{Protocol: 1, GameVersionCode: 922, Platform: platform})
	w := c.next(protocol.TWelcome).Payload.(*protocol.Welcome)
	c.token, c.epoch, c.id = w.ResumeToken, w.Epoch, w.PlayerID
	return w
}

// resume opens a new connection for the same player (token, epoch, ack).
func (c *client) resume(ts *testServer) (*client, *protocol.Welcome) {
	c.t.Helper()
	n := dial(c.t, ts)
	n.token, n.epoch, n.id, n.ack = c.token, c.epoch, c.id, c.ack
	n.send(protocol.Envelope{Ack: protocol.Int64(c.ack)},
		&protocol.Hello{Protocol: 1, GameVersionCode: 922, Platform: "desktop", ResumeToken: c.token, Epoch: c.epoch})
	w := n.next(protocol.TWelcome).Payload.(*protocol.Welcome)
	n.epoch = w.Epoch
	return n, w
}

// pair connects A and B, creates a room with A, joins B, readies both and
// returns them after RUN_START.
func pair(t *testing.T, ts *testServer) (a, b *client, runA, runB *protocol.RunStart) {
	t.Helper()
	a, b = dial(t, ts), dial(t, ts)
	a.hello("desktop")
	b.hello("android")
	a.send(protocol.Envelope{}, &protocol.RoomCreate{})
	code := a.next(protocol.TRoomState).Payload.(*protocol.RoomState).Code
	b.send(protocol.Envelope{}, &protocol.RoomJoin{Code: code})
	b.next(protocol.TRoomState)
	a.next(protocol.TRoomState)
	a.send(protocol.Envelope{}, &protocol.Ready{HeroClass: "WARRIOR", Ready: true})
	a.next(protocol.TRoomState)
	b.next(protocol.TRoomState)
	b.send(protocol.Envelope{}, &protocol.Ready{HeroClass: "MAGE", Ready: true})
	runA = a.expect(protocol.TRunStart).Payload.(*protocol.RunStart)
	runB = b.expect(protocol.TRunStart).Payload.(*protocol.RunStart)
	a.next(protocol.TRoomState)
	b.next(protocol.TRoomState)
	return a, b, runA, runB
}
