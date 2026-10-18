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

package store

import (
	"bytes"
	"context"
	"database/sql"
	"errors"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

func TestOpenMigratesAndReopens(t *testing.T) {
	ctx := context.Background()
	path := filepath.Join(t.TempDir(), "nested", "coop.db")

	s, err := Open(ctx, path)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if v, err := s.SchemaVersion(ctx); err != nil || v != SchemaVersion {
		t.Fatalf("SchemaVersion = %d, %v; want %d", v, err, SchemaVersion)
	}
	if err := s.Ping(ctx); err != nil {
		t.Fatalf("Ping: %v", err)
	}
	if err := s.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	// Reopening an already migrated database is a no-op.
	s, err = Open(ctx, path)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	defer s.Close()
	if v, err := s.SchemaVersion(ctx); err != nil || v != SchemaVersion {
		t.Fatalf("SchemaVersion after reopen = %d, %v; want %d", v, err, SchemaVersion)
	}
}

func TestOpenRejectsNewerSchema(t *testing.T) {
	ctx := context.Background()
	path := filepath.Join(t.TempDir(), "coop.db")

	s, err := Open(ctx, path)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if _, err := s.db.ExecContext(ctx, `UPDATE meta SET value = '999' WHERE key = 'schema_version'`); err != nil {
		t.Fatalf("bump version: %v", err)
	}
	s.Close()

	if _, err := Open(ctx, path); err == nil || !strings.Contains(err.Error(), "newer") {
		t.Fatalf("Open on a newer schema: err = %v, want a 'newer' error", err)
	}
}

// A database left by SRV-00 (schema v1: only the meta table) upgrades to the
// current schema.
func TestMigrateV1ToCurrent(t *testing.T) {
	ctx := context.Background()
	path := filepath.Join(t.TempDir(), "coop.db")

	db, err := sql.Open("sqlite", "file:"+path)
	if err != nil {
		t.Fatal(err)
	}
	for _, stmt := range []string{
		`CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)`,
		`INSERT INTO meta (key, value) VALUES ('schema_version', '1')`,
	} {
		if _, err := db.ExecContext(ctx, stmt); err != nil {
			t.Fatal(err)
		}
	}
	db.Close()

	s, err := Open(ctx, path)
	if err != nil {
		t.Fatalf("Open v1 database: %v", err)
	}
	defer s.Close()
	if v, err := s.SchemaVersion(ctx); err != nil || v != SchemaVersion {
		t.Fatalf("SchemaVersion after migration = %d, %v; want %d", v, err, SchemaVersion)
	}
	for _, table := range []string{"players", "runs", "run_players", "run_items", "run_state"} {
		var name string
		if err := s.db.QueryRowContext(ctx,
			`SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?`, table).Scan(&name); err != nil {
			t.Errorf("table %s missing after migration: %v", table, err)
		}
	}
}

func TestPlayersAndRuns(t *testing.T) {
	ctx := context.Background()
	s, err := Open(ctx, filepath.Join(t.TempDir(), "coop.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	hashA, hashB := bytes.Repeat([]byte{1}, 32), bytes.Repeat([]byte{2}, 32)
	if err := s.CreatePlayer(ctx, "aaaaaaaaaaaaaaaa", hashA, 100); err != nil {
		t.Fatal(err)
	}
	if err := s.CreatePlayer(ctx, "bbbbbbbbbbbbbbbb", hashB, 100); err != nil {
		t.Fatal(err)
	}
	if id, err := s.PlayerByTokenHash(ctx, hashA); err != nil || id != "aaaaaaaaaaaaaaaa" {
		t.Fatalf("PlayerByTokenHash = %q, %v", id, err)
	}
	if _, err := s.PlayerByTokenHash(ctx, bytes.Repeat([]byte{9}, 32)); !errors.Is(err, ErrNotFound) {
		t.Fatalf("unknown hash: err = %v, want ErrNotFound", err)
	}
	if _, err := s.ActiveRunForPlayer(ctx, "aaaaaaaaaaaaaaaa"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("no run yet: err = %v, want ErrNotFound", err)
	}

	run := Run{RunID: "1111111111111111", Code: "K7MZQ4", Seed: 42, RoleSeed: 7, CreatedAt: 200, Phase: RunRunning,
		Players: []RunPlayer{{"aaaaaaaaaaaaaaaa", 0, "WARRIOR", "desktop"}, {"bbbbbbbbbbbbbbbb", 1, "MAGE", "android"}}}
	if err := s.CreateRun(ctx, run); err != nil {
		t.Fatal(err)
	}
	got, err := s.ActiveRunForPlayer(ctx, "bbbbbbbbbbbbbbbb")
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(got, run) {
		t.Fatalf("ActiveRunForPlayer = %+v, want %+v", got, run)
	}
	if err := s.TouchPlayer(ctx, "aaaaaaaaaaaaaaaa", 300); err != nil {
		t.Fatal(err)
	}
}

// A database left by SRV-01 (schema v2, with a running run) upgrades to the
// current schema and keeps its rows.
func TestMigrateV2ToCurrentKeepsRuns(t *testing.T) {
	ctx := context.Background()
	path := filepath.Join(t.TempDir(), "coop.db")
	db, err := sql.Open("sqlite", "file:"+path)
	if err != nil {
		t.Fatal(err)
	}
	for _, stmt := range []string{
		`CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)`,
		`INSERT INTO meta (key, value) VALUES ('schema_version', '2')`,
		`CREATE TABLE players (player_id TEXT PRIMARY KEY, token_hash BLOB NOT NULL UNIQUE, created_at INTEGER NOT NULL, last_seen INTEGER NOT NULL)`,
		`CREATE TABLE runs (run_id TEXT PRIMARY KEY, code TEXT NOT NULL, seed INTEGER NOT NULL, role_seed INTEGER NOT NULL, created_at INTEGER NOT NULL, phase TEXT NOT NULL)`,
		`CREATE TABLE run_players (run_id TEXT NOT NULL REFERENCES runs(run_id), player_id TEXT NOT NULL REFERENCES players(player_id), slot INTEGER NOT NULL, hero_class TEXT NOT NULL, platform TEXT NOT NULL, PRIMARY KEY (run_id, slot))`,
		`CREATE INDEX run_players_by_player ON run_players(player_id)`,
		`INSERT INTO players VALUES ('aaaaaaaaaaaaaaaa', x'01', 1, 1)`,
		`INSERT INTO runs VALUES ('1111111111111111', 'K7MZQ4', 42, 7, 2, 'running')`,
		`INSERT INTO run_players VALUES ('1111111111111111', 'aaaaaaaaaaaaaaaa', 0, 'MAGE', 'ios')`,
	} {
		if _, err := db.ExecContext(ctx, stmt); err != nil {
			t.Fatal(err)
		}
	}
	db.Close()

	s, err := Open(ctx, path)
	if err != nil {
		t.Fatalf("Open v2 database: %v", err)
	}
	defer s.Close()
	if v, _ := s.SchemaVersion(ctx); v != SchemaVersion {
		t.Fatalf("SchemaVersion = %d, want %d", v, SchemaVersion)
	}
	r, err := s.ActiveRunForPlayer(ctx, "aaaaaaaaaaaaaaaa")
	if err != nil || r.Seed != 42 || len(r.Players) != 1 {
		t.Fatalf("run after migration = %+v, %v", r, err)
	}
	if items, err := s.Items(ctx, r.RunID); err != nil || len(items) != 0 {
		t.Fatalf("Items = %v, %v", items, err)
	}
	if _, _, err := s.RunState(ctx, r.RunID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("RunState = %v, want ErrNotFound", err)
	}
}

func TestItemsAndRunState(t *testing.T) {
	ctx := context.Background()
	path := filepath.Join(t.TempDir(), "coop.db")
	s, err := Open(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	for _, id := range []string{"aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb"} {
		if err := s.CreatePlayer(ctx, id, []byte(id), 1); err != nil {
			t.Fatal(err)
		}
	}
	run := Run{RunID: "2222222222222222", Code: "K7MZQ4", Seed: 1, RoleSeed: 1, CreatedAt: 1, Phase: RunRunning,
		Players: []RunPlayer{{"aaaaaaaaaaaaaaaa", 0, "WARRIOR", "desktop"}, {"bbbbbbbbbbbbbbbb", 1, "MAGE", "android"}}}
	if err := s.CreateRun(ctx, run); err != nil {
		t.Fatal(err)
	}
	a := Item{ItemNetID: "3:0:5", Owner: "aaaaaaaaaaaaaaaa", State: ItemClaimed, Depth: 3}
	b := Item{ItemNetID: "1:0:0", Owner: "", State: ItemDropped, Giver: "bbbbbbbbbbbbbbbb", Depth: 1}
	for _, it := range []Item{a, b} {
		if err := s.PutItem(ctx, run.RunID, it); err != nil {
			t.Fatal(err)
		}
	}
	b.Owner, b.State = "aaaaaaaaaaaaaaaa", ItemClaimed // update in place
	if err := s.PutItem(ctx, run.RunID, b); err != nil {
		t.Fatal(err)
	}
	if err := s.SaveRunState(ctx, run.RunID, []byte(`{"x":1}`), 10); err != nil {
		t.Fatal(err)
	}
	if err := s.SaveRunState(ctx, run.RunID, []byte(`{"x":2}`), 20); err != nil {
		t.Fatal(err)
	}
	s.Close()

	s, err = Open(ctx, path) // survives a reopen (a server restart)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	items, err := s.Items(ctx, run.RunID)
	if err != nil || !reflect.DeepEqual(items, []Item{b, a}) {
		t.Fatalf("Items = %+v, %v; want %+v", items, err, []Item{b, a})
	}
	st, at, err := s.RunState(ctx, run.RunID)
	if err != nil || string(st) != `{"x":2}` || at != 20 {
		t.Fatalf("RunState = %s, %d, %v", st, at, err)
	}
}

// A database left by SRV-02 (schema v3, with a run and an item) upgrades to
// v4 and keeps its rows; the new registries start empty.
func TestMigrateV3ToV4KeepsItems(t *testing.T) {
	ctx := context.Background()
	path := filepath.Join(t.TempDir(), "coop.db")
	db, err := sql.Open("sqlite", "file:"+path)
	if err != nil {
		t.Fatal(err)
	}
	for _, stmt := range []string{
		`CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)`,
		`INSERT INTO meta (key, value) VALUES ('schema_version', '3')`,
		`CREATE TABLE players (player_id TEXT PRIMARY KEY, token_hash BLOB NOT NULL UNIQUE, created_at INTEGER NOT NULL, last_seen INTEGER NOT NULL)`,
		`CREATE TABLE runs (run_id TEXT PRIMARY KEY, code TEXT NOT NULL, seed INTEGER NOT NULL, role_seed INTEGER NOT NULL, created_at INTEGER NOT NULL, phase TEXT NOT NULL)`,
		`CREATE TABLE run_players (run_id TEXT NOT NULL REFERENCES runs(run_id), player_id TEXT NOT NULL REFERENCES players(player_id), slot INTEGER NOT NULL, hero_class TEXT NOT NULL, platform TEXT NOT NULL, PRIMARY KEY (run_id, slot))`,
		`CREATE INDEX run_players_by_player ON run_players(player_id)`,
		`CREATE TABLE run_items (run_id TEXT NOT NULL REFERENCES runs(run_id), item_net_id TEXT NOT NULL, owner TEXT NOT NULL, state TEXT NOT NULL, giver TEXT NOT NULL, depth INTEGER NOT NULL, branch INTEGER NOT NULL, PRIMARY KEY (run_id, item_net_id))`,
		`CREATE TABLE run_state (run_id TEXT PRIMARY KEY REFERENCES runs(run_id), state TEXT NOT NULL, saved_at INTEGER NOT NULL)`,
		`INSERT INTO players VALUES ('aaaaaaaaaaaaaaaa', x'01', 1, 1)`,
		`INSERT INTO runs VALUES ('1111111111111111', 'K7MZQ4', 42, 7, 2, 'running')`,
		`INSERT INTO run_players VALUES ('1111111111111111', 'aaaaaaaaaaaaaaaa', 0, 'MAGE', 'ios')`,
		`INSERT INTO run_items VALUES ('1111111111111111', '3:0:5', 'aaaaaaaaaaaaaaaa', 'claimed', '', 3, 0)`,
	} {
		if _, err := db.ExecContext(ctx, stmt); err != nil {
			t.Fatal(err)
		}
	}
	db.Close()

	s, err := Open(ctx, path)
	if err != nil {
		t.Fatalf("Open v3 database: %v", err)
	}
	defer s.Close()
	if v, _ := s.SchemaVersion(ctx); v != 4 {
		t.Fatalf("SchemaVersion = %d, want 4", v)
	}
	if items, err := s.Items(ctx, "1111111111111111"); err != nil || len(items) != 1 || items[0].ItemNetID != "3:0:5" {
		t.Fatalf("Items = %+v, %v", items, err)
	}
	if k, err := s.Kills(ctx, "1111111111111111"); err != nil || len(k) != 0 {
		t.Fatalf("Kills = %+v, %v", k, err)
	}
	if tc, err := s.Terrain(ctx, "1111111111111111"); err != nil || len(tc) != 0 {
		t.Fatalf("Terrain = %+v, %v", tc, err)
	}
}

func TestKillsAndTerrain(t *testing.T) {
	ctx := context.Background()
	path := filepath.Join(t.TempDir(), "coop.db")
	s, err := Open(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	for _, id := range []string{"aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb"} {
		if err := s.CreatePlayer(ctx, id, []byte(id), 1); err != nil {
			t.Fatal(err)
		}
	}
	run := Run{RunID: "3333333333333333", Code: "K7MZQ4", Seed: 1, RoleSeed: 1, CreatedAt: 1, Phase: RunRunning,
		Players: []RunPlayer{{"aaaaaaaaaaaaaaaa", 0, "WARRIOR", "desktop"}, {"bbbbbbbbbbbbbbbb", 1, "MAGE", "android"}}}
	if err := s.CreateRun(ctx, run); err != nil {
		t.Fatal(err)
	}
	k1 := Kill{MobNetID: "3:0:7", Killer: "aaaaaaaaaaaaaaaa", Depth: 3}
	k2 := Kill{MobNetID: "2:0:1", Killer: "bbbbbbbbbbbbbbbb", Depth: 2}
	for _, k := range []Kill{k1, k2, {MobNetID: "3:0:7", Killer: "bbbbbbbbbbbbbbbb", Depth: 3}} {
		if err := s.PutKill(ctx, run.RunID, k); err != nil {
			t.Fatal(err)
		}
	}
	t1 := TerrainChange{Depth: 2, Cell: 431, Tile: 38}
	t2 := TerrainChange{Depth: 2, Cell: 208, Tile: 5}
	for _, tc := range []TerrainChange{t1, t2, {Depth: 2, Cell: 431, Tile: 5}} {
		if err := s.PutTerrain(ctx, run.RunID, tc); err != nil {
			t.Fatal(err)
		}
	}
	s.Close()

	s, err = Open(ctx, path) // survives a reopen (a server restart)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	kills, err := s.Kills(ctx, run.RunID)
	if want := []Kill{k2, k1}; err != nil || !reflect.DeepEqual(kills, want) {
		t.Fatalf("Kills = %+v, %v; want %+v (the first kill wins)", kills, err, want)
	}
	terrain, err := s.Terrain(ctx, run.RunID)
	if want := []TerrainChange{t2, {Depth: 2, Cell: 431, Tile: 5}}; err != nil || !reflect.DeepEqual(terrain, want) {
		t.Fatalf("Terrain = %+v, %v; want %+v (the last write wins)", terrain, err, want)
	}
}
