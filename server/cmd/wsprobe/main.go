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
// it connects, sends one message, prints the reply and exits 0 if the reply
// is {"t":"NOT_IMPLEMENTED"} (the SRV-00 placeholder).
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
)

func main() {
	url := flag.String("url", "ws://localhost:8080/ws", "websocket URL to probe")
	timeout := flag.Duration("timeout", 10*time.Second, "overall timeout")
	insecure := flag.Bool("insecure", false, "skip TLS certificate verification (local testing only)")
	flag.Parse()

	if err := probe(*url, *timeout, *insecure); err != nil {
		fmt.Fprintf(os.Stderr, "wsprobe: %v\n", err)
		os.Exit(1)
	}
}

func probe(url string, timeout time.Duration, insecure bool) error {
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

	msg := `{"t":"PROBE"}`
	if err := conn.Write(ctx, websocket.MessageText, []byte(msg)); err != nil {
		return fmt.Errorf("write: %w", err)
	}
	fmt.Printf("sent:     %s\n", msg)

	_, data, err := conn.Read(ctx)
	if err != nil {
		return fmt.Errorf("read: %w", err)
	}
	fmt.Printf("received: %s\n", data)
	_ = conn.Close(websocket.StatusNormalClosure, "")

	if string(data) != `{"t":"NOT_IMPLEMENTED"}` {
		return fmt.Errorf("unexpected reply %q", data)
	}
	return nil
}
