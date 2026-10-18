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

// Command worldprobe checks the SRV-03 connected-worlds engine (PROTOCOL.md
// §5.11) end to end against a real server binary with two simulated
// clients. It builds and starts its own server (a fresh SQLite file in a
// temp dir), so it can also restart it. It exits 0 only if every scenario
// matched.
//
//	go run ./cmd/worldprobe                      # builds ./cmd/server, port 18093
//	go run ./cmd/worldprobe -server ./srv -port 18094
//
// Scenarios: a kill race (both report one mob at once → one MOB_DEAD per
// player, one killer); a late arrival (the ally reaches the floor later and
// gets MOB_KILLS + TERRAINS); an unlock and a kill persisting across a
// server restart (SIGTERM, then the same database).
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strconv"
	"sync"
	"syscall"
	"time"

	"github.com/coder/websocket"

	"github.com/omarmaarouf18/spd-coop/server/internal/protocol"
)

var (
	serverBin = flag.String("server", "", "server binary (default: go build ./cmd/server into a temp dir)")
	port      = flag.Int("port", 18093, "port for the probe's own server")
	timeout   = flag.Duration("timeout", 10*time.Second, "timeout per read")
	url       string
)

func main() {
	flag.Parse()
	url = fmt.Sprintf("ws://127.0.0.1:%d/ws", *port)
	dir, err := os.MkdirTemp("", "worldprobe-")
	if err != nil {
		fmt.Println("FAIL:", err)
		os.Exit(1)
	}
	defer os.RemoveAll(dir)
	srv := &server{dir: dir}
	if err := srv.build(); err != nil {
		fmt.Println("FAIL:", err)
		os.Exit(1)
	}
	if err := srv.start(); err != nil {
		fmt.Println("FAIL:", err)
		os.Exit(1)
	}
	defer srv.stop()

	scenarios := []struct {
		name string
		run  func(*server) error
	}{
		{"kill race", killRace},
		{"late arrival", lateArrival},
		{"unlock persists across a restart", restart},
	}
	failed := 0
	for _, s := range scenarios {
		fmt.Printf("== %s\n", s.name)
		if err := s.run(srv); err != nil {
			fmt.Printf("FAIL %s: %v\n", s.name, err)
			failed++
		}
	}
	if failed > 0 {
		fmt.Printf("FAIL: %d of %d scenarios failed\n", failed, len(scenarios))
		srv.stop()
		os.Exit(1)
	}
	fmt.Printf("PASS: all %d scenarios matched\n", len(scenarios))
}

func step(format string, args ...any) { fmt.Printf("ok   "+format+"\n", args...) }

// ---- the probe's own server ------------------------------------------------------

type server struct {
	dir string
	bin string
	cmd *exec.Cmd
}

func (s *server) build() error {
	if *serverBin != "" {
		s.bin = *serverBin
		return nil
	}
	s.bin = filepath.Join(s.dir, "server")
	out, err := exec.Command("go", "build", "-o", s.bin, "./cmd/server").CombinedOutput()
	if err != nil {
		return fmt.Errorf("go build ./cmd/server: %v\n%s", err, out)
	}
	return nil
}

func (s *server) start() error {
	cmd := exec.Command(s.bin)
	cmd.Env = append(os.Environ(), "PORT="+strconv.Itoa(*port), "DB_PATH="+filepath.Join(s.dir, "coop.db"), "LOG_LEVEL=warn")
	cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("start server: %w", err)
	}
	s.cmd = cmd
	health := fmt.Sprintf("http://127.0.0.1:%d/healthz", *port)
	for end := time.Now().Add(15 * time.Second); time.Now().Before(end); time.Sleep(100 * time.Millisecond) {
		if resp, err := http.Get(health); err == nil {
			resp.Body.Close()
			if resp.StatusCode == http.StatusOK {
				fmt.Printf("...  server up (pid %d, db %s)\n", cmd.Process.Pid, filepath.Join(s.dir, "coop.db"))
				return nil
			}
		}
	}
	return errors.New("server not healthy within 15 s")
}

func (s *server) stop() {
	if s.cmd == nil {
		return
	}
	_ = s.cmd.Process.Signal(syscall.SIGTERM)
	_ = s.cmd.Wait()
	s.cmd = nil
}

// ---- scenarios -----------------------------------------------------------------

