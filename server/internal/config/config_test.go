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

package config

import (
	"log/slog"
	"slices"
	"strings"
	"testing"
)

func env(m map[string]string) func(string) string {
	return func(k string) string { return m[k] }
}

func TestLoadDefaults(t *testing.T) {
	cfg, err := Load(env(nil))
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.Port != DefaultPort || cfg.DBPath != DefaultDBPath || cfg.LogLevel != slog.LevelInfo || cfg.AllowedVersions != nil {
		t.Fatalf("unexpected defaults: %+v", cfg)
	}
}

func TestLoadValues(t *testing.T) {
	cfg, err := Load(env(map[string]string{
		"PORT":             "9000",
		"DB_PATH":          "/tmp/x.db",
		"LOG_LEVEL":        "debug",
		"ALLOWED_VERSIONS": " 922 , 923",
	}))
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.Port != 9000 || cfg.DBPath != "/tmp/x.db" || cfg.LogLevel != slog.LevelDebug {
		t.Fatalf("unexpected config: %+v", cfg)
	}
	if !slices.Equal(cfg.AllowedVersions, []int{922, 923}) {
		t.Fatalf("AllowedVersions = %v", cfg.AllowedVersions)
	}
}

func TestLoadRejectsBadValues(t *testing.T) {
	_, err := Load(env(map[string]string{
		"PORT":             "abc",
		"DB_PATH":          "relative.db",
		"LOG_LEVEL":        "loud",
		"ALLOWED_VERSIONS": "922,,923",
	}))
	if err == nil {
		t.Fatal("Load accepted invalid config")
	}
	for _, want := range []string{"PORT", "DB_PATH", "LOG_LEVEL", "ALLOWED_VERSIONS"} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("error does not mention %s: %v", want, err)
		}
	}
}

func TestLoadRejectsVersionNames(t *testing.T) {
	for _, v := range []string{"0.1.0-coop", "922,abc", "0", "-5"} {
		if _, err := Load(env(map[string]string{"ALLOWED_VERSIONS": v})); err == nil {
			t.Errorf("ALLOWED_VERSIONS=%q accepted", v)
		}
	}
}

func TestLoadRejectsPortOutOfRange(t *testing.T) {
	for _, p := range []string{"0", "65536", "-1"} {
		if _, err := Load(env(map[string]string{"PORT": p})); err == nil {
			t.Errorf("PORT=%s accepted", p)
		}
	}
}
