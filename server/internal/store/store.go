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

// Package store is the server's persistent state: a pure-Go SQLite database
// (modernc.org/sqlite, so the binary stays static for distroless).
// Schema v1 (SRV-00): the `meta` table holding the schema version.
// Schema v2 (SRV-01): players (resume-token hashes), runs and run_players,
// so a running run survives a server restart. Lobby rooms are memory-only.
// Schema v3 (SRV-02): run_items (shared item ownership, PROTOCOL.md §5.7) and
// run_state (the run's mission / Downed / Super Sync / boss engine state as
// one JSON document), so they survive a restart too.
package store

import (
	"context"
	"crypto/subtle"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"

	_ "modernc.org/sqlite" // registers the "sqlite" driver
)

// SchemaVersion is the schema this binary migrates the database to.
const SchemaVersion = 3

// Store wraps the SQLite database.
type Store struct {
	db *sql.DB
}

// Open opens (creating it if needed) the database at path and migrates it to
// SchemaVersion. The parent directory is created if it does not exist.
func Open(ctx context.Context, path string) (*Store, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		return nil, fmt.Errorf("create database directory: %w", err)
	}
	dsn := "file:" + path + "?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=foreign_keys(1)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("open database: %w", err)
	}
	// SQLite allows one writer at a time; one connection avoids SQLITE_BUSY
	// between our own goroutines.
	db.SetMaxOpenConns(1)
	s := &Store{db: db}
	if err := s.migrate(ctx); err != nil {
		_ = db.Close()
		return nil, err
	}
	return s, nil
}

func (s *Store) migrate(ctx context.Context) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin migration: %w", err)
	}
	defer func() { _ = tx.Rollback() }()

	if _, err := tx.ExecContext(ctx,
		`CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)`); err != nil {
		return fmt.Errorf("create meta table: %w", err)
	}

	current := 0
	var raw string
	switch err := tx.QueryRowContext(ctx, `SELECT value FROM meta WHERE key = 'schema_version'`).Scan(&raw); err {
	case nil:
		if current, err = strconv.Atoi(raw); err != nil {
			return fmt.Errorf("invalid schema_version %q: %w", raw, err)
		}
	case sql.ErrNoRows:
	default:
		return fmt.Errorf("read schema_version: %w", err)
	}
	if current > SchemaVersion {
		return fmt.Errorf("database schema version %d is newer than this server (%d)", current, SchemaVersion)
	}

	// One step per version; each runs inside this transaction.
	if current < 2 {
		for _, stmt := range []string{
			`CREATE TABLE players (
				player_id  TEXT PRIMARY KEY,
				token_hash BLOB NOT NULL UNIQUE,
				created_at INTEGER NOT NULL,
				last_seen  INTEGER NOT NULL)`,
			`CREATE TABLE runs (
				run_id     TEXT PRIMARY KEY,
				code       TEXT NOT NULL,
				seed       INTEGER NOT NULL,
				role_seed  INTEGER NOT NULL,
				created_at INTEGER NOT NULL,
				phase      TEXT NOT NULL)`,
			`CREATE TABLE run_players (
				run_id     TEXT NOT NULL REFERENCES runs(run_id),
				player_id  TEXT NOT NULL REFERENCES players(player_id),
				slot       INTEGER NOT NULL,
				hero_class TEXT NOT NULL,
				platform   TEXT NOT NULL,
				PRIMARY KEY (run_id, slot))`,
			`CREATE INDEX run_players_by_player ON run_players(player_id)`,
		} {
			if _, err := tx.ExecContext(ctx, stmt); err != nil {
				return fmt.Errorf("migrate to v2: %w", err)
			}
		}
	}

	if current < 3 {
		for _, stmt := range []string{
			`CREATE TABLE run_items (
				run_id      TEXT NOT NULL REFERENCES runs(run_id),
				item_net_id TEXT NOT NULL,
				owner       TEXT NOT NULL,
				state       TEXT NOT NULL,
				giver       TEXT NOT NULL,
				depth       INTEGER NOT NULL,
				branch      INTEGER NOT NULL,
				PRIMARY KEY (run_id, item_net_id))`,
			`CREATE TABLE run_state (
				run_id   TEXT PRIMARY KEY REFERENCES runs(run_id),
				state    TEXT NOT NULL,
				saved_at INTEGER NOT NULL)`,
		} {
			if _, err := tx.ExecContext(ctx, stmt); err != nil {
				return fmt.Errorf("migrate to v3: %w", err)
			}
		}
	}

	if _, err := tx.ExecContext(ctx,
		`INSERT INTO meta (key, value) VALUES ('schema_version', ?)
		 ON CONFLICT(key) DO UPDATE SET value = excluded.value`, strconv.Itoa(SchemaVersion)); err != nil {
		return fmt.Errorf("write schema_version: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit migration: %w", err)
	}
	return nil
}

