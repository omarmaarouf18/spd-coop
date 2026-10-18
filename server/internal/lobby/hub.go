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

// Package lobby is the protocol v1 server (docs/coop/protocol/PROTOCOL.md):
// handshake and resume, rooms, run start, presence relay, heartbeat and
// rate limits.
//
// Concurrency (architecture §4.2):
//   - each websocket has a reader goroutine (conn.serve) and a writer
//     goroutine (conn.writeLoop) fed by a buffered channel;
//   - each room is an actor: one goroutine owns all of its state and runs
//     the closures sent to it (room.do). There are no locks around room
//     state, and the actor never blocks on the network;
//   - replies keep the request order of their connection: the reader
//     handles one frame at a time and waits for the actor (room.do), and
//     every reply to a game request is queued inside that same closure on
//     the session's single FIFO writer queue. Nothing replies from another
//     goroutine, so CLAIM → DENY/CLAIMED then CLAIMS_REQ → CLAIMS arrive in
//     that order (ITM-01 relies on it). Timer events (clock expiry) may
//     interleave between two replies, never reorder them;
//   - the Hub's maps (playerId → session, code → room, runId → room) and each
//     session's seq counter / current connection are small, separately
//     locked registries.
package lobby

import (
	"context"
	"log/slog"
	"net/http"
	"slices"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"

	"github.com/omarmaarouf18/spd-coop/server/internal/protocol"
	"github.com/omarmaarouf18/spd-coop/server/internal/store"
)

// Options configures a Hub. Zero durations and counts take the defaults
// of PROTOCOL.md (tests shorten them).
type Options struct {
	Store           *store.Store
	Logger          *slog.Logger
	AllowedVersions []int // gameVersionCodes; empty = no gate
	// Epoch identifies this server process (PROTOCOL.md §2.1); random if empty.
	Epoch string
	// Shutdown, when done, closes every connection with status 1001.
	Shutdown context.Context
	// Now is the time source of the connection layer (rate limits).
	Now func() time.Time
	// Clock drives the game engine (missions, Downed windows, pause grace,
	// twin windows) and every server timestamp; defaults to Now.
	Clock Clock
	// Rules are the co-op gameplay settings (COOP_* env vars).
	Rules Rules

	HelloTimeout     time.Duration // default 10 s
	HeartbeatTimeout time.Duration // default 30 s
	EmptyRoomTTL     time.Duration // default 10 min
	RateLimit        int           // messages per second, default 30
	RateBurst        int           // default 60
	AbuseWindow      time.Duration // default 5 s
	EventLogSize     int           // reliable events kept per room, default 256
	OutboundBuffer   int           // queued frames per connection, default 256
}

func (o *Options) defaults() {
	if o.Logger == nil {
		o.Logger = slog.Default()
	}
	if o.Epoch == "" {
		o.Epoch = newHexID()
	}
	if o.Shutdown == nil {
		o.Shutdown = context.Background()
	}
	if o.Now == nil {
		o.Now = time.Now
	}
	if o.Clock == nil {
		o.Clock = realClock{now: o.Now}
	}
	o.Rules.defaults()
	setDur := func(d *time.Duration, v time.Duration) {
		if *d == 0 {
			*d = v
		}
	}
	setDur(&o.HelloTimeout, 10*time.Second)
	setDur(&o.HeartbeatTimeout, 30*time.Second)
	setDur(&o.EmptyRoomTTL, 10*time.Minute)
	setDur(&o.AbuseWindow, 5*time.Second)
	setInt := func(n *int, v int) {
		if *n == 0 {
			*n = v
		}
	}
	setInt(&o.RateLimit, 30)
	setInt(&o.RateBurst, 60)
	setInt(&o.EventLogSize, 256)
	setInt(&o.OutboundBuffer, 256)
}

// Hub accepts websockets and owns the registries.
type Hub struct {
	opts   Options
	log    *slog.Logger
	nextID atomic.Uint64

	mu       sync.Mutex
	sessions map[string]*session // playerId → session
	codes    map[string]*room    // live room code → room
	runs     map[string]*room    // runId → room
}

// New returns a Hub. opts.Store is required.
func New(opts Options) *Hub {
	opts.defaults()
	return &Hub{
		opts:     opts,
		log:      opts.Logger,
		sessions: map[string]*session{},
		codes:    map[string]*room{},
		runs:     map[string]*room{},
	}
}

// Epoch returns this server process's epoch.
func (h *Hub) Epoch() string { return h.opts.Epoch }

func (h *Hub) nowMs() int64 { return h.opts.Clock.Now().UnixMilli() }

