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

import com.watabou.utils.Bundlable;
import com.watabou.utils.Bundle;

import java.util.ArrayDeque;

//what LINK-02 must tell the server but couldn't yet (offline, mid-reconnect): permanent terrain
// changes, in the order they happened, and the floor whose record we still have to ask for.
//The changes are saved with the run (CoopRun "coop_terrain_outbox"), so a change made offline
// survives closing the game; a change leaves only once it was sent. The request isn't saved: the
// next floor entry or reconnect asks again.
//Used on the GL thread (CoopLinks posts every use); synchronized because saving can happen on
// another thread.
public class TerrainOutbox implements Bundlable {

	//true when the floor request went out (the caller marks it in flight)
	interface Sender {
		boolean change( int depth, int branch, int cell, int tile );
		boolean request( int depth, int branch );
	}

	private final ArrayDeque<int[]> changes = new ArrayDeque<>();
	//a TERRAIN_REQ still to send: {depth, branch}, or null
	private int[] request;

	synchronized void add( int depth, int branch, int cell, int tile ) {
		changes.add( new int[]{ depth, branch, cell, tile } );
	}

	//asks for a floor's record; replaces an older request (only the floor we're on matters)
	synchronized void request( int depth, int branch ) {
		request = new int[]{ depth, branch };
	}

	//sends in order and stops at the first one that can't go out; the request goes after our own
	// changes, so the record we get back already has them. Returns the floor requested, or null.
	synchronized int[] flush( Sender sender ) {
		while (!changes.isEmpty()) {
			int[] c = changes.peek();
			if (!sender.change( c[0], c[1], c[2], c[3] )) return null;
			changes.poll();
		}
		if (request != null && sender.request( request[0], request[1] )) {
			int[] sent = request;
			request = null;
			return sent;
		}
		return null;
	}

	synchronized int pending() {
		return changes.size() + (request == null ? 0 : 1);
	}

	private static final String CHANGES = "changes";

	@Override
	public synchronized void storeInBundle( Bundle bundle ) {
		int[] flat = new int[changes.size() * 4];
		int i = 0;
		for (int[] c : changes) {
			System.arraycopy( c, 0, flat, i, 4 );
			i += 4;
		}
		bundle.put( CHANGES, flat );
	}

	@Override
	public synchronized void restoreFromBundle( Bundle bundle ) {
		changes.clear();
		request = null;
		if (!bundle.contains( CHANGES )) return;
		int[] flat = bundle.getIntArray( CHANGES );
		for (int i = 0; i + 3 < flat.length; i += 4) {
			changes.add( new int[]{ flat[i], flat[i + 1], flat[i + 2], flat[i + 3] } );
		}
	}
}