// SchemaVersion returns the schema version recorded in the database.
func (s *Store) SchemaVersion(ctx context.Context) (int, error) {
	var raw string
	if err := s.db.QueryRowContext(ctx, `SELECT value FROM meta WHERE key = 'schema_version'`).Scan(&raw); err != nil {
		return 0, fmt.Errorf("read schema_version: %w", err)
	}
	return strconv.Atoi(raw)
}

// Ping checks that the database is reachable.
func (s *Store) Ping(ctx context.Context) error {
	return s.db.PingContext(ctx)
}

// ErrNotFound is returned when a looked-up row does not exist.
var ErrNotFound = errors.New("not found")

// CreatePlayer records a new player with the SHA-256 hash of its resume
// token (the token itself is never stored).
func (s *Store) CreatePlayer(ctx context.Context, playerID string, tokenHash []byte, now int64) error {
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO players (player_id, token_hash, created_at, last_seen) VALUES (?, ?, ?, ?)`,
		playerID, tokenHash, now, now)
	return err
}

// PlayerByTokenHash returns the player whose resume-token hash is tokenHash.
// The stored hash is compared in constant time.
func (s *Store) PlayerByTokenHash(ctx context.Context, tokenHash []byte) (string, error) {
	var id string
	var stored []byte
	err := s.db.QueryRowContext(ctx,
		`SELECT player_id, token_hash FROM players WHERE token_hash = ?`, tokenHash).Scan(&id, &stored)
	if errors.Is(err, sql.ErrNoRows) {
		return "", ErrNotFound
	}
	if err != nil {
		return "", err
	}
	if subtle.ConstantTimeCompare(stored, tokenHash) != 1 {
		return "", ErrNotFound
	}
	return id, nil
}

// TouchPlayer updates a player's last_seen.
func (s *Store) TouchPlayer(ctx context.Context, playerID string, now int64) error {
	_, err := s.db.ExecContext(ctx, `UPDATE players SET last_seen = ? WHERE player_id = ?`, now, playerID)
	return err
}

// Run phases stored in runs.phase.
const RunRunning = "running"

// RunPlayer is one row of run_players.
type RunPlayer struct {
	PlayerID  string
	Slot      int
	HeroClass string
	Platform  string
}

// Run is a started run.
type Run struct {
	RunID     string
	Code      string
	Seed      int64
	RoleSeed  int64
	CreatedAt int64
	Phase     string
	Players   []RunPlayer // sorted by slot
}

// CreateRun stores a run and its players in one transaction.
func (s *Store) CreateRun(ctx context.Context, r Run) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()
	if _, err := tx.ExecContext(ctx,
		`INSERT INTO runs (run_id, code, seed, role_seed, created_at, phase) VALUES (?, ?, ?, ?, ?, ?)`,
		r.RunID, r.Code, r.Seed, r.RoleSeed, r.CreatedAt, r.Phase); err != nil {
		return err
	}
	for _, p := range r.Players {
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO run_players (run_id, player_id, slot, hero_class, platform) VALUES (?, ?, ?, ?, ?)`,
			r.RunID, p.PlayerID, p.Slot, p.HeroClass, p.Platform); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// ActiveRunForPlayer returns the newest running run the player is in, or