// ServeHTTP upgrades GET /ws and serves the connection until it closes.
func (h *Hub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	// Game clients are not browsers and send no Origin header; Accept only
	// enforces same-origin when one is present.
	ws, err := websocket.Accept(w, r, nil)
	if err != nil {
		h.log.Debug("websocket upgrade failed", "remote", r.RemoteAddr, "err", err)
		return
	}
	ws.SetReadLimit(64 << 10) // PROTOCOL.md §1: larger frames close with 1009
	c := newConn(h, ws, r.RemoteAddr)
	c.serve()
}

// session returns the session of playerID, creating an empty one.
// seqStart is the first s2c seq minus one for a new session (§2.1).
func (h *Hub) session(playerID string, seqStart int64) *session {
	h.mu.Lock()
	defer h.mu.Unlock()
	s, ok := h.sessions[playerID]
	if !ok {
		s = &session{hub: h, id: playerID, seq: seqStart}
		h.sessions[playerID] = s
	}
	return s
}

// newRoom creates a lobby room with a code unique among live rooms and
// starts its actor.
func (h *Hub) newRoom() *room {
	h.mu.Lock()
	defer h.mu.Unlock()
	code := newRoomCode()
	for h.codes[code] != nil {
		code = newRoomCode()
	}
	r := newRoom(h, code)
	h.codes[code] = r
	r.start()
	return r
}

func (h *Hub) roomByCode(code string) *room {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.codes[code]
}

func (h *Hub) registerRun(runID string, r *room) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.runs[runID] = r
}

// removeRoom forgets a room that stopped.
func (h *Hub) removeRoom(r *room) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.codes[r.code] == r {
		delete(h.codes, r.code)
	}
	for id, rr := range h.runs {
		if rr == r {
			delete(h.runs, id)
		}
	}
}

// runRoom returns the live room of a persisted run, rebuilding it from the
// database record after a restart (both players offline until they resume).
func (h *Hub) runRoom(rec store.Run) *room {
	h.mu.Lock()
	defer h.mu.Unlock()
	if r := h.runs[rec.RunID]; r != nil {
		return r
	}
	code := rec.Code
	if h.codes[code] != nil { // a live lobby took the code meanwhile
		code = newRoomCode()
		for h.codes[code] != nil {
			code = newRoomCode()
		}
	}
	r := newRoom(h, code)
	slots := make([]*slot, len(rec.Players))
	for i, p := range rec.Players {
		s, ok := h.sessions[p.PlayerID]
		if !ok {
			s = &session{hub: h, id: p.PlayerID}
			h.sessions[p.PlayerID] = s
		}
		slots[i] = &slot{sess: s, platform: p.Platform, heroClass: p.HeroClass, ready: true}
	}
	run := &protocol.RunInfo{RunID: rec.RunID, Seed: rec.Seed, RoleSeed: rec.RoleSeed}
	for _, p := range rec.Players {
		run.Players = append(run.Players, protocol.RunPlayer{PlayerID: p.PlayerID, Slot: p.Slot, HeroClass: p.HeroClass, Platform: p.Platform})
	}
	r.restoreRunning(slots, run)
	h.codes[code] = r
	h.runs[rec.RunID] = r
	return r
}

func (h *Hub) versionAllowed(code int) bool {
	return len(h.opts.AllowedVersions) == 0 || slices.Contains(h.opts.AllowedVersions, code)
}

func (h *Hub) minVersion() int { return slices.Min(h.opts.AllowedVersions) }

// session is one player across connections: the s2c seq counter within the
// epoch, the current connection, and the room the player is in. The room
// actor sets room/phase; the connection reads them for the state machine.
type session struct {
	hub *Hub
	id  string

	mu    sync.Mutex
	seq   int64
	c     *conn
	room  *room
	phase string // room phase as last set by the room actor
}

// attach makes c the session's connection and returns the previous one.
func (s *session) attach(c *conn) *conn {
	s.mu.Lock()
	defer s.mu.Unlock()
	old := s.c
	s.c = c
	return old
}

// detach clears the connection if it is still c, and reports whether it was.
func (s *session) detach(c *conn) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.c != c {
		return false
	}
	s.c = nil
	return true
}

func (s *session) conn() *conn {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.c
}

func (s *session) setRoom(r *room, phase string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.room, s.phase = r, phase
}

func (s *session) roomState() (*room, string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.room, s.phase
}

// send assigns the next s2c seq and queues the frame on the current
// connection (if any). It returns the seq and the frame, which the room
// logs for reliable types. bestEffort frames are dropped when the queue is
// full; other frames close the connection instead, so the client resumes
// and gets them replayed.
func (s *session) send(env protocol.Envelope, p protocol.Payload, bestEffort bool) (int64, []byte) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.seq++
	env.Seq = s.seq
	frame, err := protocol.Encode(protocol.S2C, env, p)
	if err != nil {
		s.hub.log.Error("refusing to send an invalid message", "player", s.id, "type", p.Type(), "err", err)
		return s.seq, nil
	}
	if s.c != nil {
		s.c.enqueue(frame, bestEffort)
	}
	return s.seq, frame
}
