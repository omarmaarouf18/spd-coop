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

package com.shatteredpixel.shatteredpixeldungeon.coop.links;

import java.util.ArrayDeque;

//what LINK-02 must tell the server but couldn't yet (offline, mid-reconnect): permanent terrain
// changes, in the order they happened, and the last floor whose record we still have to ask for.
//GL thread only (CoopLinks posts every use). In memory: a change made offline is lost if the game
// is closed before the connection comes back (see the LINK-02 worklog).
final class TerrainOutbox {

	private static final class Change {
		final int depth, branch, cell, tile;

		Change( int depth, int branch, int cell, int tile ) {
			this.depth = depth;
			this.branch = branch;
			this.cell = cell;
			this.tile = tile;
		}
	}

	private final ArrayDeque<Change> changes = new ArrayDeque<>();
	//a TERRAIN_REQ still to send: {depth, branch}, or null
	private int[] request;

	void add( CoopLinks.Link link, int depth, int branch, int cell, int tile ) {
		changes.add( new Change( depth, branch, cell, tile ) );
		flush( link );
	}

	//asks for a floor's record; replaces an older request (only the floor we're on matters)
	void request( CoopLinks.Link link, int depth, int branch ) {
		request = new int[]{ depth, branch };
		flush( link );
	}

	//sends in order and stops at the first one that can't go out
	void flush( CoopLinks.Link link ) {
		while (!changes.isEmpty()) {
			Change c = changes.peek();
			if (!link.terrainPersist( c.depth, c.branch, c.cell, c.tile )) return;
			changes.poll();
		}
		//after our own changes, so the record we get back already has them
		if (request != null && link.terrainReq( request[0], request[1] )) request = null;
	}

	int pending() {
		return changes.size() + (request == null ? 0 : 1);
	}

	void clear() {
		changes.clear();
		request = null;
	}
}
