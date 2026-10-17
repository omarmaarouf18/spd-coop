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
	"encoding/json"
	"errors"
	"log/slog"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"

	"github.com/omarmaarouf18/spd-coop/server/internal/protocol"
	"github.com/omarmaarouf18/spd-coop/server/internal/store"
)

// outFrame is one item of the writer queue: a frame, or a close request
// that is executed after the frames queued before it.
type outFrame struct {
	frame  []byte
	close  bool
	status websocket.StatusCode
	reason string
}

type conn struct {
	hub *Hub
	ws  *websocket.Conn
	id  uint64
	log *slog.Logger

	out      chan outFrame
	done     chan struct{}
	finished sync.Once
	closing  atomic.Bool

	watchdog *time.Timer // hello timeout, then heartbeat timeout
	helloed  atomic.Bool

	// Reader-goroutine-only state.
	lastSeq  int64
	preSeq   int64 // s2c numbering before WELCOME (no session yet)
	sess     *session
	platform string
	limit    *limiter
}

func newConn(h *Hub, ws *websocket.Conn, remote string) *conn {
	id := h.nextID.Add(1)
	return &conn{
		hub:   h,
		ws:    ws,
		id:    id,
		log:   h.log.With("conn", id, "remote", remote),
		out:   make(chan outFrame, h.opts.OutboundBuffer),
		done:  make(chan struct{}),
		limit: newLimiter(h.opts.RateLimit, h.opts.RateBurst, h.opts.AbuseWindow, h.opts.Now()),
	}
}

// serve runs the reader loop until the connection ends.
func (c *conn) serve() {
	c.log.Info("websocket connected")
	go c.writeLoop()
	c.watchdog = time.AfterFunc(c.hub.opts.HelloTimeout, func() {
		if c.helloed.Load() {
			c.log.Info("closing: heartbeat timeout")
			c.closeWith(protocol.CloseTimeout, "heartbeat timeout")
		} else {
			c.log.Info("closing: no HELLO in time")
			c.closeWith(protocol.CloseTimeout, "no HELLO in time")
		}
	})
	stopShutdown := context.AfterFunc(c.hub.opts.Shutdown, func() {
		c.closeWith(websocket.StatusGoingAway, "server shutting down")
	})
	defer stopShutdown()
	defer c.finish()

	for {
		typ, data, err := c.ws.Read(context.Background())
		if err != nil {
			if status := websocket.CloseStatus(err); status != -1 {
				c.log.Info("websocket closed", "status", status)
			} else {
				c.log.Info("websocket closed", "err", err)
			}
			return
		}
		if c.closing.Load() {
			continue // draining until the close handshake ends
		}
		if c.helloed.Load() {
			c.watchdog.Reset(c.hub.opts.HeartbeatTimeout)
		}
		now := c.hub.opts.Now()
		switch c.limit.take(now) {
		case drop:
			if c.limit.shouldReport(now) {
				c.sendError(0, protocol.CodeRateLimited, "too many messages, slow down")
			}
			continue
		case dropAbuse:
			c.log.Warn("closing: sustained rate-limit abuse")
			c.closeWith(websocket.StatusPolicyViolation, "rate limit")
			continue
		}
		if typ != websocket.MessageText {
			c.sendError(0, protocol.CodeBadMessage, "binary frames are not allowed")
			continue
		}
		c.handle(data)
	}
}

