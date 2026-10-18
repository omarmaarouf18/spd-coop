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
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"regexp"
	"slices"
	"sort"
	"strconv"
	"strings"
	"unicode/utf8"
)

// The schema check walks the generic JSON tree (numbers kept as
// json.Number) before the typed unmarshal, so every violation maps to one
// decode-error kind and a dotted field path (PROTOCOL.md §11).

func parseJSON(data []byte) (map[string]any, error) {
	if !utf8.Valid(data) {
		return nil, &DecodeError{Kind: KindNotJSON, Msg: "frame is not valid UTF-8"}
	}
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.UseNumber()
	var root any
	if err := dec.Decode(&root); err != nil {
		return nil, &DecodeError{Kind: KindNotJSON, Msg: err.Error()}
	}
	if _, err := dec.Token(); !errors.Is(err, io.EOF) {
		return nil, &DecodeError{Kind: KindNotJSON, Msg: "trailing data after the JSON object"}
	}
	obj, ok := root.(map[string]any)
	if !ok {
		return nil, &DecodeError{Kind: KindNotJSON, Msg: "frame is not a JSON object"}
	}
	return obj, nil
}

type validator struct {
	dir     Direction
	err     *DecodeError
	ignored []string
}

func (v *validator) fail(kind, path, format string, args ...any) {
	if v.err == nil {
		v.err = &DecodeError{Kind: kind, Field: path, Msg: fmt.Sprintf(format, args...)}
	}
}

var envelopeKeys = []string{"v", "t", "seq", "ack", "from", "depth", "branch", "p"}

// envelope validates the top level and the payload; it returns the decoded
// envelope, the type's spec and the raw payload for the typed unmarshal.
func (v *validator) envelope(root map[string]any) (Envelope, typeSpec, json.RawMessage) {
	var env Envelope

	// v first: an unsupported version is answered with VERSION_MISMATCH
	// before anything else is looked at.
	ver, ok := root["v"]
	if !ok {
		v.fail(KindMissingField, "v", "required")
		return env, typeSpec{}, nil
	}
	n, ok := v.integer("v", ver, 0, MaxSafeInt)
	if !ok {
		return env, typeSpec{}, nil
	}
	if n != Version {
		v.fail(KindUnsupportedVersion, "v", "unsupported envelope version %d (this is v%d)", n, Version)
		return env, typeSpec{}, nil
	}
	env.V = Version

	t, ok := root["t"]
	if !ok {
		v.fail(KindMissingField, "t", "required")
		return env, typeSpec{}, nil
	}
	ts, ok := t.(string)
	if !ok {
		v.fail(KindWrongType, "t", "must be a string")
		return env, typeSpec{}, nil
	}
	spec, known := specs[ts]
	if !known || (v.dir == C2S && !spec.c2s) || (v.dir == S2C && !spec.s2c) {
		v.fail(KindUnknownType, "t", "%q is not a v1 %s type", ts, v.dir)
		return env, typeSpec{}, nil
	}
	env.T = ts

	v.unknownKeys("", root, envelopeKeys)

	if raw, ok := root["seq"]; !ok {
		v.fail(KindMissingField, "seq", "required")
	} else if n, ok := v.integer("seq", raw, 1, MaxSafeInt); ok {
		env.Seq = n
	}
	if raw, ok := root["ack"]; ok {
		if n, ok := v.integer("ack", raw, 0, MaxSafeInt); ok {
			env.Ack = Int64(n)
		}
	}

	// from: ignored on c2s (the server overwrites it, but it must still be a
	// string); on s2c required exactly on relayed types.
	raw, hasFrom := root["from"]
	switch {
	case v.dir == C2S && hasFrom:
		if s, ok := raw.(string); ok {
			env.From = s
		} else {
			v.fail(KindWrongType, "from", "must be a string")
		}
	case v.dir == S2C && Relayed(ts) && !hasFrom:
		v.fail(KindMissingField, "from", "required on relayed %s", ts)
	case v.dir == S2C && Relayed(ts):
		if s, ok := v.str("from", raw); ok && v.match("from", s, hexIDRe, "a 16-char lowercase hex playerId") {
			env.From = s
		}
	case v.dir == S2C && hasFrom:
		v.fail(KindForbiddenField, "from", "only relayed types (§5.3, §5.7, §5.9, §5.10) carry from")
	}

	v.floor(root, spec.floor, ts, &env)

	praw, ok := root["p"]
	if !ok {
		v.fail(KindMissingField, "p", "required (use {} for an empty payload)")
		return env, spec, nil
	}
	pobj, ok := praw.(map[string]any)
	if !ok {
		v.fail(KindWrongType, "p", "must be an object")
		return env, spec, nil
	}
	v.object("p", pobj, spec.payload)
	if v.err != nil {
		return env, spec, nil
	}
	// Re-marshal the validated payload for the typed unmarshal.
	pRaw, err := json.Marshal(pobj)
	if err != nil {
		v.fail(KindWrongType, "p", "%v", err)
	}
	return env, spec, pRaw
}

