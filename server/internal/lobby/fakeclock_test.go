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
	"sync"
	"testing"
	"time"

	"github.com/omarmaarouf18/spd-coop/server/internal/protocol"
)

// fakeClock is a manual Clock: time only moves in Advance, which runs the
// due timers in order on the calling goroutine (so when it returns, the
// room actor has handled them and queued their frames).
type fakeClock struct {
	mu     sync.Mutex
	now    time.Time
	timers []*fakeTimer
}

type fakeTimer struct {
	c       *fakeClock
	at      time.Time
	f       func()
	stopped bool
}

func newFakeClock() *fakeClock {
	return &fakeClock{now: time.Date(2026, 10, 18, 12, 0, 0, 0, time.UTC)}
}

func (c *fakeClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.now
}

func (c *fakeClock) ms() int64 { return c.Now().UnixMilli() }

func (c *fakeClock) AfterFunc(d time.Duration, f func()) Timer {
	c.mu.Lock()
	defer c.mu.Unlock()
	t := &fakeTimer{c: c, at: c.now.Add(d), f: f}
	if d <= 0 {
		// Due now: run it like a real timer would, never on the caller (the
		// caller is the room actor, and f goes back through room.do).
		t.stopped = true
		go f()
		return t
	}
	c.timers = append(c.timers, t)
	return t
}

func (t *fakeTimer) Stop() bool {
	t.c.mu.Lock()
	defer t.c.mu.Unlock()
	was := !t.stopped
	t.stopped = true
	return was
}

// Advance moves time forward by d, firing every timer due on the way.
func (c *fakeClock) Advance(d time.Duration) {
	c.mu.Lock()
	target := c.now.Add(d)
	c.mu.Unlock()
	for {
		c.mu.Lock()
		var next *fakeTimer
		live := c.timers[:0]
		for _, t := range c.timers {
			if t.stopped {
				continue
			}
			live = append(live, t)
			if !t.at.After(target) && (next == nil || t.at.Before(next.at)) {
				next = t
			}
		}
		c.timers = live
		if next == nil {
			c.now = target
			c.mu.Unlock()
			return
		}
		if next.at.After(c.now) {
			c.now = next.at
		}
		next.stopped = true
		c.mu.Unlock()
		next.f()
	}
}

// ---- game test helpers -------------------------------------------------------

func fl(depth int) protocol.Envelope {
	return protocol.Envelope{Depth: protocol.Int(depth), Branch: protocol.Int(0)}
}

// newGameServer is a test server on a fake clock with the given rules (zero
// = defaults).
func newGameServer(t *testing.T, dbPath string, fc *fakeClock, rules Rules) *testServer {
	t.Helper()
	return newTestServer(t, dbPath, func(o *Options) {
		o.Clock = fc
		o.Rules = rules
	})
}

// moveTo reports c on depth (a GHOST) and waits for the relay at the ally.
func moveTo(c, ally *client, depth int) {
	c.t.Helper()
	c.send(fl(depth), &protocol.Ghost{Pos: 10, Anim: "idle", HPPct: 100, HeroClass: "WARRIOR", Tier: 0})
	ally.expect(protocol.TGhost)
}

// collect sends a PING and returns every message received before its PONG:
// all that the server queued for c up to now.
func (c *client) collect() []protocol.Message {
	c.t.Helper()
	c.send(protocol.Envelope{}, &protocol.Ping{T: 1})
	var out []protocol.Message
	for i := 0; i < 50; i++ {
		m := c.recv()
		if m.T == protocol.TPong {
			return out
		}
		out = append(out, m)
	}
	c.t.Fatal("no PONG within 50 messages")
	return nil
}

// types lists the message types of ms.
func types(ms []protocol.Message) []string {
	var out []string
	for _, m := range ms {
		out = append(out, m.T)
	}
	return out
}

// quiet requires that nothing but the listed types is pending for c.
func (c *client) quiet(allowed ...string) []protocol.Message {
	c.t.Helper()
	ms := c.collect()
	for _, m := range ms {
		ok := false
		for _, a := range allowed {
			ok = ok || m.T == a
		}
		if !ok {
			c.t.Fatalf("unexpected %s %+v (pending: %v)", m.T, m.Payload, types(ms))
		}
	}
	return ms
}
