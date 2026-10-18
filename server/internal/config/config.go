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

// Package config loads and validates the server configuration from
// environment variables (architecture §4.2). There are no secrets in v1.
package config

import (
	"errors"
	"fmt"
	"log/slog"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"time"
)

// Defaults for unset variables.
const (
	DefaultPort     = 8080
	DefaultDBPath   = "/data/coop.db"
	DefaultLogLevel = "info"
)

// Config is the validated server configuration.
type Config struct {
	Port     int
	DBPath   string
	LogLevel slog.Level
	// AllowedVersions lists the gameVersionCodes (the client's
	// appVersionCode) accepted at the handshake: comma-separated integers in
	// ALLOWED_VERSIONS. Empty means no version gate (PROTOCOL.md §8).
	AllowedVersions []int
	// Coop holds the COOP_* gameplay settings (server/README.md).
	Coop Coop
}

// Coop is the validated co-op gameplay configuration. Durations use Go
// syntax ("30s", "4m", "1500ms").
type Coop struct {
	CellWaveInterval time.Duration // COOP_CELL_WAVE_INTERVAL
	CellCap          time.Duration // COOP_CELL_CAP
	CellCapPerRegion time.Duration // COOP_CELL_CAP_PER_REGION
	DownedWindow     time.Duration // COOP_DOWNED_WINDOW
	PauseGrace       time.Duration // COOP_PAUSE_GRACE
	TwinWindow       time.Duration // COOP_TWIN_WINDOW
	BossHPScale      float64       // COOP_BOSS_HP_SCALE
	DeathMode        string        // COOP_DEATH_MODE
}

// DefaultCoop is the configuration with every COOP_* variable unset.
var DefaultCoop = Coop{
	CellWaveInterval: 30 * time.Second,
	CellCap:          4 * time.Minute,
	CellCapPerRegion: 15 * time.Second,
	DownedWindow:     30 * time.Second,
	PauseGrace:       90 * time.Second,
	TwinWindow:       2 * time.Second,
	BossHPScale:      1.8,
	DeathMode:        "player_out",
}

// DeathModes are the accepted COOP_DEATH_MODE values (v1: one).
var DeathModes = []string{"player_out"}

// Load reads PORT, DB_PATH, LOG_LEVEL, ALLOWED_VERSIONS and COOP_* through getenv
// (os.Getenv in production) and returns every validation error at once.
func Load(getenv func(string) string) (Config, error) {
	cfg := Config{Port: DefaultPort, DBPath: DefaultDBPath}
	var errs []error

	if v := strings.TrimSpace(getenv("PORT")); v != "" {
		port, err := strconv.Atoi(v)
		if err != nil || port < 1 || port > 65535 {
			errs = append(errs, fmt.Errorf("PORT must be an integer in 1..65535, got %q", v))
		} else {
			cfg.Port = port
		}
	}

	if v := strings.TrimSpace(getenv("DB_PATH")); v != "" {
		cfg.DBPath = v
	}
	if !filepath.IsAbs(cfg.DBPath) {
		errs = append(errs, fmt.Errorf("DB_PATH must be an absolute path, got %q", cfg.DBPath))
	}

	level := strings.TrimSpace(getenv("LOG_LEVEL"))
	if level == "" {
		level = DefaultLogLevel
	}
	if err := cfg.LogLevel.UnmarshalText([]byte(level)); err != nil {
		errs = append(errs, fmt.Errorf("LOG_LEVEL must be debug, info, warn or error, got %q", level))
	}

	if v := strings.TrimSpace(getenv("ALLOWED_VERSIONS")); v != "" {
		for _, part := range strings.Split(v, ",") {
			part = strings.TrimSpace(part)
			code, err := strconv.Atoi(part)
			if err != nil || code < 1 {
				errs = append(errs, fmt.Errorf("ALLOWED_VERSIONS must be comma-separated gameVersionCodes (integers >= 1), got %q", v))
				break
			}
			cfg.AllowedVersions = append(cfg.AllowedVersions, code)
		}
	}

	cfg.Coop, errs = loadCoop(getenv, errs)
	return cfg, errors.Join(errs...)
}

func loadCoop(getenv func(string) string, errs []error) (Coop, []error) {
	c := DefaultCoop
	dur := func(name string, dst *time.Duration, lo, hi time.Duration) {
		v := strings.TrimSpace(getenv(name))
		if v == "" {
			return
		}
		d, err := time.ParseDuration(v)
		if err != nil || d < lo || d > hi {
			errs = append(errs, fmt.Errorf("%s must be a duration in %s..%s (e.g. \"30s\"), got %q", name, lo, hi, v))
			return
		}
		*dst = d
	}
	dur("COOP_CELL_WAVE_INTERVAL", &c.CellWaveInterval, time.Second, 10*time.Minute)
	dur("COOP_CELL_CAP", &c.CellCap, 10*time.Second, time.Hour)
	dur("COOP_CELL_CAP_PER_REGION", &c.CellCapPerRegion, 0, 10*time.Minute)
	dur("COOP_DOWNED_WINDOW", &c.DownedWindow, time.Second, 10*time.Minute)
	dur("COOP_PAUSE_GRACE", &c.PauseGrace, time.Second, time.Hour)
	dur("COOP_TWIN_WINDOW", &c.TwinWindow, 100*time.Millisecond, 30*time.Second)

	if v := strings.TrimSpace(getenv("COOP_BOSS_HP_SCALE")); v != "" {
		f, err := strconv.ParseFloat(v, 64)
		if err != nil || !(f >= 0.1 && f <= 10) {
			errs = append(errs, fmt.Errorf("COOP_BOSS_HP_SCALE must be a number in 0.1..10, got %q", v))
		} else {
			c.BossHPScale = f
		}
	}
	if v := strings.TrimSpace(getenv("COOP_DEATH_MODE")); v != "" {
		if !slices.Contains(DeathModes, v) {
			errs = append(errs, fmt.Errorf("COOP_DEATH_MODE must be one of %v, got %q", DeathModes, v))
		} else {
			c.DeathMode = v
		}
	}
	return c, errs
}