func killRace(*server) error {
	a, b, err := pair()
	if err != nil {
		return err
	}
	defer closeAll(a, b)
	if err := moveBoth(a, b, 3); err != nil {
		return err
	}
	for i := 0; i < 5; i++ {
		id := "3:0:" + strconv.Itoa(i)
		var wg sync.WaitGroup
		errs := make([]error, 2)
		for k, p := range []*player{a, b} {
			wg.Add(1)
			go func() { defer wg.Done(); errs[k] = p.send(fl(3), &protocol.MobKilled{MobNetID: id}) }()
		}
		wg.Wait()
		if err := errors.Join(errs...); err != nil {
			return err
		}
		killer := ""
		for _, p := range []*player{a, b} {
			ms, err := p.collect()
			if err != nil {
				return err
			}
			var dead []*protocol.MobDead
			for _, m := range ms {
				switch pl := m.Payload.(type) {
				case *protocol.MobDead:
					dead = append(dead, pl)
				case *protocol.Deny:
					return fmt.Errorf("%s got DENY %s for a racing kill", p.name, pl.Reason)
				}
			}
			if len(dead) != 1 || dead[0].MobNetID != id || (killer != "" && dead[0].By != killer) {
				return fmt.Errorf("%s MOB_DEAD for %s = %+v (killer %q)", p.name, id, dead, killer)
			}
			killer = dead[0].By
		}
		who := "A"
		if killer == b.id {
			who = "B"
		}
		step("both report %s at once -> exactly one MOB_DEAD each, by=%s", id, who)
	}
	return nil
}

func lateArrival(*server) error {
	a, b, err := pair()
	if err != nil {
		return err
	}
	defer closeAll(a, b)
	if err := move(a, b, 4); err != nil {
		return err
	}
	if err := move(b, a, 1); err != nil {
		return err
	}
	if err := a.send(fl(4), &protocol.MobKilled{MobNetID: "4:0:2"}); err != nil {
		return err
	}
	if _, err := a.expect(protocol.TMobDead); err != nil {
		return err
	}
	if _, err := b.expect(protocol.TMobDead); err != nil {
		return err
	}
	step("A kills 4:0:2 on depth 4 while B is on depth 1 -> MOB_DEAD to both")
	yes := true
	for _, t := range []*protocol.Terrain{
		{Cell: 208, Tile: protocol.TileDoor, Persist: &yes},         // iron key
		{Cell: 431, Tile: protocol.TileInactiveTrap, Persist: &yes}, // trap consumed
		{Cell: 209, Tile: protocol.TileOpenDoor},                    // relay-only
	} {
		if err := a.send(fl(4), t); err != nil {
			return err
		}
	}
	if _, err := a.collect(); err != nil { // A's frames are handled before B looks
		return err
	}
	if ms, err := b.collect(); err != nil {
		return err
	} else if len(ms) != 0 {
		return fmt.Errorf("B (other floor) got %d messages, first %s", len(ms), ms[0].T)
	}
	step("A unlocks 208, consumes the trap at 431, opens 209 -> nothing relayed to B (other floor)")
	if err := move(b, a, 4); err != nil {
		return err
	}
	kills, err := b.mobKills(4)
	if err != nil {
		return err
	}
	if !reflect.DeepEqual(kills, []string{"4:0:2"}) {
		return fmt.Errorf("MOB_KILLS = %v", kills)
	}
	changes, err := b.terrains(4)
	if err != nil {
		return err
	}
	want := []protocol.TerrainChange{{Cell: 208, Tile: 5}, {Cell: 431, Tile: 19}}
	if !reflect.DeepEqual(changes, want) {
		return fmt.Errorf("TERRAINS = %+v, want %+v", changes, want)
	}
	step("B arrives on depth 4 -> MOB_KILLS %v, TERRAINS %+v", kills, changes)
	return nil
}

