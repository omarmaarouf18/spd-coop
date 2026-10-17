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
	// Co-op gameplay value sets (§5.6–§5.10).
	MissionKinds   = []string{"CELL"}
	MissionResults = []string{"success", "revived", "failed", "aborted"}
	ReviveModes    = []string{"inPlace", "regionStart"}
	ReviveMethods  = []string{"recall", "healing"}
	SyncActions    = []string{"move", "attack", "wait"}
	ReportReasons  = []string{"expired", "died", "interrupted"}
	ItemKinds      = []string{"cell_key", "item"}
	TwinKinds      = []string{"door", "switch"}
	// DenyReasons is the closed list of DENY.reason values (§5.7).
	DenyReasons = []string{"ally_offline", "ally_other_floor", "mission_active", "invalid_state",
		"not_owner", "already_transferred", "taken", "no_pool", "sync_active", "downed", "not_downed"}
)

// DENY reasons that carry meaning beyond the closed list above.
const (
	DenyReasonAllyOffline        = "ally_offline"
	DenyReasonAllyOtherFloor     = "ally_other_floor"
	DenyReasonMissionActive      = "mission_active"
	DenyReasonInvalidState       = "invalid_state"
	DenyReasonNotOwner           = "not_owner"
	DenyReasonAlreadyTransferred = "already_transferred"
	DenyReasonTaken              = "taken"
	DenyReasonNoPool             = "no_pool"
	DenyReasonSyncActive         = "sync_active"
	DenyReasonDowned             = "downed"
	DenyReasonNotDowned          = "not_downed"
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

// ---- missions (§5.6) -------------------------------------------------------

type MissionTrigger struct {
	Kind     string `json:"kind"`
	TrapCell int    `json:"trapCell"`
	RoomID   int64  `json:"roomId"`
}

type MissionStart struct {
	MissionID      string `json:"missionId"`
	Kind           string `json:"kind"`
	Prisoner       string `json:"prisoner"`
	Rescuer        string `json:"rescuer"`
	Depth          int    `json:"depth"`
	Branch         int    `json:"branch"`
	TrapCell       int    `json:"trapCell"`
	RoomID         int64  `json:"roomId"`
	KeySeed        int64  `json:"keySeed"`
	StartsAt       int64  `json:"startsAt"`
	Deadline       int64  `json:"deadline"`
	WaveIntervalMs int64  `json:"waveIntervalMs"`
}

// MissionTasks is the MISSION_UPDATE.tasks object (optional).
type MissionTasks struct {
	Key *bool `json:"key,omitempty"`
}

type MissionUpdate struct {
	MissionID   string        `json:"missionId"`
	Paused      bool          `json:"paused"`
	RemainingMs int64         `json:"remainingMs"`
	Tasks       *MissionTasks `json:"tasks,omitempty"`
}

type MissionPenalty struct {
	RegionStartDepth int `json:"regionStartDepth"`
	GoldPct          int `json:"goldPct"`
}

type MissionReward struct {
	RewardSeed int64 `json:"rewardSeed"`
}

type MissionEnd struct {
	MissionID string          `json:"missionId"`
	Result    string          `json:"result"`
	Penalty   *MissionPenalty `json:"penalty,omitempty"`
	Reward    *MissionReward  `json:"reward,omitempty"`
}

// ---- shared items (§5.7) ---------------------------------------------------

type Claim struct {
	ItemNetID string `json:"itemNetId"`
}

type Claimed struct {
	ItemNetID string `json:"itemNetId"`
	By        string `json:"by"`
}

type ClaimsReq struct {
	Depth  int `json:"depth"`
	Branch int `json:"branch"`
}

type Claims struct {
	Depth      int      `json:"depth"`
	Branch     int      `json:"branch"`
	ItemNetIDs []string `json:"itemNetIds"`
}

type Give struct {
	ItemNetID string  `json:"itemNetId"`
	Kind      string  `json:"kind"`
	Item      *string `json:"item,omitempty"`
	ToCell    *int    `json:"toCell,omitempty"`
}

type Transfer struct {
	ItemNetID string  `json:"itemNetId"`
	From      string  `json:"from"`
	To        string  `json:"to"`
	Kind      string  `json:"kind"`
	Item      *string `json:"item,omitempty"`
}

// DropShare is relayed (from set) like presence, and also registered.
type DropShare struct {
	Cell      int    `json:"cell"`
	ItemNetID string `json:"itemNetId"`
	Item      string `json:"item"`
}

type Deny struct {
	Reason string `json:"reason"`
}

// ---- downed and revive (§5.8) ----------------------------------------------

type Downed struct {
	Cause string `json:"cause,omitempty"`
}

type AllyDowned struct {
	Player   string `json:"player"`
	Deadline int64  `json:"deadline"`
}

type UseRecall struct {
	Target string `json:"target"`
	Via    string `json:"via"`
}

type Revive struct {
	Target string `json:"target"`
	Mode   string `json:"mode"`
	HPPct  int    `json:"hpPct"`
}

type DownedExpired struct {
	Player string `json:"player"`
}

// ---- Super Sync (§5.9) -----------------------------------------------------

type SyncDrink struct {
	Snapshot string `json:"snapshot"`
	Turns    int64  `json:"turns"`
}

type SyncStart struct {
	SyncID   string `json:"syncId"`
	Snapshot string `json:"snapshot"`
	Turns    int64  `json:"turns"`
}

// SyncEntity is one entry of SYNC_VIEW.entities.
type SyncEntity struct {
	ID    int64  `json:"id"`
	Kind  string `json:"kind"`
	Cell  int    `json:"cell"`
	HPPct int    `json:"hpPct"`
}

// SyncView is relayed (from set); the host streams, the drinker applies.
type SyncView struct {
	SyncID      string       `json:"syncId"`
	Turn        int64        `json:"turn"`
	TerrainDiff []int64      `json:"terrainDiff"`
	Entities    []SyncEntity `json:"entities"`
}

type SyncCmd struct {
	SyncID string `json:"syncId"`
	Turn   int64  `json:"turn"`
	Action string `json:"action"`
	Cell   *int   `json:"cell,omitempty"`
}

type SyncReport struct {
	SyncID string `json:"syncId"`
	HPLost int64  `json:"hpLost"`
	Reason string `json:"reason"`
}

type SyncEnd struct {
	SyncID string `json:"syncId"`
	HPLost int64  `json:"hpLost"`
	Reason string `json:"reason"`
}

// ---- shared boss HP, twin doors, terrain (§5.10) ----------------------------

type BossEngage struct {
	BossID int64 `json:"bossId"`
	HT     int64 `json:"ht"`
}

type BossHit struct {
	BossID int64 `json:"bossId"`
	Dmg    int64 `json:"dmg"`
}

type BossHP struct {
	BossID int64 `json:"bossId"`
	HP     int64 `json:"hp"`
	HT     int64 `json:"ht"`
}

type BossDead struct {
	BossID int64 `json:"bossId"`
}

type TwinAct struct {
	ObjectID int64  `json:"objectId"`
	Kind     string `json:"kind"`
	Cell     int    `json:"cell"`
	Active   bool   `json:"active"`
	GroupID  *int64 `json:"groupId,omitempty"`
}

type TwinDone struct {
	GroupID int64 `json:"groupId"`
}

// Terrain is relayed (from set); only door tiles pass the server.
type Terrain struct {
	Cell int `json:"cell"`
	Tile int `json:"tile"`
}

// DoorTerrain whitelist (§5.10): SPD v4.0.2 Terrain values.
const (
	TileDoor     = 5 // Terrain.DOOR
	TileOpenDoor = 6 // Terrain.OPEN_DOOR
	TileEmbers   = 9 // Terrain.EMBERS: the burned-through (broken) door
)

func IsDoorTile(tile int) bool {
	return tile == TileDoor || tile == TileOpenDoor || tile == TileEmbers
}

func (*Hello) Type() string          { return THello }
func (*Welcome) Type() string        { return TWelcome }
func (*Reject) Type() string         { return TReject }
func (*Ping) Type() string           { return TPing }
func (*Pong) Type() string           { return TPong }
func (*Error) Type() string          { return TError }
func (*RoomCreate) Type() string     { return TRoomCreate }
func (*RoomJoin) Type() string       { return TRoomJoin }
func (*RoomLeave) Type() string      { return TRoomLeave }
func (*Ready) Type() string          { return TReady }
func (*RoomState) Type() string      { return TRoomState }
func (*RunStart) Type() string       { return TRunStart }
func (*AllyStatus) Type() string     { return TAllyStatus }
func (*Ghost) Type() string          { return TGhost }
func (*Mark) Type() string           { return TMark }
func (*Quick) Type() string          { return TQuick }
func (*MissionTrigger) Type() string { return TMissionTrigger }
func (*MissionStart) Type() string   { return TMissionStart }
func (*MissionUpdate) Type() string  { return TMissionUpdate }
func (*MissionEnd) Type() string     { return TMissionEnd }
func (*Claim) Type() string          { return TClaim }
func (*Claimed) Type() string        { return TClaimed }
func (*ClaimsReq) Type() string      { return TClaimsReq }
func (*Claims) Type() string         { return TClaims }
func (*Give) Type() string           { return TGive }
func (*Transfer) Type() string       { return TTransfer }
func (*DropShare) Type() string      { return TDropShare }
func (*Deny) Type() string           { return TDeny }
func (*Downed) Type() string         { return TDowned }
func (*AllyDowned) Type() string     { return TAllyDowned }
func (*UseRecall) Type() string      { return TUseRecall }
func (*Revive) Type() string         { return TRevive }
func (*DownedExpired) Type() string  { return TDownedExpired }
func (*SyncDrink) Type() string      { return TSyncDrink }
func (*SyncStart) Type() string      { return TSyncStart }
func (*SyncView) Type() string       { return TSyncView }
func (*SyncCmd) Type() string        { return TSyncCmd }
func (*SyncReport) Type() string     { return TSyncReport }
func (*SyncEnd) Type() string        { return TSyncEnd }
func (*BossEngage) Type() string     { return TBossEngage }
func (*BossHit) Type() string        { return TBossHit }
func (*BossHP) Type() string         { return TBossHP }
func (*BossDead) Type() string       { return TBossDead }
func (*TwinAct) Type() string        { return TTwinAct }
func (*TwinDone) Type() string       { return TTwinDone }
func (*Terrain) Type() string        { return TTerrain }

// Reliable reports whether an s2c type is kept in the event log and
// replayed on resume (§5, §7).
func Reliable(t string) bool {
	switch t {
	case TRoomState, TRunStart, TAllyStatus,
		TMissionStart, TMissionUpdate, TMissionEnd,
		TClaimed, TTransfer, TDropShare,
		TAllyDowned, TRevive, TDownedExpired,
		TSyncStart, TSyncEnd,
		TBossHP, TBossDead,
		TTwinDone:
		return true
	}
	return false
}

// Presence reports whether t is a relayed presence type (§5.3).
func Presence(t string) bool {
	return t == TGhost || t == TMark || t == TQuick
}

// Relayed reports whether t is relayed between the two players with `from`
// and the floor copied from the sender (§5.3, §5.7, §5.9, §5.10).
func Relayed(t string) bool {
	return Presence(t) || t == TDropShare || t == TSyncView || t == TSyncCmd || t == TTerrain
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
	missionPenaltySchema = objSchema{
		fields: []field{
			{"regionStartDepth", true, intRange(1, 30)},
			{"goldPct", true, intRange(0, 100)},
		},
	}
	missionRewardSchema = objSchema{
		fields: []field{
			{"rewardSeed", true, intRange(0, MaxSafeInt)},
		},
	}
	syncEntitySchema = objSchema{
		fields: []field{
			{"id", true, intRange(1, MaxSafeInt)},
			{"kind", true, text(32)},
			{"cell", true, intRange(0, MaxCell)},
			{"hpPct", true, intRange(0, 100)},
		},
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

	// ---- missions (§5.6) ------------------------------------------------------

	TMissionTrigger: {c2s: true, floor: floorRequired, payload: objSchema{
		fields: []field{
			{"kind", true, enum(MissionKinds...)},
			{"trapCell", true, intRange(0, MaxCell)},
			{"roomId", true, intRange(1, MaxSafeInt)},
		},
	}, newPayload: func() Payload { return &MissionTrigger{} }},
	TMissionStart: {s2c: true, payload: objSchema{
		fields: []field{
			{"missionId", true, hexID},
			{"kind", true, enum(MissionKinds...)},
			{"prisoner", true, hexID},
			{"rescuer", true, hexID},
			{"depth", true, intRange(1, 30)},
			{"branch", true, intRange(0, 9)},
			{"trapCell", true, intRange(0, MaxCell)},
			{"roomId", true, intRange(1, MaxSafeInt)},
			{"keySeed", true, intRange(0, MaxSafeInt)},
			{"startsAt", true, intRange(0, MaxSafeInt)},
			{"deadline", true, intRange(0, MaxSafeInt)},
			{"waveIntervalMs", true, intRange(1, MaxSafeInt)},
		},
		check: prisonersDiffer,
	}, newPayload: func() Payload { return &MissionStart{} }},
	TMissionUpdate: {s2c: true, payload: objSchema{
		fields: []field{
			{"missionId", true, hexID},
			{"paused", true, boolean},
			{"remainingMs", true, intRange(0, MaxSafeInt)},
			{"tasks", false, object(objSchema{
				fields: []field{{"key", false, boolean}},
			})},
		},
	}, newPayload: func() Payload { return &MissionUpdate{} }},
	TMissionEnd: {s2c: true, payload: objSchema{
		fields: []field{
			{"missionId", true, hexID},
			{"result", true, enum(MissionResults...)},
			{"penalty", false, object(missionPenaltySchema)},
			{"reward", false, object(missionRewardSchema)},
		},
		check: chain(
			valueDep("result", "penalty", []string{"failed"}),
			valueDep("result", "reward", []string{"success"}),
		),
	}, newPayload: func() Payload { return &MissionEnd{} }},

	// ---- shared items (§5.7) ---------------------------------------------------

	TClaim: {c2s: true, floor: floorRequired, payload: objSchema{
		fields: []field{{"itemNetId", true, itemNet}},
	}, newPayload: func() Payload { return &Claim{} }},
	TClaimed: {s2c: true, payload: objSchema{
		fields: []field{
			{"itemNetId", true, itemNet},
			{"by", true, hexID},
		},
	}, newPayload: func() Payload { return &Claimed{} }},
	TClaimsReq: {c2s: true, payload: objSchema{
		fields: []field{
			{"depth", true, intRange(1, 30)},
			{"branch", true, intRange(0, 9)},
		},
	}, newPayload: func() Payload { return &ClaimsReq{} }},
	TClaims: {s2c: true, payload: objSchema{
		fields: []field{
			{"depth", true, intRange(1, 30)},
			{"branch", true, intRange(0, 9)},
			{"itemNetIds", true, array(0, 256, itemNet)},
		},
	}, newPayload: func() Payload { return &Claims{} }},
	TGive: {c2s: true, floor: floorRequired, payload: objSchema{
		fields: []field{
			{"itemNetId", true, itemNet},
			{"kind", true, enum(ItemKinds...)},
			{"item", false, opaqueBlob},
			{"toCell", false, intRange(0, MaxCell)},
		},
		check: valueDep("kind", "item", []string{"item"}),
	}, newPayload: func() Payload { return &Give{} }},
	TTransfer: {s2c: true, payload: objSchema{
		fields: []field{
			{"itemNetId", true, itemNet},
			{"from", true, hexID},
			{"to", true, hexID},
			{"kind", true, enum(ItemKinds...)},
			{"item", false, opaqueBlob},
		},
		check: valueDep("kind", "item", []string{"item"}),
	}, newPayload: func() Payload { return &Transfer{} }},
	TDropShare: {c2s: true, s2c: true, floor: floorRequired, payload: objSchema{
		fields: []field{
			{"cell", true, intRange(0, MaxCell)},
			{"itemNetId", true, itemNet},
			{"item", true, opaqueBlob},
		},
	}, newPayload: func() Payload { return &DropShare{} }},
	TDeny: {s2c: true, payload: objSchema{
		fields: []field{{"reason", true, enum(DenyReasons...)}},
	}, newPayload: func() Payload { return &Deny{} }},

	// ---- downed and revive (§5.8) ----------------------------------------------

	TDowned: {c2s: true, floor: floorRequired, payload: objSchema{
		fields: []field{
			{"cause", false, text(64)},
		},
	}, newPayload: func() Payload { return &Downed{} }},
	TAllyDowned: {s2c: true, payload: objSchema{
		fields: []field{
			{"player", true, hexID},
			{"deadline", true, intRange(0, MaxSafeInt)},
		},
	}, newPayload: func() Payload { return &AllyDowned{} }},
	TUseRecall: {c2s: true, floor: floorRequired, payload: objSchema{
		fields: []field{
			{"target", true, hexID},
			{"via", true, enum(ReviveMethods...)},
		},
	}, newPayload: func() Payload { return &UseRecall{} }},
	TRevive: {s2c: true, payload: objSchema{
		fields: []field{
			{"target", true, hexID},
			{"mode", true, enum(ReviveModes...)},
			{"hpPct", true, intRange(0, 100)},
		},
	}, newPayload: func() Payload { return &Revive{} }},
	TDownedExpired: {s2c: true, payload: objSchema{
		fields: []field{
			{"player", true, hexID},
		},
	}, newPayload: func() Payload { return &DownedExpired{} }},

	// ---- Super Sync (§5.9) ------------------------------------------------------

	TSyncDrink: {c2s: true, floor: floorRequired, payload: objSchema{
		fields: []field{
			{"snapshot", true, opaqueBlob},
			{"turns", true, intRange(1, MaxSafeInt)},
		},
	}, newPayload: func() Payload { return &SyncDrink{} }},
	TSyncStart: {s2c: true, payload: objSchema{
		fields: []field{
			{"syncId", true, hexID},
			{"snapshot", true, opaqueBlob},
			{"turns", true, intRange(1, MaxSafeInt)},
		},
	}, newPayload: func() Payload { return &SyncStart{} }},
	TSyncView: {c2s: true, s2c: true, floor: floorRequired, payload: objSchema{
		fields: []field{
			{"syncId", true, hexID},
			{"turn", true, intRange(1, MaxSafeInt)},
			{"terrainDiff", true, pairList(512, 0, MaxCell)},
			{"entities", true, array(0, 64, object(syncEntitySchema))},
		},
	}, newPayload: func() Payload { return &SyncView{} }},
	TSyncCmd: {c2s: true, s2c: true, floor: floorRequired, payload: objSchema{
		fields: []field{
			{"syncId", true, hexID},
			{"turn", true, intRange(1, MaxSafeInt)},
			{"action", true, enum(SyncActions...)},
			{"cell", false, intRange(0, MaxCell)},
		},
		check: valueDep("action", "cell", []string{"move", "attack"}),
	}, newPayload: func() Payload { return &SyncCmd{} }},
	TSyncReport: {c2s: true, floor: floorRequired, payload: objSchema{
		fields: []field{
			{"syncId", true, hexID},
			{"hpLost", true, intRange(0, MaxSafeInt)},
			{"reason", true, enum(ReportReasons...)},
		},
	}, newPayload: func() Payload { return &SyncReport{} }},
	TSyncEnd: {s2c: true, payload: objSchema{
		fields: []field{
			{"syncId", true, hexID},
			{"hpLost", true, intRange(0, MaxSafeInt)},
			{"reason", true, enum(ReportReasons...)},
		},
	}, newPayload: func() Payload { return &SyncEnd{} }},

	// ---- shared boss HP, twin doors, terrain (§5.10) -----------------------------

	TBossEngage: {c2s: true, floor: floorRequired, payload: objSchema{
		fields: []field{
			{"bossId", true, intRange(1, MaxSafeInt)},
			{"ht", true, intRange(1, MaxSafeInt)},
		},
	}, newPayload: func() Payload { return &BossEngage{} }},
	TBossHit: {c2s: true, floor: floorRequired, payload: objSchema{
		fields: []field{
			{"bossId", true, intRange(1, MaxSafeInt)},
			{"dmg", true, intRange(1, MaxSafeInt)},
		},
	}, newPayload: func() Payload { return &BossHit{} }},
	TBossHP: {s2c: true, payload: objSchema{
		fields: []field{
			{"bossId", true, intRange(1, MaxSafeInt)},
			{"hp", true, intRange(0, MaxSafeInt)},
			{"ht", true, intRange(1, MaxSafeInt)},
		},
	}, newPayload: func() Payload { return &BossHP{} }},
	TBossDead: {s2c: true, payload: objSchema{
		fields: []field{
			{"bossId", true, intRange(1, MaxSafeInt)},
		},
	}, newPayload: func() Payload { return &BossDead{} }},
	TTwinAct: {c2s: true, floor: floorRequired, payload: objSchema{
		fields: []field{
			{"objectId", true, intRange(1, MaxSafeInt)},
			{"kind", true, enum(TwinKinds...)},
			{"cell", true, intRange(0, MaxCell)},
			{"active", true, boolean},
			{"groupId", false, intRange(1, MaxSafeInt)},
		},
		check: valueDep("kind", "groupId", []string{"switch"}),
	}, newPayload: func() Payload { return &TwinAct{} }},
	TTwinDone: {s2c: true, payload: objSchema{
		fields: []field{
			{"groupId", true, intRange(1, MaxSafeInt)},
		},
	}, newPayload: func() Payload { return &TwinDone{} }},
	TTerrain: {c2s: true, s2c: true, floor: floorRequired, payload: objSchema{
		fields: []field{
			{"cell", true, intRange(0, MaxCell)},
			{"tile", true, intRange(0, 255)},
		},
	}, newPayload: func() Payload { return &Terrain{} }},
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
