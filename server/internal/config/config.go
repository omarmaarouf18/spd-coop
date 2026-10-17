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
	"strconv"
	"strings"
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
	// AllowedVersions lists the game versions accepted at the handshake
	// (comma-separated ALLOWED_VERSIONS). Empty means no version gate yet;
	// the handshake itself lands in SRV-01.
	AllowedVersions []string
}

// Load reads PORT, DB_PATH, LOG_LEVEL and ALLOWED_VERSIONS through getenv
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
			if part == "" {
				errs = append(errs, fmt.Errorf("ALLOWED_VERSIONS has an empty entry: %q", v))
				continue
			}
			cfg.AllowedVersions = append(cfg.AllowedVersions, part)
		}
	}

	return cfg, errors.Join(errs...)
}
