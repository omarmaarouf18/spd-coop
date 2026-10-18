/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Oleg Dolya
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
 *
 * Logic Link PD — Copyright (C) 2026 Logic Link PD contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package com.shatteredpixel.shatteredpixeldungeon.coop.mission;

import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Direction;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Message;

//s2c mission/downed/sync frames as the server sends them, decoded by the real protocol decoder
// (so every test message is also valid per PROTOCOL.md §5.6–§5.9)
final class MissionWire {

	static final String ME = "a1b2c3d4e5f60718";
	static final String ALLY = "b2c3d4e5f6071829";
	static final String M1 = "0123456789abcdef";
	static final String M2 = "fedcba9876543210";
	static final String SYNC = "00112233445566aa";

	private static long seq = 100;

	private MissionWire() {}

	static Message s2c( String t, String payload ) {
		try {
			return Protocol.decode( Direction.S2C, "{\"v\":1,\"t\":\"" + t + "\",\"seq\":" + (++seq) + ",\"p\":" + payload + "}" );
		} catch (Protocol.DecodeError e) {
			throw new AssertionError( t + " " + payload + ": " + e.getMessage(), e );
		}
	}

	static Message start( String id, String prisoner, String rescuer, long startsAt, long deadline ) {
		return s2c( Protocol.MISSION_START, "{\"missionId\":\"" + id + "\",\"kind\":\"CELL\",\"prisoner\":\"" + prisoner
				+ "\",\"rescuer\":\"" + rescuer + "\",\"depth\":7,\"branch\":0,\"trapCell\":345,\"roomId\":12,"
				+ "\"keySeed\":99,\"startsAt\":" + startsAt + ",\"deadline\":" + deadline + ",\"waveIntervalMs\":30000}" );
	}

	static Message update( String id, boolean paused, long remainingMs, String tasksJson ) {
		return s2c( Protocol.MISSION_UPDATE, "{\"missionId\":\"" + id + "\",\"paused\":" + paused + ",\"remainingMs\":" + remainingMs
				+ (tasksJson == null ? "" : ",\"tasks\":" + tasksJson) + "}" );
	}

	static Message failed( String id, int regionStart, int goldPct ) {
		return s2c( Protocol.MISSION_END, "{\"missionId\":\"" + id + "\",\"result\":\"failed\",\"penalty\":{\"regionStartDepth\":"
				+ regionStart + ",\"goldPct\":" + goldPct + "}}" );
	}

	static Message success( String id, long rewardSeed ) {
		return s2c( Protocol.MISSION_END, "{\"missionId\":\"" + id + "\",\"result\":\"success\",\"reward\":{\"rewardSeed\":" + rewardSeed + "}}" );
	}

	static Message ended( String id, String result ) {
		return s2c( Protocol.MISSION_END, "{\"missionId\":\"" + id + "\",\"result\":\"" + result + "\"}" );
	}

	static Message allyDowned( String player, long deadline ) {
		return s2c( Protocol.ALLY_DOWNED, "{\"player\":\"" + player + "\",\"deadline\":" + deadline + "}" );
	}

	static Message revive( String target ) {
		return s2c( Protocol.REVIVE, "{\"target\":\"" + target + "\",\"mode\":\"inPlace\",\"hpPct\":25}" );
	}

	static Message downedExpired( String player ) {
		return s2c( Protocol.DOWNED_EXPIRED, "{\"player\":\"" + player + "\"}" );
	}

	static Message syncStart( String id ) {
		return s2c( Protocol.SYNC_START, "{\"syncId\":\"" + id + "\",\"snapshot\":\"opaque-stats\",\"turns\":15}" );
	}

	static Message syncEnd( String id ) {
		return s2c( Protocol.SYNC_END, "{\"syncId\":\"" + id + "\",\"hpLost\":4,\"reason\":\"expired\"}" );
	}
}
