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

package lobby

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"math/big"

	"github.com/omarmaarouf18/spd-coop/server/internal/protocol"
)

// All randomness comes from crypto/rand (PROTOCOL.md §6: seeds from a CSPRNG).

// newHexID returns 16 lowercase hex chars (playerId, runId, epoch).
func newHexID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b) // crypto/rand.Read never fails (it panics instead)
	return hex.EncodeToString(b)
}

// newToken returns a resume token (32 random bytes, base64url without
// padding: 43 chars) and its SHA-256 hash, the only form that is stored.
func newToken() (string, []byte) {
	b := make([]byte, 32)
	_, _ = rand.Read(b)
	tok := base64.RawURLEncoding.EncodeToString(b)
	return tok, hashToken(tok)
}

func hashToken(tok string) []byte {
	h := sha256.Sum256([]byte(tok))
	return h[:]
}

// newRoomCode returns a 6-char code from protocol.RoomCodeAlphabet.
func newRoomCode() string {
	alphabet := protocol.RoomCodeAlphabet
	b := make([]byte, protocol.RoomCodeLen)
	for i := range b {
		b[i] = alphabet[randBelow(int64(len(alphabet)))]
	}
	return string(b)
}

// newSeed returns a uniform SPD seed: 0 <= seed < 26^9 (DungeonSeed.TOTAL_SEEDS).
func newSeed() int64 { return randBelow(protocol.MaxSeedExclusive) }

// newRoleSeed returns a uniform 0 <= roleSeed < 2^53.
func newRoleSeed() int64 { return randBelow(protocol.MaxSafeInt + 1) }

func randBelow(n int64) int64 {
	v, err := rand.Int(rand.Reader, big.NewInt(n))
	if err != nil {
		panic(err) // crypto/rand failing is unrecoverable
	}
	return v.Int64()
}