func (c *conn) handle(data []byte) {
	env, p, err := protocol.DecodeC2S(data)
	if err != nil {
		var de *protocol.DecodeError
		if !errors.As(err, &de) {
			de = &protocol.DecodeError{Kind: protocol.KindNotJSON, Msg: err.Error()}
		}
		switch {
		case de.Kind == protocol.KindUnsupportedVersion:
			c.reject(0, protocol.CodeVersionMismatch, de.Error(), nil)
		case !c.helloed.Load() && peekType(data) == protocol.THello:
			c.reject(0, protocol.CodeBadMessage, de.Error(), nil)
		default:
			c.sendError(0, de.Code(), de.Error())
		}
		return
	}
	if env.Seq <= c.lastSeq {
		c.sendError(env.Seq, protocol.CodeBadMessage, "seq must increase on each message")
		return
	}
	c.lastSeq = env.Seq

	if !c.helloed.Load() {
		if h, ok := p.(*protocol.Hello); ok {
			c.hello(env, h)
		} else {
			c.sendError(env.Seq, protocol.CodeInvalidState, "send HELLO first")
		}
		return
	}

	r, phase := c.sess.roomState()
	switch p := p.(type) {
	case *protocol.Hello:
		c.sendError(env.Seq, protocol.CodeInvalidState, "already said HELLO")
	case *protocol.Ping:
		c.sess.send(protocol.Envelope{Ack: protocol.Int64(env.Seq)}, &protocol.Pong{T: p.T, ServerTime: c.hub.nowMs()}, true)
	case *protocol.RoomCreate:
		if r != nil {
			c.sendError(env.Seq, protocol.CodeAlreadyInRoom, "already in a room")
			return
		}
		room := c.hub.newRoom()
		c.log.Info("room created", "code", room.code, "player", c.sess.id)
		c.result(env.Seq, room.join(c.sess, c.platform))
	case *protocol.RoomJoin:
		if r != nil {
			c.sendError(env.Seq, protocol.CodeAlreadyInRoom, "already in a room")
			return
		}
		room := c.hub.roomByCode(p.Code)
		if room == nil {
			c.sendError(env.Seq, protocol.CodeRoomNotFound, "no room with that code")
			return
		}
		c.result(env.Seq, room.join(c.sess, c.platform))
	case *protocol.RoomLeave:
		switch {
		case r == nil:
			c.sendError(env.Seq, protocol.CodeNotInRoom, "not in a room")
		case phase == protocol.PhaseRunning:
			c.sendError(env.Seq, protocol.CodeInvalidState, "a running run cannot be left in v1")
		default:
			c.result(env.Seq, r.leave(c.sess))
		}
	case *protocol.Ready:
		switch {
		case r == nil:
			c.sendError(env.Seq, protocol.CodeNotInRoom, "not in a room")
		case phase == protocol.PhaseRunning:
			c.sendError(env.Seq, protocol.CodeInvalidState, "the run has already started")
		default:
			c.result(env.Seq, r.ready(c.sess, p.HeroClass, p.Ready))
		}
	case *protocol.Ghost, *protocol.Mark, *protocol.Quick:
		switch {
		case r == nil:
			c.sendError(env.Seq, protocol.CodeNotInRoom, "not in a room")
		case phase != protocol.PhaseRunning:
			c.sendError(env.Seq, protocol.CodeInvalidState, "presence is only relayed in a running room")
		default:
			c.result(env.Seq, r.relay(c.sess, env, p))
		}
	default:
		c.sendError(env.Seq, protocol.CodeUnknownType, "not a c2s type")
	}
}

// result sends the ERROR for a room command's error code ("" = success).
func (c *conn) result(seq int64, code string) {
	if code != "" {
		c.sendError(seq, code, errorMsg(code))
	}
}

func errorMsg(code string) string {
	switch code {
	case protocol.CodeRoomFull:
		return "the room already has two players"
	case protocol.CodeRoomNotFound:
		return "no room with that code"
	case protocol.CodeNotInRoom:
		return "not in a room"
	case protocol.CodeInvalidState:
		return "not allowed in the current state"
	default:
		return code
	}
}

// hello handles the handshake: version gate, new player or resume.
func (c *conn) hello(env protocol.Envelope, h *protocol.Hello) {
	if h.Protocol != protocol.Version {
		c.reject(env.Seq, protocol.CodeVersionMismatch, "unsupported protocol version", c.minVersion())
		return
	}
	if !c.hub.versionAllowed(h.GameVersionCode) {
		c.reject(env.Seq, protocol.CodeVersionMismatch, "this game version is not allowed, update the game", c.minVersion())
		return
	}
	c.platform = h.Platform
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	st := c.hub.opts.Store
	now := c.hub.nowMs()

	if h.ResumeToken == "" {
		id := newHexID()
		tok, hash := newToken()
		if err := st.CreatePlayer(ctx, id, hash, now); err != nil {
			c.log.Error("creating a player failed", "err", err)
			c.closeWith(websocket.StatusInternalError, "internal error")
			return
		}
		sess := c.hub.session(id, c.preSeq)
		c.start(sess)
		c.log.Info("hello", "player", id, "resumed", false, "platform", h.Platform, "gameVersionCode", h.GameVersionCode)
		c.sendWelcome(sess, protocol.Welcome{PlayerID: id, ResumeToken: tok, Epoch: c.hub.Epoch(),
			ServerTime: now, Complete: true}, env.Seq, nil)
		return
	}

	id, err := st.PlayerByTokenHash(ctx, hashToken(h.ResumeToken))
	if errors.Is(err, store.ErrNotFound) {
		c.reject(env.Seq, protocol.CodeResumeFailed, "unknown resume token, start with a HELLO without a token", nil)
		return
	}
	if err != nil {
		c.log.Error("looking up a resume token failed", "err", err)
		c.closeWith(websocket.StatusInternalError, "internal error")
		return
	}
	_ = st.TouchPlayer(ctx, id, now)
	sess := c.hub.session(id, 0)
	c.start(sess)
	var ack int64
	if env.Ack != nil {
		ack = *env.Ack
	}
	sameEpoch := h.Epoch == c.hub.Epoch()
	welcome := protocol.Welcome{PlayerID: id, ResumeToken: h.ResumeToken, Epoch: c.hub.Epoch(),
		ServerTime: now, Resumed: true, Complete: sameEpoch}
	c.log.Info("hello", "player", id, "resumed", true, "sameEpoch", sameEpoch, "ack", ack, "platform", h.Platform)

	r, _ := sess.roomState()
	if r == nil {
		// Not in a live room: maybe in a persisted run (after a restart).
		if rec, err := st.ActiveRunForPlayer(ctx, id); err == nil {
			r = c.hub.runRoom(rec)
		} else if !errors.Is(err, store.ErrNotFound) {
			c.log.Error("looking up the player's run failed", "player", id, "err", err)
		}
	}
	if r == nil || !r.resume(sess, c, welcome, env.Seq, ack, sameEpoch) {
		c.sendWelcome(sess, welcome, env.Seq, nil)
	}
}

