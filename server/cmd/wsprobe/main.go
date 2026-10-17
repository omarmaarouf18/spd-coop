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

// Command wsprobe is a connectivity probe for the server's /ws endpoint:
// it connects, sends a protocol v1 HELLO, prints the reply and exits 0 if it
// is a WELCOME (cmd/lobbyprobe exercises the whole lobby flow).
//
//	go run ./cmd/wsprobe -url ws://localhost:8080/ws
//	go run ./cmd/wsprobe -url wss://<domain>/ws
//	go run ./cmd/wsprobe -url wss://localhost/ws -insecure   (local Caddy CA)
package main

import (
	"context"
	"crypto/tls"
	"flag"
	"fmt"
	"net/http"
	"os"
	"time"

	"github.com/coder/websocket"

	"github.com/omarmaarouf18/spd-coop/server/internal/protocol"
)

func main() {
	url := flag.String("url", "ws://localhost:8080/ws", "websocket URL to probe")
	timeout := flag.Duration("timeout", 10*time.Second, "overall timeout")
	insecure := flag.Bool("insecure", false, "skip TLS certificate verification (local testing only)")
	gameVersion := flag.Int("game-version", 922, "gameVersionCode sent in HELLO")
	flag.Parse()

	if err := probe(*url, *timeout, *insecure, *gameVersion); err != nil {
		fmt.Fprintf(os.Stderr, "wsprobe: %v\n", err)
		os.Exit(1)
	}
}

func probe(url string, timeout time.Duration, insecure bool, gameVersion int) error {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	var opts *websocket.DialOptions
	if insecure {
		opts = &websocket.DialOptions{HTTPClient: &http.Client{Transport: &http.Transport{
			TLSClientConfig: &tls.Config{InsecureSkipVerify: true}, //nolint:gosec // opt-in flag for local testing
		}}}
	}
	conn, _, err := websocket.Dial(ctx, url, opts)
	if err != nil {
		return fmt.Errorf("dial %s: %w", url, err)
	}
	defer conn.CloseNow()
	fmt.Printf("connected to %s\n", url)

	msg, err := protocol.Encode(protocol.C2S, protocol.Envelope{Seq: 1},
		&protocol.Hello{Protocol: protocol.Version, GameVersionCode: gameVersion, Platform: "desktop"})
	if err != nil {
		return err
	}
	if err := conn.Write(ctx, websocket.MessageText, msg); err != nil {
		return fmt.Errorf("write: %w", err)
	}
	fmt.Printf("sent:     %s\n", msg)

	_, data, err := conn.Read(ctx)
	if err != nil {
		return fmt.Errorf("read: %w", err)
	}
	fmt.Printf("received: %s\n", data)
	_ = conn.Close(websocket.StatusNormalClosure, "")

	env, _, err := protocol.DecodeS2C(data)
	if err != nil {
		return fmt.Errorf("reply is not valid protocol v1: %w", err)
	}
	if env.T != protocol.TWelcome {
		return fmt.Errorf("expected WELCOME, got %s", env.T)
	}
	return nil
}