func (v *validator) floor(root map[string]any, rule floorRule, t string, env *Envelope) {
	d, hasD := root["depth"]
	b, hasB := root["branch"]
	switch {
	case rule == floorForbidden && hasD:
		v.fail(KindForbiddenField, "depth", "%s does not carry depth/branch", t)
		return
	case rule == floorForbidden && hasB:
		v.fail(KindForbiddenField, "branch", "%s does not carry depth/branch", t)
		return
	case rule == floorRequired && !hasD:
		v.fail(KindMissingField, "depth", "%s requires depth and branch", t)
		return
	case rule == floorRequired && !hasB, rule == floorOptional && hasD && !hasB:
		v.fail(KindMissingField, "branch", "depth and branch go together")
		return
	case rule == floorOptional && hasB && !hasD:
		v.fail(KindMissingField, "depth", "depth and branch go together")
		return
	}
	if hasD {
		if n, ok := v.integer("depth", d, 1, 30); ok {
			env.Depth = Int(int(n))
		}
	}
	if hasB {
		if n, ok := v.integer("branch", b, 0, 9); ok {
			env.Branch = Int(int(n))
		}
	}
}

// unknownKeys rejects (c2s) or records (s2c) keys outside known.
func (v *validator) unknownKeys(prefix string, obj map[string]any, known []string) {
	var extra []string
	for k := range obj {
		if !slices.Contains(known, k) {
			extra = append(extra, k)
		}
	}
	sort.Strings(extra)
	for _, k := range extra {
		path := join(prefix, k)
		if v.dir == C2S {
			v.fail(KindUnknownField, path, "unknown field")
			return
		}
		v.ignored = append(v.ignored, path)
		delete(obj, k) // so the typed unmarshal and re-encode never see it
	}
}

// ---- schema building blocks ------------------------------------------------

type check func(v *validator, path string, x any)

type field struct {
	name     string
	required bool
	check    check
}

type objSchema struct {
	fields []field
	// check runs after the fields, for cross-field rules.
	check func(v *validator, path string, obj map[string]any)
}

func (v *validator) object(path string, obj map[string]any, s objSchema) {
	known := make([]string, len(s.fields))
	for i, f := range s.fields {
		known[i] = f.name
	}
	v.unknownKeys(path, obj, known)
	for _, f := range s.fields {
		x, ok := obj[f.name]
		if !ok {
			if f.required {
				v.fail(KindMissingField, join(path, f.name), "required")
			}
			continue
		}
		f.check(v, join(path, f.name), x)
	}
	if s.check != nil && v.err == nil {
		s.check(v, path, obj)
	}
}

func object(s objSchema) check {
	return func(v *validator, path string, x any) {
		obj, ok := x.(map[string]any)
		if !ok {
			v.fail(KindWrongType, path, "must be an object")
			return
		}
		v.object(path, obj, s)
	}
}