// start binds the connection to its session; a previous connection of the
// same player is told SESSION_REPLACED and closed (PROTOCOL.md §7).
func (c *conn) start(sess *session) {
	c.sess = sess
	c.helloed.Store(true)
	c.watchdog.Reset(c.hub.opts.HeartbeatTimeout)
	if old := sess.conn(); old != nil && old != c {
		// Still the session's connection: this ERROR goes to the old one.
		old.log.Info("session replaced by a newer connection", "player", sess.id)
		sess.send(protocol.Envelope{}, &protocol.Error{Code: protocol.CodeSessionReplaced, Msg: "resumed on another connection"}, false)
		old.closeWith(protocol.CloseSessionReplaced, "session replaced")
	}
	sess.attach(c)
}

// sendWelcome sends WELCOME (new seq) and then the replayed frames with
// their original seq.
func (c *conn) sendWelcome(sess *session, w protocol.Welcome, helloSeq int64, replay [][]byte) {
	sess.send(protocol.Envelope{Ack: protocol.Int64(helloSeq)}, &w, false)
	for _, f := range replay {
		c.enqueue(f, false)
	}
}

func (c *conn) minVersion() *int {
	if len(c.hub.opts.AllowedVersions) == 0 {
		return nil
	}
	m := c.hub.minVersion()
	return &m
}

// sendError answers on this connection: through the session after HELLO,
// with the connection's own numbering before it.
func (c *conn) sendError(refSeq int64, code, msg string) {
	e := &protocol.Error{Code: code, Msg: truncate(msg, 256)}
	var env protocol.Envelope
	if refSeq > 0 {
		e.RefSeq = protocol.Int64(refSeq)
		env.Ack = protocol.Int64(refSeq)
	}
	if c.sess != nil {
		c.sess.send(env, e, false)
		return
	}
	c.sendPre(env, e)
}

// reject sends REJECT and closes with 4001 (PROTOCOL.md §4.2).
func (c *conn) reject(refSeq int64, code, msg string, minVersion *int) {
	var env protocol.Envelope
	if refSeq > 0 {
		env.Ack = protocol.Int64(refSeq)
	}
	c.log.Info("rejected", "code", code)
	c.sendPre(env, &protocol.Reject{Code: code, Msg: truncate(msg, 256), MinGameVersionCode: minVersion})
	c.closeWith(protocol.CloseRejected, code)
}

func (c *conn) sendPre(env protocol.Envelope, p protocol.Payload) {
	c.preSeq++
	env.Seq = c.preSeq
	frame, err := protocol.Encode(protocol.S2C, env, p)
	if err != nil {
		c.log.Error("refusing to send an invalid message", "type", p.Type(), "err", err)
		return
	}
	c.enqueue(frame, false)
}

// enqueue queues a frame for the writer without blocking. A full queue
// drops best-effort frames; for anything else the connection is closed
// (the client resumes and gets reliable events replayed).
func (c *conn) enqueue(frame []byte, bestEffort bool) {
	select {
	case c.out <- outFrame{frame: frame}:
	case <-c.done:
	default:
		if !bestEffort {
			c.log.Warn("outbound queue full, closing the slow connection")
			_ = c.ws.CloseNow()
		}
	}
}

// closeWith queues a close after the frames already queued.
func (c *conn) closeWith(status websocket.StatusCode, reason string) {
	if !c.closing.CompareAndSwap(false, true) {
		return
	}
	select {
	case c.out <- outFrame{close: true, status: status, reason: reason}:
	case <-c.done:
	default:
		_ = c.ws.CloseNow()
	}
}

func (c *conn) writeLoop() {
	for {
		select {
		case <-c.done:
			return
		case m := <-c.out:
			if m.close {
				_ = c.ws.Close(m.status, m.reason)
				return
			}
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			err := c.ws.Write(ctx, websocket.MessageText, m.frame)
			cancel()
			if err != nil {
				_ = c.ws.CloseNow()
				return
			}
		}
	}
}

// finish runs once when the reader loop ends.
func (c *conn) finish() {
	c.finished.Do(func() {
		c.watchdog.Stop()
		close(c.done)
		_ = c.ws.CloseNow()
		if c.sess == nil {
			return
		}
		if c.sess.detach(c) {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			_ = c.hub.opts.Store.TouchPlayer(ctx, c.sess.id, c.hub.nowMs())
			cancel()
			if r, _ := c.sess.roomState(); r != nil {
				r.disconnected(c.sess)
			}
		}
		c.log.Info("player disconnected", "player", c.sess.id)
	})
}

func peekType(data []byte) string {
	var env struct {
		T string `json:"t"`
	}
	_ = json.Unmarshal(data, &env)
	return env.T
}

func truncate(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n])
}
