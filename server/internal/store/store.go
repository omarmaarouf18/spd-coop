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
// SRV-00 only creates the schema bookkeeping (a `meta` table holding the
// schema version); game state tables come with later SRV tasks.
package store

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"path/filepath"
	"strconv"

	_ "modernc.org/sqlite" // registers the "sqlite" driver
)

// SchemaVersion is the schema this binary migrates the database to.
const SchemaVersion = 1

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

	// Future migrations go here, one step per version: if current < 2 { ... }.

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

// Close closes the database.
func (s *Store) Close() error {
	return s.db.Close()
}