func array(minLen, maxLen int, elem check) check {
	return func(v *validator, path string, x any) {
		arr, ok := x.([]any)
		if !ok {
			v.fail(KindWrongType, path, "must be an array")
			return
		}
		if len(arr) < minLen || len(arr) > maxLen {
			v.fail(KindOutOfRange, path, "must have %d..%d elements, has %d", minLen, maxLen, len(arr))
			return
		}
		for i, e := range arr {
			elem(v, join(path, strconv.Itoa(i)), e)
		}
	}
}

func boolean(v *validator, path string, x any) {
	if _, ok := x.(bool); !ok {
		v.fail(KindWrongType, path, "must be a boolean")
	}
}

func intRange(lo, hi int64) check {
	return func(v *validator, path string, x any) { v.integer(path, x, lo, hi) }
}

// integer accepts a JSON number without fraction or exponent in [lo, hi].
func (v *validator) integer(path string, x any, lo, hi int64) (int64, bool) {
	num, ok := x.(json.Number)
	if !ok || strings.ContainsAny(num.String(), ".eE") {
		v.fail(KindWrongType, path, "must be an integer")
		return 0, false
	}
	n, err := strconv.ParseInt(num.String(), 10, 64)
	if err != nil || n < lo || n > hi {
		v.fail(KindOutOfRange, path, "must be in %d..%d, got %s", lo, hi, num)
		return 0, false
	}
	return n, true
}

func (v *validator) str(path string, x any) (string, bool) {
	s, ok := x.(string)
	if !ok {
		v.fail(KindWrongType, path, "must be a string")
	}
	return s, ok
}

func (v *validator) match(path, s string, re *regexp.Regexp, what string) bool {
	if !re.MatchString(s) {
		v.fail(KindBadFormat, path, "must be %s", what)
		return false
	}
	return true
}

var (
	hexIDRe    = regexp.MustCompile(`^[0-9a-f]{16}$`)
	tokenRe    = regexp.MustCompile(`^[A-Za-z0-9_-]{43}$`)
	roomCodeRe = regexp.MustCompile(`^[` + RoomCodeAlphabet + `]{6}$`)
	// itemNetIDRe is §3: "<depth>:<branch>:<genIndex>" (depth 1..30, branch 0..9,
	// genIndex 0..9999999999, per DET-01's CoopItemIds) or a runtime
	// "<playerId16hex>:<counter>" (counter 1..9999999999).
	itemNetIDRe = regexp.MustCompile(`^(?:(?:[12][0-9]|30|[1-9]):[0-9]:(?:0|[1-9][0-9]{0,9})|[0-9a-f]{16}:(?:[1-9][0-9]{0,9}))$`)
	// mobNetIDRe is §3: "<depth>:<branch>:<mobIndex>" only (no runtime form).
	mobNetIDRe = regexp.MustCompile(`^(?:[12][0-9]|30|[1-9]):[0-9]:(?:0|[1-9][0-9]{0,9})$`)
)

func formatted(re *regexp.Regexp, what string) check {
	return func(v *validator, path string, x any) {
		if s, ok := v.str(path, x); ok {
			v.match(path, s, re, what)
		}
	}
}

var (
	hexID            = formatted(hexIDRe, "16 lowercase hex chars")
	token            = formatted(tokenRe, "43 base64url chars")
	roomCode         = formatted(roomCodeRe, "6 chars from "+RoomCodeAlphabet)
	itemNet          = formatted(itemNetIDRe, `"<depth>:<branch>:<genIndex>" or "<playerId>:<counter>" (§3)`)
	mobNet           = formatted(mobNetIDRe, `"<depth>:<branch>:<mobIndex>" (§3)`)
	opaqueBlob check = opaque
)

func enum(values ...string) check {
	return func(v *validator, path string, x any) {
		if s, ok := v.str(path, x); ok && !slices.Contains(values, s) {
			v.fail(KindBadFormat, path, "must be one of %s", strings.Join(values, ", "))
		}
	}
}

