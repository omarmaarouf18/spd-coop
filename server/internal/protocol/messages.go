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

// Value sets (PROTOCOL.md §3).
var (
	Platforms   = []string{"desktop", "android", "ios"}
	HeroClasses = []string{"WARRIOR", "MAGE", "ROGUE", "HUNTRESS", "DUELIST", "CLERIC"}
	Anims       = []string{"idle", "run", "attack", "operate", "zap", "die"}
	MarkKinds   = []string{"HERE", "DANGER", "ITEM", "GO"}
	Phases      = []string{PhaseLobby, PhaseRunning}
	// RejectCodes may appear in REJECT, ErrorCodes in ERROR (§4.3).
	RejectCodes = []string{CodeBadMessage, CodeVersionMismatch, CodeResumeFailed}
	ErrorCodes  = []string{CodeBadMessage, CodeUnknownType, CodeInvalidState, CodeNotInRoom,
		CodeAlreadyInRoom, CodeRoomNotFound, CodeRoomFull, CodeRateLimited, CodeSessionReplaced, CodeInternal}
)

// Room phases.
const (
	PhaseLobby   = "lobby"
	PhaseRunning = "running"
)

// RoomCodeAlphabet is the room-code alphabet: no 0, O, 1, I, L (§3).
const RoomCodeAlphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

// RoomCodeLen is the length of a room code.
const RoomCodeLen = 6

// ---- session -------------------------------------------------------------

type Hello struct {
	Protocol        int    `json:"protocol"`
	GameVersionCode int    `json:"gameVersionCode"`
	Platform        string `json:"platform"`
	ResumeToken     string `json:"resumeToken,omitempty"`
	Epoch           string `json:"epoch,omitempty"`
}

type Welcome struct {
	PlayerID    string     `json:"playerId"`
	ResumeToken string     `json:"resumeToken"`
	Epoch       string     `json:"epoch"`
	ServerTime  int64      `json:"serverTime"`
	Resumed     bool       `json:"resumed"`
	Replayed    int        `json:"replayed"`
	Complete    bool       `json:"complete"`
	Room        *RoomState `json:"room,omitempty"`
	Run         *RunInfo   `json:"run,omitempty"`
}

type Reject struct {
	Code               string `json:"code"`
	Msg                string `json:"msg"`
	MinGameVersionCode *int   `json:"minGameVersionCode,omitempty"`
}

type Ping struct {
	T int64 `json:"t"`
}

type Pong struct {
	T          int64 `json:"t"`
	ServerTime int64 `json:"serverTime"`
}

type Error struct {
	Code   string `json:"code"`
	Msg    string `json:"msg"`
	RefSeq *int64 `json:"ref_seq,omitempty"`
}

// ---- lobby and run start ---------------------------------------------------

type RoomCreate struct{}

type RoomJoin struct {
	Code string `json:"code"`
}

type RoomLeave struct{}

type Ready struct {
	HeroClass string `json:"heroClass"`
	Ready     bool   `json:"ready"`
}

type RoomPlayer struct {
	PlayerID  string `json:"playerId"`
	Slot      int    `json:"slot"`
	HeroClass string `json:"heroClass,omitempty"`
	Ready     bool   `json:"ready"`
	Connected bool   `json:"connected"`
	Platform  string `json:"platform"`
}

type RoomState struct {
	Code    string       `json:"code"`
	Phase   string       `json:"phase"`
	Players []RoomPlayer `json:"players"`
}

type RunPlayer struct {
	PlayerID  string `json:"playerId"`
	Slot      int    `json:"slot"`
	HeroClass string `json:"heroClass"`
	Platform  string `json:"platform"`
}

// RunInfo is the RUN_START payload; WELCOME.run carries the same object.
type RunInfo struct {
	RunID    string      `json:"runId"`
	Seed     int64       `json:"seed"`
	RoleSeed int64       `json:"roleSeed"`
	Players  []RunPlayer `json:"players"`
}

// RunStart is sent once per room when both players are ready.
type RunStart RunInfo

