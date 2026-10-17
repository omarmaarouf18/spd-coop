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
	"errors"
	"strings"
	"testing"
)

func TestEncodeDecodeRoundTrip(t *testing.T) {
	data, err := Encode(S2C, Envelope{Seq: 7, From: "a1b2c3d4e5f60718", Depth: Int(3), Branch: Int(0)},
		&Ghost{Pos: 10, Anim: "idle", HPPct: 50, HeroClass: "ROGUE", Tier: 2})
	if err != nil {
		t.Fatalf("Encode: %v", err)
	}
	env, p, err := DecodeS2C(data)
	if err != nil {
		t.Fatalf("DecodeS2C(%s): %v", data, err)
	}
	g, ok := p.(*Ghost)
	if !ok || env.T != TGhost || env.V != 1 || env.Seq != 7 || *env.Depth != 3 || g.HPPct != 50 || g.HeroClass != "ROGUE" {
		t.Fatalf("round trip lost data: env=%+v payload=%+v", env, p)
	}
}

func TestEncodeRefusesInvalidMessages(t *testing.T) {
	cases := []struct {
		name string
		dir  Direction
		env  Envelope
		p    Payload
	}{
		{"seed out of range", S2C, Envelope{Seq: 1}, &RunStart{RunID: "c0ffee0123456789", Seed: MaxSeedExclusive,
			Players: []RunPlayer{{"a1b2c3d4e5f60718", 0, "MAGE", "ios"}, {"b2c3d4e5f6071829", 1, "MAGE", "ios"}}}},
		{"ghost without floor", C2S, Envelope{Seq: 1}, &Ghost{Anim: "idle", HeroClass: "MAGE"}},
		{"seq zero", C2S, Envelope{}, &RoomCreate{}},
		{"s2c-only type as c2s", C2S, Envelope{Seq: 1}, &Pong{}},
		{"error msg too long", S2C, Envelope{Seq: 1}, &Error{Code: CodeInternal, Msg: strings.Repeat("x", 257)}},
	}
	for _, c := range cases {
		if _, err := Encode(c.dir, c.env, c.p); err == nil {
			t.Errorf("%s: Encode accepted an invalid message", c.name)
		}
	}
}

func TestDecodeErrorCodes(t *testing.T) {
	cases := map[string]string{
		`{"v":2,"t":"HELLO","seq":1,"p":{}}`:       CodeVersionMismatch,
		`{"v":1,"t":"NOPE","seq":1,"p":{}}`:        CodeUnknownType,
		`{"v":1,"t":"PING","seq":1,"p":{"t":"x"}}`: CodeBadMessage,
		`not json`: CodeBadMessage,
		`{"v":1,"t":"PING","seq":1,"p":{"t":1}} {}`:     CodeBadMessage,
		"{\"v\":1,\"t\":\"PING\",\"seq\":1,\"p\":\xff}": CodeBadMessage,
	}
	for frame, want := range cases {
		_, _, err := DecodeC2S([]byte(frame))
		var de *DecodeError
		if !errors.As(err, &de) || de.Code() != want {
			t.Errorf("%q: err = %v, want code %s", frame, err, want)
		}
	}
}

func TestReliableAndPresence(t *testing.T) {
	for _, typ := range []string{TRoomState, TRunStart, TAllyStatus} {
		if !Reliable(typ) {
			t.Errorf("%s must be reliable", typ)
		}
	}
	for _, typ := range []string{TWelcome, TReject, TPong, TError, TGhost, TMark, TQuick} {
		if Reliable(typ) {
			t.Errorf("%s must not be reliable", typ)
		}
	}
	for _, typ := range []string{TGhost, TMark, TQuick} {
		if !Presence(typ) {
			t.Errorf("%s must be presence", typ)
		}
	}
}