func text(maxChars int) check {
	return func(v *validator, path string, x any) {
		if s, ok := v.str(path, x); ok && utf8.RuneCountInString(s) > maxChars {
			v.fail(KindTooLong, path, "at most %d chars", maxChars)
		}
	}
}

// slotsAscending requires the players' slots to be strictly ascending
// (0 then 1), so slots are distinct and the list is sorted.
func slotsAscending(name string) func(v *validator, path string, obj map[string]any) {
	return func(v *validator, path string, obj map[string]any) {
		players, _ := obj[name].([]any)
		prev := int64(-1)
		for i, p := range players {
			pm, _ := p.(map[string]any)
			n, _ := pm["slot"].(json.Number).Int64()
			if n <= prev {
				v.fail(KindBadFormat, join(join(path, name), strconv.Itoa(i))+".slot", "slots must be distinct and ascending")
				return
			}
			prev = n
		}
	}
}

// chain combines objSchema checks and keeps the first failure; a rule that
// already fired an error stops further ones from running.
func chain(rules ...func(v *validator, path string, obj map[string]any)) func(v *validator, path string, obj map[string]any) {
	return func(v *validator, path string, obj map[string]any) {
		for _, r := range rules {
			if v.err != nil {
				return
			}
			r(v, path, obj)
		}
	}
}

// valueDep makes dep required when trigger is one of when, and forbidden
// otherwise (e.g. GIVE.item when kind == "item").
func valueDep(trigger, dep string, when []string) func(v *validator, path string, obj map[string]any) {
	return func(v *validator, path string, obj map[string]any) {
		val, _ := obj[trigger].(string)
		_, has := obj[dep]
		switch {
		case slices.Contains(when, val) && !has:
			v.fail(KindMissingField, join(path, dep), "required with %s %q", trigger, val)
		case !slices.Contains(when, val) && has:
			v.fail(KindForbiddenField, join(path, dep), "forbidden unless %s is one of (%s)", trigger, strings.Join(when, ", "))
		}
	}
}

// prisonersDiffer rejects a mission addressed to one player twice.
func prisonersDiffer(v *validator, path string, obj map[string]any) {
	prisoner, _ := obj["prisoner"].(string)
	rescuer, _ := obj["rescuer"].(string)
	if prisoner == rescuer {
		v.fail(KindBadFormat, join(path, "rescuer"), "prisoner and rescuer must be different players")
	}
}

// pairList validates a flat [cell, tile, cell, tile, …] array (§5.9).
func pairList(maxPairs int, lo, hi int64) check {
	return func(v *validator, path string, x any) {
		arr, ok := x.([]any)
		if !ok {
			v.fail(KindWrongType, path, "must be an array")
			return
		}
		if len(arr) > 2*maxPairs {
			v.fail(KindOutOfRange, path, "at most %d pairs, has %d", maxPairs, len(arr))
			return
		}
		if len(arr)%2 != 0 {
			v.fail(KindWrongType, path, "must hold complete [cell, tile] pairs, got %d values", len(arr))
			return
		}
		for i, e := range arr {
			intRange(lo, hi)(v, join(path, strconv.Itoa(i)), e)
		}
	}
}

// opaque validates an opaque string field: at most MaxOpaque bytes.
func opaque(v *validator, path string, x any) {
	if s, ok := v.str(path, x); ok && len(s) > MaxOpaque {
		v.fail(KindTooLong, path, "at most %d bytes", MaxOpaque)
	}
}

func epochNeedsToken(v *validator, path string, obj map[string]any) {
	_, hasEpoch := obj["epoch"]
	_, hasToken := obj["resumeToken"]
	if hasEpoch && !hasToken {
		v.fail(KindForbiddenField, join(path, "epoch"), "epoch is only sent together with resumeToken")
	}
}

func join(prefix, name string) string {
	if prefix == "" {
		return name
	}
	return prefix + "." + name
}