type AllyStatus struct {
	PlayerID  string `json:"playerId"`
	Connected bool   `json:"connected"`
	Paused    bool   `json:"paused"`
	LastSeen  int64  `json:"lastSeen"`
}

// ---- presence ------------------------------------------------------------

type Ghost struct {
	Pos       int    `json:"pos"`
	Anim      string `json:"anim"`
	HPPct     int    `json:"hpPct"`
	HeroClass string `json:"heroClass"`
	Tier      int    `json:"tier"`
}

type Mark struct {
	Cell int    `json:"cell"`
	Kind string `json:"kind"`
}

type Quick struct {
	PhraseID int `json:"phraseId"`
}

func (*Hello) Type() string      { return THello }
func (*Welcome) Type() string    { return TWelcome }
func (*Reject) Type() string     { return TReject }
func (*Ping) Type() string       { return TPing }
func (*Pong) Type() string       { return TPong }
func (*Error) Type() string      { return TError }
func (*RoomCreate) Type() string { return TRoomCreate }
func (*RoomJoin) Type() string   { return TRoomJoin }
func (*RoomLeave) Type() string  { return TRoomLeave }
func (*Ready) Type() string      { return TReady }
func (*RoomState) Type() string  { return TRoomState }
func (*RunStart) Type() string   { return TRunStart }
func (*AllyStatus) Type() string { return TAllyStatus }
func (*Ghost) Type() string      { return TGhost }
func (*Mark) Type() string       { return TMark }
func (*Quick) Type() string      { return TQuick }

// Reliable reports whether an s2c type is kept in the event log and
// replayed on resume (§5, §7).
func Reliable(t string) bool {
	return t == TRoomState || t == TRunStart || t == TAllyStatus
}

// Presence reports whether t is a relayed presence type (§5.3).
func Presence(t string) bool {
	return t == TGhost || t == TMark || t == TQuick
}

// ---- schema ----------------------------------------------------------------

// floorRule says whether depth/branch are required, optional or forbidden.
type floorRule int

const (
	floorForbidden floorRule = iota
	floorRequired
	floorOptional
)

type typeSpec struct {
	c2s, s2c   bool
	floor      floorRule
	payload    objSchema
	newPayload func() Payload
}

var (
	roomPlayerSchema = objSchema{
		fields: []field{
			{"playerId", true, hexID},
			{"slot", true, intRange(0, 1)},
			{"heroClass", false, enum(HeroClasses...)},
			{"ready", true, boolean},
			{"connected", true, boolean},
			{"platform", true, enum(Platforms...)},
		},
	}
	roomStateSchema = objSchema{
		fields: []field{
			{"code", true, roomCode},
			{"phase", true, enum(Phases...)},
			{"players", true, array(1, 2, object(roomPlayerSchema))},
		},
		check: slotsAscending("players"),
	}
	runPlayerSchema = objSchema{
		fields: []field{
			{"playerId", true, hexID},
			{"slot", true, intRange(0, 1)},
			{"heroClass", true, enum(HeroClasses...)},
			{"platform", true, enum(Platforms...)},
		},
	}
	runInfoSchema = objSchema{
		fields: []field{
			{"runId", true, hexID},
			{"seed", true, intRange(0, MaxSeedExclusive-1)},
			{"roleSeed", true, intRange(0, MaxSafeInt)},
			{"players", true, array(2, 2, object(runPlayerSchema))},
		},
		check: slotsAscending("players"),
	}
)

