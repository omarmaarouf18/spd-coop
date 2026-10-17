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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

//pings (MARK, game-design §3): markers that show for LIFETIME_MS in both worlds, same floor only.
//Pure logic in wall-clock ms, GL thread only.
public final class Marks {

	public static final long LIFETIME_MS = 5_000;
	//local rate limit: one ping per second
	public static final long LOCAL_INTERVAL_MS = 1_000;
	//a newer ping on the same cell replaces the old one; this many at most at once
	public static final int MAX_ACTIVE = 8;

	public enum Kind {
		HERE, DANGER, ITEM, GO;

		//PROTOCOL.md §3 "mark kind"
		public String wire() {
			return name();
		}

		public static Kind fromWire( String s ) {
			for (Kind k : values()) {
				if (k.name().equals( s )) return k;
			}
			return null;
		}
	}

	public static final class Mark {
		public final int depth, branch, cell;
		public final Kind kind;
		public final boolean fromAlly;
		public final long createdAt;

		Mark( int depth, int branch, int cell, Kind kind, boolean fromAlly, long createdAt ) {
			this.depth = depth;
			this.branch = branch;
			this.cell = cell;
			this.kind = kind;
			this.fromAlly = fromAlly;
			this.createdAt = createdAt;
		}

		public float remaining( long now ) {
			return Math.max( 0f, (createdAt + LIFETIME_MS - now) / (float) LIFETIME_MS );
		}

		public boolean expired( long now ) {
			return now - createdAt >= LIFETIME_MS;
		}
	}

	private final List<Mark> active = new ArrayList<>();
	private long lastLocal = Long.MIN_VALUE / 2;

	//our own ping: null when rate-limited
	public Mark placeLocal( int depth, int branch, int cell, Kind kind, long now ) {
		if (now - lastLocal < LOCAL_INTERVAL_MS) return null;
		lastLocal = now;
		return add( new Mark( depth, branch, cell, kind, false, now ) );
	}

	public boolean canPlaceLocal( long now ) {
		return now - lastLocal >= LOCAL_INTERVAL_MS;
	}

	//the ally's ping: ignored (null) unless it is for our floor (PROTOCOL.md §5.3)
	public Mark receive( int depth, int branch, int cell, Kind kind, int myDepth, int myBranch, long now ) {
		if (kind == null || depth != myDepth || branch != myBranch) return null;
		return add( new Mark( depth, branch, cell, kind, true, now ) );
	}

	//the marks to draw on this floor now; expired ones are dropped
	public List<Mark> active( int myDepth, int myBranch, long now ) {
		prune( now );
		List<Mark> out = new ArrayList<>();
		for (Mark m : active) {
			if (m.depth == myDepth && m.branch == myBranch) out.add( m );
		}
		return Collections.unmodifiableList( out );
	}

	public void prune( long now ) {
		for (int i = active.size() - 1; i >= 0; i--) {
			if (active.get( i ).expired( now )) active.remove( i );
		}
	}

	public void clear() {
		active.clear();
		lastLocal = Long.MIN_VALUE / 2;
	}

	private Mark add( Mark m ) {
		for (int i = active.size() - 1; i >= 0; i--) {
			Mark o = active.get( i );
			if (o.cell == m.cell && o.depth == m.depth && o.branch == m.branch && o.fromAlly == m.fromAlly) {
				active.remove( i );
			}
		}
		active.add( m );
		while (active.size() > MAX_ACTIVE) active.remove( 0 );
		return m;
	}
}