// ErrNotFound.
func (s *Store) ActiveRunForPlayer(ctx context.Context, playerID string) (Run, error) {
	var r Run
	err := s.db.QueryRowContext(ctx,
		`SELECT r.run_id, r.code, r.seed, r.role_seed, r.created_at, r.phase
		   FROM runs r JOIN run_players rp ON rp.run_id = r.run_id
		  WHERE rp.player_id = ? AND r.phase = ?
		  ORDER BY r.created_at DESC, r.run_id DESC LIMIT 1`, playerID, RunRunning).
		Scan(&r.RunID, &r.Code, &r.Seed, &r.RoleSeed, &r.CreatedAt, &r.Phase)
	if errors.Is(err, sql.ErrNoRows) {
		return Run{}, ErrNotFound
	}
	if err != nil {
		return Run{}, err
	}
	rows, err := s.db.QueryContext(ctx,
		`SELECT player_id, slot, hero_class, platform FROM run_players WHERE run_id = ? ORDER BY slot`, r.RunID)
	if err != nil {
		return Run{}, err
	}
	defer rows.Close()
	for rows.Next() {
		var p RunPlayer
		if err := rows.Scan(&p.PlayerID, &p.Slot, &p.HeroClass, &p.Platform); err != nil {
			return Run{}, err
		}
		r.Players = append(r.Players, p)
	}
	return r, rows.Err()
}

// Item is one row of run_items: the server's record of a shared itemNetId.
type Item struct {
	ItemNetID string
	Owner     string // playerId, "" while a dropped item lies unclaimed
	State     string // ItemClaimed, ItemGiven or ItemDropped
	Giver     string // playerId that gave or dropped it, "" for a claim
	Depth     int    // the floor it was last registered on
	Branch    int
}

// Item states.
const (
	ItemClaimed = "claimed"
	ItemGiven   = "given"
	ItemDropped = "dropped"
)

// PutItem inserts or replaces one item record of a run.
func (s *Store) PutItem(ctx context.Context, runID string, it Item) error {
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO run_items (run_id, item_net_id, owner, state, giver, depth, branch)
		 VALUES (?, ?, ?, ?, ?, ?, ?)
		 ON CONFLICT(run_id, item_net_id) DO UPDATE SET
		   owner = excluded.owner, state = excluded.state, giver = excluded.giver,
		   depth = excluded.depth, branch = excluded.branch`,
		runID, it.ItemNetID, it.Owner, it.State, it.Giver, it.Depth, it.Branch)
	return err
}

// Items returns every item record of a run, sorted by itemNetId.
func (s *Store) Items(ctx context.Context, runID string) ([]Item, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT item_net_id, owner, state, giver, depth, branch FROM run_items
		  WHERE run_id = ? ORDER BY item_net_id`, runID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Item
	for rows.Next() {
		var it Item
		if err := rows.Scan(&it.ItemNetID, &it.Owner, &it.State, &it.Giver, &it.Depth, &it.Branch); err != nil {
			return nil, err
		}
		out = append(out, it)
	}
	return out, rows.Err()
}

// SaveRunState stores the run's engine state document (opaque to the store)
// and when it was taken (server Unix ms).
func (s *Store) SaveRunState(ctx context.Context, runID string, state []byte, savedAt int64) error {
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO run_state (run_id, state, saved_at) VALUES (?, ?, ?)
		 ON CONFLICT(run_id) DO UPDATE SET state = excluded.state, saved_at = excluded.saved_at`,
		runID, string(state), savedAt)
	return err
}

// RunState returns the run's engine state document and its saved_at, or
// ErrNotFound when none was saved.
func (s *Store) RunState(ctx context.Context, runID string) ([]byte, int64, error) {
	var state string
	var savedAt int64
	err := s.db.QueryRowContext(ctx, `SELECT state, saved_at FROM run_state WHERE run_id = ?`, runID).
		Scan(&state, &savedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, 0, ErrNotFound
	}
	if err != nil {
		return nil, 0, err
	}
	return []byte(state), savedAt, nil
}

// Close closes the database.
func (s *Store) Close() error {
	return s.db.Close()
}
