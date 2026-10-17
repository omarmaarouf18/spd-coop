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

package com.shatteredpixel.shatteredpixeldungeon.coop.ghost;

//what we know about the ally: the last GHOST (kept across level changes, save/load and
// reconnects, so the ghost comes back where it was) and the connection state.
//Pure data, GL thread only. Nothing here is gameplay state: it is never saved with the run.
public final class AllyState {

	//the ally's floor relative to ours (game-design §2.1)
	public enum Floor {
		//no GHOST/MARK received yet
		UNKNOWN,
		SAME,
		//deeper than us: "↓N"
		BELOW,
		//shallower: "↑N"
		ABOVE,
		//same depth, another branch (quest floors)
		OTHER_BRANCH
	}

	private HeroSnapshot ghost;
	private long ghostAt;
	//the ally's floor from the newest GHOST or MARK (a MARK can be newer than the last GHOST)
	private int depth = -1, branch;

	//from ALLY_STATUS (null until one arrives) or the room snapshot, whichever came last
	private Boolean connected;
	private long lastSeen;

	public void onGhost( HeroSnapshot s, long now ) {
		ghost = s;
		ghostAt = now;
		depth = s.depth;
		branch = s.branch;
		//a GHOST is proof the ally is online right now
		if (connected == null || !connected) {
			connected = true;
		}
		lastSeen = now;
	}

	public void onFloor( int d, int b, long now ) {
		depth = d;
		branch = b;
		lastSeen = now;
	}

	public void onStatus( boolean isConnected, long lastSeenAt ) {
		connected = isConnected;
		if (lastSeenAt > 0) lastSeen = lastSeenAt;
	}

	public void clear() {
		ghost = null;
		ghostAt = 0;
		depth = -1;
		branch = 0;
		connected = null;
		lastSeen = 0;
	}

	public HeroSnapshot ghost() {
		return ghost;
	}

	public long ghostAt() {
		return ghostAt;
	}

	public boolean known() {
		return depth > 0;
	}

	public int depth() {
		return depth;
	}

	public int branch() {
		return branch;
	}

	//null: no status yet
	public Boolean connected() {
		return connected;
	}

	public boolean online() {
		return connected != null && connected;
	}

	public long lastSeen() {
		return lastSeen;
	}

	public Floor floor( int myDepth, int myBranch ) {
		if (!known()) return Floor.UNKNOWN;
		if (depth > myDepth) return Floor.BELOW;
		if (depth < myDepth) return Floor.ABOVE;
		return branch == myBranch ? Floor.SAME : Floor.OTHER_BRANCH;
	}

	//how many floors away (0 on the same depth)
	public int floorsAway( int myDepth ) {
		return known() ? Math.abs( depth - myDepth ) : 0;
	}
}