var specs = map[string]typeSpec{
	THello: {c2s: true, payload: objSchema{
		fields: []field{
			{"protocol", true, intRange(0, 1<<31-1)},
			{"gameVersionCode", true, intRange(1, 1<<31-1)},
			{"platform", true, enum(Platforms...)},
			{"resumeToken", false, token},
			{"epoch", false, hexID},
		},
		check: epochNeedsToken,
	}, newPayload: func() Payload { return &Hello{} }},
	TWelcome: {s2c: true, payload: objSchema{
		fields: []field{
			{"playerId", true, hexID},
			{"resumeToken", true, token},
			{"epoch", true, hexID},
			{"serverTime", true, intRange(0, MaxSafeInt)},
			{"resumed", true, boolean},
			{"replayed", true, intRange(0, MaxSafeInt)},
			{"complete", true, boolean},
			{"room", false, object(roomStateSchema)},
			{"run", false, object(runInfoSchema)},
		},
	}, newPayload: func() Payload { return &Welcome{} }},
	TReject: {s2c: true, payload: objSchema{
		fields: []field{
			{"code", true, enum(RejectCodes...)},
			{"msg", true, text(256)},
			{"minGameVersionCode", false, intRange(1, 1<<31-1)},
		},
	}, newPayload: func() Payload { return &Reject{} }},
	TPing: {c2s: true, payload: objSchema{
		fields: []field{{"t", true, intRange(0, MaxSafeInt)}},
	}, newPayload: func() Payload { return &Ping{} }},
	TPong: {s2c: true, payload: objSchema{
		fields: []field{
			{"t", true, intRange(0, MaxSafeInt)},
			{"serverTime", true, intRange(0, MaxSafeInt)},
		},
	}, newPayload: func() Payload { return &Pong{} }},
	TError: {s2c: true, payload: objSchema{
		fields: []field{
			{"code", true, enum(ErrorCodes...)},
			{"msg", true, text(256)},
			{"ref_seq", false, intRange(1, MaxSafeInt)},
		},
	}, newPayload: func() Payload { return &Error{} }},
	TRoomCreate: {c2s: true, payload: objSchema{}, newPayload: func() Payload { return &RoomCreate{} }},
	TRoomJoin: {c2s: true, payload: objSchema{
		fields: []field{{"code", true, roomCode}},
	}, newPayload: func() Payload { return &RoomJoin{} }},
	TRoomLeave: {c2s: true, payload: objSchema{}, newPayload: func() Payload { return &RoomLeave{} }},
	TReady: {c2s: true, payload: objSchema{
		fields: []field{
			{"heroClass", true, enum(HeroClasses...)},
			{"ready", true, boolean},
		},
	}, newPayload: func() Payload { return &Ready{} }},
	TRoomState: {s2c: true, payload: roomStateSchema, newPayload: func() Payload { return &RoomState{} }},
	TRunStart:  {s2c: true, payload: runInfoSchema, newPayload: func() Payload { return &RunStart{} }},
	TAllyStatus: {s2c: true, payload: objSchema{
		fields: []field{
			{"playerId", true, hexID},
			{"connected", true, boolean},
			{"paused", true, boolean},
			{"lastSeen", true, intRange(0, MaxSafeInt)},
		},
	}, newPayload: func() Payload { return &AllyStatus{} }},
	TGhost: {c2s: true, s2c: true, floor: floorRequired, payload: objSchema{
		fields: []field{
			{"pos", true, intRange(0, 65535)},
			{"anim", true, enum(Anims...)},
			{"hpPct", true, intRange(0, 100)},
			{"heroClass", true, enum(HeroClasses...)},
			{"tier", true, intRange(0, 6)},
		},
	}, newPayload: func() Payload { return &Ghost{} }},
	TMark: {c2s: true, s2c: true, floor: floorRequired, payload: objSchema{
		fields: []field{
			{"cell", true, intRange(0, 65535)},
			{"kind", true, enum(MarkKinds...)},
		},
	}, newPayload: func() Payload { return &Mark{} }},
	TQuick: {c2s: true, s2c: true, floor: floorOptional, payload: objSchema{
		fields: []field{{"phraseId", true, intRange(0, 63)}},
	}, newPayload: func() Payload { return &Quick{} }},
}

// Types returns every v1 type valid in dir.
func Types(dir Direction) []string {
	var out []string
	for t, s := range specs {
		if (dir == C2S && s.c2s) || (dir == S2C && s.s2c) {
			out = append(out, t)
		}
	}
	return out
}