func restart(srv *server) error {
	a, b, err := pair()
	if err != nil {
		return err
	}
	if err := moveBoth(a, b, 5); err != nil {
		return err
	}
	yes := true
	if err := a.send(fl(5), &protocol.Terrain{Cell: 1022, Tile: protocol.TileUnlockedExit, Persist: &yes}); err != nil {
		return err
	}
	m, err := b.expect(protocol.TTerrain)
	if err != nil {
		return err
	}
	if t := m.Payload.(*protocol.Terrain); m.From != a.id || t.Tile != 22 || !t.Persistent() {
		return fmt.Errorf("relayed TERRAIN = %+v %+v", m.Envelope, t)
	}
	if err := b.send(fl(5), &protocol.MobKilled{MobNetID: "5:0:1"}); err != nil {
		return err
	}
	if _, err := a.expect(protocol.TMobDead); err != nil {
		return err
	}
	step("A unlocks the exit (1022 -> UNLOCKED_EXIT, persist) -> B gets it; B kills 5:0:1")

	srv.stop()
	closeAll(a, b)
	step("server stopped (SIGTERM)")
	if err := srv.start(); err != nil {
		return err
	}
	a2, b2 := &player{name: "A"}, &player{name: "B"}
	defer closeAll(a2, b2)
	for _, pp := range [][2]*player{{a, a2}, {b, b2}} {
		old, p := pp[0], pp[1]
		if err := p.connect(); err != nil {
			return err
		}
		w, err := p.hello(protocol.Envelope{Ack: protocol.Int64(old.ack)},
			&protocol.Hello{Protocol: 1, GameVersionCode: 922, Platform: "desktop", ResumeToken: old.token, Epoch: old.epoch})
		if err != nil {
			return err
		}
		if !w.Resumed || w.Run == nil || w.Complete {
			return fmt.Errorf("%s WELCOME after restart = resumed %v run %v complete %v", p.name, w.Resumed, w.Run != nil, w.Complete)
		}
	}
	step("both resumed into the run (new epoch, complete=false)")
	changes, err := b2.terrains(5)
	if err != nil {
		return err
	}
	if want := []protocol.TerrainChange{{Cell: 1022, Tile: 22}}; !reflect.DeepEqual(changes, want) {
		return fmt.Errorf("TERRAINS after restart = %+v, want %+v", changes, want)
	}
	kills, err := a2.mobKills(5)
	if err != nil {
		return err
	}
	if !reflect.DeepEqual(kills, []string{"5:0:1"}) {
		return fmt.Errorf("MOB_KILLS after restart = %v", kills)
	}
	step("after the restart: TERRAINS %+v, MOB_KILLS %v", changes, kills)
	if err := moveBoth(a2, b2, 5); err != nil {
		return err
	}
	if err := a2.send(fl(5), &protocol.MobKilled{MobNetID: "5:0:1"}); err != nil {
		return err
	}
	for _, p := range []*player{a2, b2} {
		ms, err := p.collect()
		if err != nil {
			return err
		}
		for _, m := range ms {
			if m.T == protocol.TMobDead || m.T == protocol.TDeny {
				return fmt.Errorf("%s got %s for an already killed mob", p.name, m.T)
			}
		}
	}
	step("A reports 5:0:1 again -> silently ignored (no MOB_DEAD, no DENY)")
	return nil
}

// ---- helpers -------------------------------------------------------------------

func fl(depth int) protocol.Envelope {
	return protocol.Envelope{Depth: protocol.Int(depth), Branch: protocol.Int(0)}
}

func closeAll(ps ...*player) {
	for _, p := range ps {
		if p.ws != nil {
			_ = p.ws.CloseNow()
		}
	}
}

// pair starts a fresh run for two new players.
func pair() (a, b *player, err error) {
	a, b = &player{name: "A"}, &player{name: "B"}
	for _, p := range []*player{a, b} {
		if err := p.connect(); err != nil {
			return nil, nil, err
		}
		if _, err := p.hello(protocol.Envelope{}, &protocol.Hello{Protocol: 1, GameVersionCode: 922, Platform: "desktop"}); err != nil {
			return nil, nil, err
		}
	}
	if err := a.send(protocol.Envelope{}, &protocol.RoomCreate{}); err != nil {
		return nil, nil, err
	}
	m, err := a.expect(protocol.TRoomState)
	if err != nil {
		return nil, nil, err
	}
	if err := b.send(protocol.Envelope{}, &protocol.RoomJoin{Code: m.Payload.(*protocol.RoomState).Code}); err != nil {
		return nil, nil, err
	}
	if _, err := b.expect(protocol.TRoomState); err != nil {
		return nil, nil, err
	}
	for _, p := range []*player{a, b} {
		if err := p.send(protocol.Envelope{}, &protocol.Ready{HeroClass: "WARRIOR", Ready: true}); err != nil {
			return nil, nil, err
		}
	}
	for _, p := range []*player{a, b} {
		if _, err := p.expect(protocol.TRunStart); err != nil {
			return nil, nil, err
		}
		if _, err := p.collect(); err != nil { // drain the lobby traffic
			return nil, nil, err
		}
	}
	return a, b, nil
}

// move reports p on depth with a GHOST and waits for the relay at ally.
func move(p, ally *player, depth int) error {
	if err := p.send(fl(depth), &protocol.Ghost{Pos: 10, Anim: "idle", HPPct: 100, HeroClass: "WARRIOR", Tier: 0}); err != nil {
		return err
	}
	_, err := ally.expect(protocol.TGhost)
	return err
}

