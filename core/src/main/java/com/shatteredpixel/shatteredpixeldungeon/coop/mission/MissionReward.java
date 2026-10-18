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

//MISSION_END{success}: what both players get (game-design §4.6). MEC-01 installs the Cell's
// reward with CoopMissions.reward(...). Called on the GL thread while the hero waits for input,
// exactly once per mission; both clients roll it from end.rewardSeed, never from gameplay Random.
public interface MissionReward {

	MissionReward NONE = new MissionReward() {
		@Override
		public void grant( MissionState.End end ) {}
	};

	void grant( MissionState.End end );
}
