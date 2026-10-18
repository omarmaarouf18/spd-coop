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

package com.shatteredpixel.shatteredpixeldungeon.coop.items;

import com.watabou.utils.Bundlable;
import com.watabou.utils.Bundle;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

//what one player knows about the shared items of a co-op run (ITM-01), saved in CoopRun
// ("coop_items"). Ordered collections only: nothing here may depend on hash order.
public class CoopItemsState implements Bundlable {

	//an optimistic pickup the server hasn't confirmed yet (game-design §6A.1)
	public static final class Pending {
		public final String id;
		public final int depth, branch;
		//to take a merged stack or gold back when the instance is gone
		public final String cls;
		public final int quantity;

		public Pending( String id, int depth, int branch, String cls, int quantity ) {
			this.id = id;
			this.depth = depth;
			this.branch = branch;
			this.cls = cls;
			this.quantity = quantity;
		}

		String encode() {
			return id + "|" + depth + "|" + branch + "|" + cls + "|" + quantity;
		}

		static Pending decode( String s ) {
			String[] f = s.split( "\\|" );
			return new Pending( f[0], Integer.parseInt( f[1] ), Integer.parseInt( f[2] ), f[3], Integer.parseInt( f[4] ) );
		}
	}

	//one CLAIMS_REQ on the wire, optionally right after the CLAIM it brackets (see CoopItems)
	static final class Request {
		final int depth, branch;
		final String claim;
		final long sentAt;
		//got its direct reply (CLAIMED by us, or DENY)
		boolean replied;

		Request( int depth, int branch, String claim, long sentAt ) {
			this.depth = depth;
			this.branch = branch;
			this.claim = claim;
			this.sentAt = sentAt;
		}
	}

	//last runtime counter used in "<playerId>:<counter>" ids
	public long counter;
	public final LinkedHashMap<String, Pending> pending = new LinkedHashMap<>();
	//confirmed ours by the server
	public final LinkedHashSet<String> mine = new LinkedHashSet<>();
	//claimed by the ally: gone from our world
	public final LinkedHashSet<String> allyTaken = new LinkedHashSet<>();
	//DROP_SHARE / TRANSFER ids already spawned here (the server replays reliable events)
	public final LinkedHashSet<String> received = new LinkedHashSet<>();
	//a run saved before ITM-01: its items carry no ids, so loaded floors are rebound once from
	// the DET-01 table (floor keys "depth_branch" already done)
	public boolean legacy;
	public final LinkedHashSet<String> migrated = new LinkedHashSet<>();

	//not saved: the requests of the current connection, oldest first
	final ArrayDeque<Request> wire = new ArrayDeque<>();

	private static final String COUNTER = "coop_items_counter";
	private static final String PENDING = "coop_items_pending";
	private static final String MINE = "coop_items_mine";
	private static final String ALLY = "coop_items_ally";
	private static final String RECEIVED = "coop_items_received";
	private static final String LEGACY = "coop_items_legacy";
	private static final String MIGRATED = "coop_items_migrated";

	@Override
	public void storeInBundle( Bundle bundle ) {
		bundle.put( COUNTER, counter );
		String[] p = new String[pending.size()];
		int i = 0;
		for (Pending e : pending.values()) p[i++] = e.encode();
		bundle.put( PENDING, p );
		bundle.put( MINE, mine.toArray( new String[0] ) );
		bundle.put( ALLY, allyTaken.toArray( new String[0] ) );
		bundle.put( RECEIVED, received.toArray( new String[0] ) );
		bundle.put( LEGACY, legacy );
		bundle.put( MIGRATED, migrated.toArray( new String[0] ) );
	}

	@Override
	public void restoreFromBundle( Bundle bundle ) {
		counter = bundle.getLong( COUNTER );
		pending.clear();
		if (bundle.contains( PENDING )) {
			for (String s : bundle.getStringArray( PENDING )) {
				Pending e = Pending.decode( s );
				pending.put( e.id, e );
			}
		}
		restore( bundle, MINE, mine );
		restore( bundle, ALLY, allyTaken );
		restore( bundle, RECEIVED, received );
		legacy = bundle.getBoolean( LEGACY );
		restore( bundle, MIGRATED, migrated );
	}

	private static void restore( Bundle bundle, String key, LinkedHashSet<String> into ) {
		into.clear();
		if (bundle.contains( key )) into.addAll( Arrays.asList( bundle.getStringArray( key ) ) );
	}
}
