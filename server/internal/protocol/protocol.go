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

// Package protocol implements protocol v1 (docs/coop/protocol/PROTOCOL.md):
// the envelope, the typed payloads, strict decoding with machine-checkable
// error kinds, and encoding. The fixtures in docs/coop/protocol/fixtures are
// the contract (contract_test.go); the Java client must pass the same files.
//
// Decoding is direction-aware (PROTOCOL.md §5.5): c2s rejects unknown
// fields, s2c ignores them. Checks that need connection state (seq order,
// the state machine, rate limits) are not done here.
package protocol

import (
	"encoding/json"
	"fmt"
)

// Version is the envelope `v` and the HELLO `protocol` of this package.
const Version = 1

// MaxSeedExclusive is SPD's DungeonSeed.TOTAL_SEEDS (26^9): 0 <= seed < it.
const MaxSeedExclusive int64 = 5429503678976

// MaxSafeInt is the largest JSON-safe integer (2^53 - 1).
const MaxSafeInt int64 = 1<<53 - 1

// MaxOpaque is the byte limit of every opaque string field (§3).
const MaxOpaque = 16384

// MaxCell is the cell-index upper bound (int64 form of the §3 cell rule).
const MaxCell int64 = 65535

// MaxFloorList caps MOB_KILLS.mobNetIds and TERRAINS.changes (§5.11).
const MaxFloorList = 512

// Direction of a message.
type Direction int

const (
	C2S Direction = iota // client → server
	S2C                  // server → client
)

func (d Direction) String() string {
	if d == C2S {
		return "c2s"
	}
	return "s2c"
}

// Message types (PROTOCOL.md §5).
const (
	THello      = "HELLO"
	TWelcome    = "WELCOME"
	TReject     = "REJECT"
	TPing       = "PING"
	TPong       = "PONG"
	TError      = "ERROR"
	TRoomCreate = "ROOM_CREATE"
	TRoomJoin   = "ROOM_JOIN"
	TRoomLeave  = "ROOM_LEAVE"
	TReady      = "READY"
	TRoomState  = "ROOM_STATE"
	TRunStart   = "RUN_START"
	TAllyStatus = "ALLY_STATUS"
	TGhost      = "GHOST"
	TMark       = "MARK"
	TQuick      = "QUICK"
	// Missions and The Cell (§5.6).
	TMissionTrigger = "MISSION_TRIGGER"
	TMissionStart   = "MISSION_START"
	TMissionUpdate  = "MISSION_UPDATE"
	TMissionEnd     = "MISSION_END"
	// Shared items (§5.7).
	TClaim     = "CLAIM"
	TClaimed   = "CLAIMED"
	TClaimsReq = "CLAIMS_REQ"
	TClaims    = "CLAIMS"
	TGive      = "GIVE"
	TTransfer  = "TRANSFER"
	TDropShare = "DROP_SHARE"
	TDeny      = "DENY"
	// Downed and revive (§5.8).
	TDowned        = "DOWNED"
	TAllyDowned    = "ALLY_DOWNED"
	TUseRecall     = "USE_RECALL"
	TRevive        = "REVIVE"
	TDownedExpired = "DOWNED_EXPIRED"
	// Super Sync (§5.9).
	TSyncDrink  = "SYNC_DRINK"
	TSyncStart  = "SYNC_START"
	TSyncView   = "SYNC_VIEW"
	TSyncCmd    = "SYNC_CMD"
	TSyncReport = "SYNC_REPORT"
	TSyncEnd    = "SYNC_END"
	// Shared boss HP, twin doors, terrain mirroring (§5.10).
	TBossEngage = "BOSS_ENGAGE"
	TBossHit    = "BOSS_HIT"
	TBossHP     = "BOSS_HP"
	TBossDead   = "BOSS_DEAD"
	TTwinAct    = "TWIN_ACT"
	TTwinDone   = "TWIN_DONE"
	TTerrain    = "TERRAIN"
	// Connected worlds: shared mobs, terrain record (§5.11).
	TMobKilled   = "MOB_KILLED"
	TMobDead     = "MOB_DEAD"
	TMobKillsReq = "MOB_KILLS_REQ"
	TMobKills    = "MOB_KILLS"
	TTerrainReq  = "TERRAIN_REQ"
	TTerrains    = "TERRAINS"
)

// Reserved lists the type names reserved for later versions (§5.4). They
// decode as unknown_type in v1; within v1 the list only shrinks.
var Reserved = []string{
	"TASK_DONE", "COOP_ROOM_ACT", "COOP_ROOM_STATE",
}

