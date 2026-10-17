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

package com.shatteredpixel.shatteredpixeldungeon.coop;

import com.watabou.utils.Bundlable;
import com.watabou.utils.Bundle;

//the co-op part of a run, saved inside the run's own bundle (key "coop_run"). Its presence is
// what makes a run a co-op run (AGENTS.md rule 5): the socket state never decides that.
//resumeToken is a secret (PROTOCOL.md §3): it lives only here and is never logged.
public class CoopRun implements Bundlable {

	public String runId = "";
	public long seed;
	public long roleSeed;
	//0 = room creator (role A at run start), 1 = joiner (role B)
	public int slot;
	public String heroClass = "";
	public String roomCode = "";
	public String serverUrl = "";
	//the co-op save slot this run lives in (Coop.isCoopSlot)
	public int saveSlot;

	public String playerId = "";
	public String resumeToken = "";
	public String epoch = "";
	public long ack;

	private static final String RUN_ID = "coop_run_id";
	private static final String SEED = "coop_seed";
	private static final String ROLE_SEED = "coop_role_seed";
	private static final String SLOT = "coop_slot";
	private static final String HERO = "coop_hero";
	private static final String ROOM = "coop_room";
	private static final String SERVER = "coop_server";
	private static final String SAVE_SLOT = "coop_save_slot";
	private static final String PLAYER = "coop_player";
	private static final String TOKEN = "coop_token";
	private static final String EPOCH = "coop_epoch";
	private static final String ACK = "coop_ack";

	@Override
	public void storeInBundle( Bundle bundle ) {
		bundle.put( RUN_ID, runId );
		bundle.put( SEED, seed );
		bundle.put( ROLE_SEED, roleSeed );
		bundle.put( SLOT, slot );
		bundle.put( HERO, heroClass );
		bundle.put( ROOM, roomCode );
		bundle.put( SERVER, serverUrl );
		bundle.put( SAVE_SLOT, saveSlot );
		bundle.put( PLAYER, playerId );
		bundle.put( TOKEN, resumeToken );
		bundle.put( EPOCH, epoch );
		bundle.put( ACK, ack );
	}

	@Override
	public void restoreFromBundle( Bundle bundle ) {
		runId = bundle.getString( RUN_ID );
		seed = bundle.getLong( SEED );
		roleSeed = bundle.getLong( ROLE_SEED );
		slot = bundle.getInt( SLOT );
		heroClass = bundle.getString( HERO );
		roomCode = bundle.getString( ROOM );
		serverUrl = bundle.getString( SERVER );
		saveSlot = bundle.getInt( SAVE_SLOT );
		playerId = bundle.getString( PLAYER );
		resumeToken = bundle.getString( TOKEN );
		epoch = bundle.getString( EPOCH );
		ack = bundle.getLong( ACK );
	}

	//"" in the bundle means "none" for the protocol
	static String orNull( String s ) {
		return s == null || s.isEmpty() ? null : s;
	}
}
