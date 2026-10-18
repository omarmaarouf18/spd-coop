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

//what the mechanics built on the framework (MEC-01 The Cell, MEC-02 Downed, SYN-01 Super Sync)
// hear about, via CoopMissions.addListener(). Called on the GL thread, after MissionState has
// applied the message, and only for messages it accepted (never twice for a replay).
//Penalty and reward are not done here: CoopMissions applies them later, once the hero can act
// (MissionReward for success, the region-start return for failure).
public interface MissionListener {

	default void onMissionStart( MissionState.Mission mission ) {}

	//paused/remaining/tasks changed
	default void onMissionUpdate( MissionState.Mission mission ) {}

	//the mission is over (any result); state already cleared. Stop waves, unlock doors here.
	default void onMissionEnd( MissionState.End end ) {}

	default void onAllyDowned( String playerId, long deadline ) {}

	//mode: "inPlace" | "regionStart"
	default void onRevive( String targetId, String mode, int hpPct ) {}

	//the player's Downed window ran out: their client plays the real death (MEC-02)
	default void onDownedExpired( String playerId ) {}

	default void onSyncStart( MissionState.Sync sync ) {}

	default void onSyncEnd( String syncId, long hpLost, String reason ) {}
}