// Error codes (PROTOCOL.md §4.3, closed list).
const (
	CodeBadMessage      = "BAD_MESSAGE"
	CodeUnknownType     = "UNKNOWN_TYPE"
	CodeVersionMismatch = "VERSION_MISMATCH"
	CodeResumeFailed    = "RESUME_FAILED"
	CodeInvalidState    = "INVALID_STATE"
	CodeNotInRoom       = "NOT_IN_ROOM"
	CodeAlreadyInRoom   = "ALREADY_IN_ROOM"
	CodeRoomNotFound    = "ROOM_NOT_FOUND"
	CodeRoomFull        = "ROOM_FULL"
	CodeRateLimited     = "RATE_LIMITED"
	CodeSessionReplaced = "SESSION_REPLACED"
	CodeInternal        = "INTERNAL"
)

// Websocket close statuses (PROTOCOL.md §9.1) beyond the standard ones.
const (
	CloseRejected        = 4001
	CloseTimeout         = 4002
	CloseSessionReplaced = 4003
)

// Decode-error kinds (PROTOCOL.md §11): the fixtures' `reason` values.
const (
	KindNotJSON            = "not_json"
	KindUnsupportedVersion = "unsupported_version"
	KindUnknownType        = "unknown_type"
	KindMissingField       = "missing_field"
	KindUnknownField       = "unknown_field"
	KindForbiddenField     = "forbidden_field"
	KindWrongType          = "wrong_type"
	KindOutOfRange         = "out_of_range"
	KindBadFormat          = "bad_format"
	KindTooLong            = "too_long"
)

// DecodeError is a message that does not conform to the spec.
type DecodeError struct {
	Kind  string // one of the Kind* constants
	Field string // dotted path ("p.code", "depth"), empty for the whole message
	Msg   string
}

func (e *DecodeError) Error() string {
	if e.Field == "" {
		return fmt.Sprintf("%s: %s", e.Kind, e.Msg)
	}
	return fmt.Sprintf("%s at %s: %s", e.Kind, e.Field, e.Msg)
}

// Code is the protocol error code this decode error is answered with.
func (e *DecodeError) Code() string {
	switch e.Kind {
	case KindUnsupportedVersion:
		return CodeVersionMismatch
	case KindUnknownType:
		return CodeUnknownType
	default:
		return CodeBadMessage
	}
}

// Envelope is everything of a message except the payload (§2).
// Optional fields are pointers (or empty strings) and omitted when unset.
type Envelope struct {
	V      int    `json:"v"`
	T      string `json:"t"`
	Seq    int64  `json:"seq"`
	Ack    *int64 `json:"ack,omitempty"`
	From   string `json:"from,omitempty"`
	Depth  *int   `json:"depth,omitempty"`
	Branch *int   `json:"branch,omitempty"`
}

// Payload is a typed message payload (messages.go).
type Payload interface {
	Type() string
}

// Message is a decoded message.
type Message struct {
	Envelope
	Payload Payload
	// Ignored lists the dotted paths of unknown fields that an s2c decode
	// dropped (§5.5). Always empty for c2s.
	Ignored []string
}

// DecodeC2S decodes a client → server frame.
func DecodeC2S(data []byte) (Envelope, Payload, error) {
	m, err := Decode(C2S, data)
	return m.Envelope, m.Payload, err
}

// DecodeS2C decodes a server → client frame.
func DecodeS2C(data []byte) (Envelope, Payload, error) {
	m, err := Decode(S2C, data)
	return m.Envelope, m.Payload, err
}

// Decode validates data against the v1 schema for dir and returns the typed
// message. Every error is a *DecodeError.
func Decode(dir Direction, data []byte) (Message, error) {
	root, err := parseJSON(data)
	if err != nil {
		return Message{}, err
	}
	v := &validator{dir: dir}
	env, spec, pRaw := v.envelope(root)
	if v.err != nil {
		return Message{}, v.err
	}
	payload := spec.newPayload()
	// The schema check above guarantees this succeeds; unknown s2c fields
	// are dropped by encoding/json.
	if err := json.Unmarshal(pRaw, payload); err != nil {
		return Message{}, &DecodeError{Kind: KindWrongType, Field: "p", Msg: err.Error()}
	}
	return Message{Envelope: env, Payload: payload, Ignored: v.ignored}, nil
}

// Encode builds the frame for env + p, filling env.V and env.T, and checks
// the result against the schema for dir, so a sender can never emit a
// message the receiver would reject.
func Encode(dir Direction, env Envelope, p Payload) ([]byte, error) {
	env.V = Version
	env.T = p.Type()
	pRaw, err := json.Marshal(p)
	if err != nil {
		return nil, err
	}
	frame := struct {
		Envelope
		P json.RawMessage `json:"p"`
	}{env, pRaw}
	data, err := json.Marshal(frame)
	if err != nil {
		return nil, err
	}
	if _, err := Decode(dir, data); err != nil {
		return nil, fmt.Errorf("encode %s %s: %w", dir, env.T, err)
	}
	return data, nil
}

// Int returns a pointer to v, for the optional envelope fields.
func Int(v int) *int { return &v }

// Int64 returns a pointer to v.
func Int64(v int64) *int64 { return &v }
