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

// Package httpapi serves the server's HTTP surface: GET /healthz and the
// GET /ws websocket endpoint. Everything else is 404.
//
// SRV-00 has no game protocol yet (PRO-01 / SRV-01): /ws accepts the
// connection and answers every message with {"t":"NOT_IMPLEMENTED"}, so a
// client can test connectivity end to end.
package httpapi

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"sync/atomic"

	"github.com/coder/websocket"
)

// MaxMessageBytes caps one incoming websocket message (architecture §4.2
// limits). Larger messages close the connection with StatusMessageTooBig.
const MaxMessageBytes = 64 << 10

// NotImplementedReply is the answer to every /ws message until PRO-01.
var NotImplementedReply = []byte(`{"t":"NOT_IMPLEMENTED"}`)

// Options configures the handler.
type Options struct {
	Version string
	Logger  *slog.Logger
	// Shutdown, when done, closes every open websocket with StatusGoingAway.
	// http.Server.Shutdown does not close hijacked connections by itself.
	Shutdown context.Context
}

type api struct {
	opts   Options
	nextID atomic.Uint64
}

// New returns the HTTP handler.
func New(opts Options) http.Handler {
	if opts.Logger == nil {
		opts.Logger = slog.Default()
	}
	if opts.Shutdown == nil {
		opts.Shutdown = context.Background()
	}
	a := &api{opts: opts}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", a.healthz)
	mux.HandleFunc("GET /ws", a.ws)
	return mux
}

func (a *api) healthz(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(map[string]string{"status": "ok", "version": a.opts.Version})
}

func (a *api) ws(w http.ResponseWriter, r *http.Request) {
	// Game clients are not browsers and send no Origin header; Accept only
	// enforces same-origin when one is present.
	conn, err := websocket.Accept(w, r, nil)
	if err != nil {
		a.opts.Logger.Debug("websocket upgrade failed", "remote", r.RemoteAddr, "err", err)
		return
	}
	id := a.nextID.Add(1)
	log := a.opts.Logger.With("conn", id, "remote", r.RemoteAddr)
	log.Info("websocket connected")

	conn.SetReadLimit(MaxMessageBytes)
	stop := context.AfterFunc(a.opts.Shutdown, func() {
		_ = conn.Close(websocket.StatusGoingAway, "server shutting down")
	})
	defer stop()

	ctx := context.Background()
	for {
		typ, data, err := conn.Read(ctx)
		if err != nil {
			// CloseStatus is -1 when the peer vanished without a close frame.
			if status := websocket.CloseStatus(err); status != -1 {
				log.Info("websocket closed", "status", status)
			} else {
				log.Info("websocket closed", "err", err)
			}
			_ = conn.CloseNow()
			return
		}
		log.Debug("message received", "type", typ.String(), "bytes", len(data))
		if err := conn.Write(ctx, websocket.MessageText, NotImplementedReply); err != nil {
			log.Info("websocket write failed", "err", err)
			_ = conn.CloseNow()
			return
		}
	}
}
