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

package protocol

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"slices"
	"sort"
	"strconv"
	"strings"
	"testing"
)

// FixturesDir is the shared contract (PROTOCOL.md §11), relative to this
// package. The Java client's contract test (NET-01) reads the same files.
const FixturesDir = "../../../docs/coop/protocol/fixtures"

type fixture struct {
	Name          string          `json:"name"`
	Direction     string          `json:"direction"`
	Valid         bool            `json:"valid"`
	Reason        string          `json:"reason"`
	Field         string          `json:"field"`
	IgnoredFields []string        `json:"ignoredFields"`
	Note          string          `json:"note"`
	Message       json.RawMessage `json:"message"`
}

var decodeKinds = []string{KindNotJSON, KindUnsupportedVersion, KindUnknownType, KindMissingField,
	KindUnknownField, KindForbiddenField, KindWrongType, KindOutOfRange, KindBadFormat, KindTooLong}

func loadFixtures(t *testing.T) []fixture {
	t.Helper()
	files, err := filepath.Glob(filepath.Join(FixturesDir, "*.json"))
	if err != nil || len(files) == 0 {
		t.Fatalf("no fixtures in %s (err %v)", FixturesDir, err)
	}
	sort.Strings(files)
	var out []fixture
	for _, file := range files {
		data, err := os.ReadFile(file)
		if err != nil {
			t.Fatal(err)
		}
		dec := json.NewDecoder(strings.NewReader(string(data)))
		dec.DisallowUnknownFields()
		var f fixture
		if err := dec.Decode(&f); err != nil {
			t.Fatalf("%s: not a valid fixture file: %v", file, err)
		}
		if want := strings.TrimSuffix(filepath.Base(file), ".json"); f.Name != want {
			t.Fatalf("%s: name %q must equal the file name %q", file, f.Name, want)
		}
		if f.Direction != "c2s" && f.Direction != "s2c" {
			t.Fatalf("%s: direction must be c2s or s2c", file)
		}
		if f.Valid && (f.Reason != "" || f.Field != "") {
			t.Fatalf("%s: a valid fixture has no reason/field", file)
		}
		if !f.Valid && !slices.Contains(decodeKinds, f.Reason) {
			t.Fatalf("%s: reason %q is not a decode-error kind", file, f.Reason)
		}
		out = append(out, f)
	}
	return out
}

// frame returns the raw frame: message is an object, or a string holding
// the raw text (the not_json cases).
func (f fixture) frame() []byte {
	var s string
	if json.Unmarshal(f.Message, &s) == nil {
		return []byte(s)
	}
	return f.Message
}

func TestContractFixtures(t *testing.T) {
	fixtures := loadFixtures(t)
	var valid, invalid int
	for _, f := range fixtures {
		dir := C2S
		if f.Direction == "s2c" {
			dir = S2C
		}
		t.Run(f.Name, func(t *testing.T) {
			msg, err := Decode(dir, f.frame())
			if !f.Valid {
				var de *DecodeError
				if !errors.As(err, &de) {
					t.Fatalf("decode accepted an invalid message (want %s %s); err=%v", f.Reason, f.Field, err)
				}
				if de.Kind != f.Reason || (f.Field != "" && de.Field != f.Field) {
					t.Fatalf("got %s at %q, want %s at %q (%v)", de.Kind, de.Field, f.Reason, f.Field, de)
				}
				return
			}
			if err != nil {
				t.Fatalf("decode rejected a valid message: %v", err)
			}
			if !slices.Equal(sorted(msg.Ignored), sorted(f.IgnoredFields)) {
				t.Fatalf("ignored fields = %v, want %v", msg.Ignored, f.IgnoredFields)
			}
			// Re-encode exactly what was decoded (raw marshal: c2s `from` is
			// kept as decoded) and compare semantically.
			got := reencode(t, msg)
			want := generic(t, f.Message)
			for _, path := range f.IgnoredFields {
				deletePath(want, strings.Split(path, "."))
			}
			if !reflect.DeepEqual(got, want) {
				g, _ := json.Marshal(got)
				w, _ := json.Marshal(want)
				t.Fatalf("re-encoded message differs\n got: %s\nwant: %s", g, w)
			}
		})
		if f.Valid {
			valid++
		} else {
			invalid++
		}
	}
	t.Logf("contract fixtures exercised: %d (%d valid, %d invalid)", len(fixtures), valid, invalid)
}

// Every v1 type in each direction has at least one valid fixture, and every
// reserved name has a test that it is unknown.
func TestContractCoverage(t *testing.T) {
	have := map[string]bool{}
	for _, f := range loadFixtures(t) {
		if !f.Valid {
			continue
		}
		var env struct{ T string }
		if err := json.Unmarshal(f.Message, &env); err != nil {
			t.Fatal(err)
		}
		have[f.Direction+":"+env.T] = true
	}
	for _, dir := range []Direction{C2S, S2C} {
		for _, typ := range Types(dir) {
			if !have[dir.String()+":"+typ] {
				t.Errorf("no valid %s fixture for %s", dir, typ)
			}
		}
	}
	for _, r := range Reserved {
		for _, dir := range []Direction{C2S, S2C} {
			frame := `{"v":1,"t":"` + r + `","seq":1,"p":{}}`
			_, err := Decode(dir, []byte(frame))
			var de *DecodeError
			if !errors.As(err, &de) || de.Kind != KindUnknownType || de.Code() != CodeUnknownType {
				t.Errorf("reserved %s (%s): err = %v, want unknown_type", r, dir, err)
			}
		}
	}
}

func reencode(t *testing.T, m Message) any {
	t.Helper()
	pRaw, err := json.Marshal(m.Payload)
	if err != nil {
		t.Fatal(err)
	}
	data, err := json.Marshal(struct {
		Envelope
		P json.RawMessage `json:"p"`
	}{m.Envelope, pRaw})
	if err != nil {
		t.Fatal(err)
	}
	return generic(t, data)
}

func generic(t *testing.T, data []byte) any {
	t.Helper()
	dec := json.NewDecoder(strings.NewReader(string(data)))
	dec.UseNumber()
	var v any
	if err := dec.Decode(&v); err != nil {
		t.Fatal(err)
	}
	return v
}

func deletePath(v any, path []string) {
	switch node := v.(type) {
	case map[string]any:
		if len(path) == 1 {
			delete(node, path[0])
			return
		}
		deletePath(node[path[0]], path[1:])
	case []any:
		if i, err := strconv.Atoi(path[0]); err == nil && i < len(node) {
			deletePath(node[i], path[1:])
		}
	}
}

func sorted(s []string) []string {
	out := slices.Clone(s)
	sort.Strings(out)
	return out
}