func moveBoth(a, b *player, depth int) error {
	if err := move(a, b, depth); err != nil {
		return err
	}
	return move(b, a, depth)
}

// ---- client --------------------------------------------------------------------

type player struct {
	name  string
	ws    *websocket.Conn
	seq   int64
	ack   int64
	id    string
	token string
	epoch string
	pings int64
}

func (p *player) connect() error {
	ctx, cancel := context.WithTimeout(context.Background(), *timeout)
	defer cancel()
	ws, _, err := websocket.Dial(ctx, url, nil)
	if err != nil {
		return fmt.Errorf("%s dial %s: %w", p.name, url, err)
	}
	ws.SetReadLimit(64 << 10)
	p.ws, p.seq = ws, 0
	return nil
}

func (p *player) send(env protocol.Envelope, pl protocol.Payload) error {
	p.seq++
	env.Seq = p.seq
	frame, err := protocol.Encode(protocol.C2S, env, pl)
	if err != nil {
		return fmt.Errorf("%s encode %s: %w", p.name, pl.Type(), err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), *timeout)
	defer cancel()
	if err := p.ws.Write(ctx, websocket.MessageText, frame); err != nil {
		return fmt.Errorf("%s write: %w", p.name, err)
	}
	return nil
}

func (p *player) recv() (protocol.Message, error) {
	ctx, cancel := context.WithTimeout(context.Background(), *timeout)
	defer cancel()
	_, data, err := p.ws.Read(ctx)
	if err != nil {
		return protocol.Message{}, fmt.Errorf("%s read: %w", p.name, err)
	}
	m, err := protocol.Decode(protocol.S2C, data)
	if err != nil {
		return protocol.Message{}, fmt.Errorf("%s got an invalid message: %w", p.name, err)
	}
	if m.Seq > p.ack {
		p.ack = m.Seq
	}
	switch m.Payload.(type) {
	case *protocol.Error, *protocol.Reject:
		return m, fmt.Errorf("%s got %s %+v", p.name, m.T, m.Payload)
	}
	return m, nil
}

func (p *player) expect(t string) (protocol.Message, error) {
	for i := 0; i < 50; i++ {
		m, err := p.recv()
		if err != nil {
			return m, err
		}
		if m.T == t {
			return m, nil
		}
	}
	return protocol.Message{}, fmt.Errorf("%s: no %s within 50 messages", p.name, t)
}

// collect sends a PING and returns what arrived before its PONG.
func (p *player) collect() ([]protocol.Message, error) {
	p.pings++
	mark := p.pings
	if err := p.send(protocol.Envelope{}, &protocol.Ping{T: mark}); err != nil {
		return nil, err
	}
	var out []protocol.Message
	for i := 0; i < 100; i++ {
		m, err := p.recv()
		if err != nil {
			return nil, err
		}
		if pong, ok := m.Payload.(*protocol.Pong); ok {
			if pong.T == mark {
				return out, nil
			}
			continue
		}
		out = append(out, m)
	}
	return nil, fmt.Errorf("%s: no PONG within 100 messages", p.name)
}

func (p *player) hello(env protocol.Envelope, h *protocol.Hello) (*protocol.Welcome, error) {
	if err := p.send(env, h); err != nil {
		return nil, err
	}
	m, err := p.recv()
	if err != nil {
		return nil, err
	}
	w, ok := m.Payload.(*protocol.Welcome)
	if !ok {
		return nil, fmt.Errorf("%s: expected WELCOME, got %s", p.name, m.T)
	}
	p.id, p.token, p.epoch = w.PlayerID, w.ResumeToken, w.Epoch
	return w, nil
}

func (p *player) mobKills(depth int) ([]string, error) {
	if err := p.send(protocol.Envelope{}, &protocol.MobKillsReq{Depth: depth, Branch: 0}); err != nil {
		return nil, err
	}
	m, err := p.expect(protocol.TMobKills)
	if err != nil {
		return nil, err
	}
	return m.Payload.(*protocol.MobKills).MobNetIDs, nil
}

func (p *player) terrains(depth int) ([]protocol.TerrainChange, error) {
	if err := p.send(protocol.Envelope{}, &protocol.TerrainReq{Depth: depth, Branch: 0}); err != nil {
		return nil, err
	}
	m, err := p.expect(protocol.TTerrains)
	if err != nil {
		return nil, err
	}
	return m.Payload.(*protocol.Terrains).Changes, nil
}
