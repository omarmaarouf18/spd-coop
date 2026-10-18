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

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroAction;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mimic;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopConnection;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopRun;
import com.shatteredpixel.shatteredpixeldungeon.coop.ghost.AllyState;
import com.shatteredpixel.shatteredpixeldungeon.coop.ghost.CoopGhosts;
import com.shatteredpixel.shatteredpixeldungeon.coop.items.CoopItemsState.Pending;
import com.shatteredpixel.shatteredpixeldungeon.coop.items.CoopItemsState.Request;
import com.shatteredpixel.shatteredpixeldungeon.coop.levelgen.CoopItemIds;
import com.shatteredpixel.shatteredpixeldungeon.coop.levelgen.CoopLevelGen;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopSession;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Message;
import com.shatteredpixel.shatteredpixeldungeon.effects.CellEmitter;
import com.shatteredpixel.shatteredpixeldungeon.effects.particles.SmokeParticle;
import com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem;
import com.shatteredpixel.shatteredpixeldungeon.items.Gold;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.Key;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;
import com.watabou.utils.Reflection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

//shared floor loot and drop-to-ally (ITM-01, game-design §6A.1–§6A.2, PROTOCOL.md §5.7).
//Every upstream hook calls one method here, and each is a no-op outside co-op runs.
//
//Ids: every shared item carries its itemNetId in its own bundle ("coop_net_id"), so the id
// survives pickup, drop, throw, save/load and mob death. Level-gen items get DET-01's
// "depth:branch:genIndex"; an item we drop to the ally gets a fresh "<playerId>:<counter>".
// Per-player items (DET-02) are marked LOCAL: never claimed, never shared.
//
//Claims are optimistic: the pickup happens now, the CLAIM goes out now (or on the next flush
// when offline), and the server's verdict can take the item back. DENY{taken} names no item, so
// every CLAIM is followed by a CLAIMS_REQ for its floor: the server answers a connection in
// order, so the reply stream per claim is [CLAIMED|DENY|nothing] then CLAIMS, and CLAIMS closes
// the bracket ("nothing" = re-claiming our own, which the server ignores silently).
public final class CoopItems {

	//DET-02's per-player items
	public static final String LOCAL = "local";

	static final String BUNDLE_KEY = "coop_net_id";
	//a request with no answer for this long is assumed lost with its connection
	static final long STALE_MS = 15_000;

	//the network and the GL thread, replaceable in tests
	public interface Link {
		//our playerId, or null before the first WELCOME
		String me();
		boolean running();
		boolean allyOn( int depth, int branch );
		boolean claim( int depth, int branch, String itemNetId );
		boolean claimsReq( int depth, int branch );
		boolean dropShare( int depth, int branch, int cell, String itemNetId, String item );
		long now();
		//runs on the GL thread (AGENTS.md rule 20)
		void post( Runnable r );
	}

	public static final Link LIVE = new Link() {
		@Override
		public String me() {
			CoopRun run = Coop.run();
			if (run != null && !run.playerId.isEmpty()) return run.playerId;
			return CoopConnection.playerId();
		}

		@Override
		public boolean running() {
			return session() != null;
		}

		@Override
		public boolean allyOn( int depth, int branch ) {
			AllyState ally = CoopGhosts.ally();
			return running() && ally.online() && ally.floor( depth, branch ) == AllyState.Floor.SAME;
		}

		@Override
		public boolean claim( int depth, int branch, String itemNetId ) {
			CoopSession s = session();
			return s != null && s.claim( depth, branch, itemNetId );
		}

		@Override
		public boolean claimsReq( int depth, int branch ) {
			CoopSession s = session();
			return s != null && s.claimsReq( depth, branch );
		}

		@Override
		public boolean dropShare( int depth, int branch, int cell, String itemNetId, String item ) {
			CoopSession s = session();
			return s != null && s.dropShare( depth, branch, cell, itemNetId, item );
		}

		@Override
		public long now() {
			return System.currentTimeMillis();
		}

		@Override
		public void post( Runnable r ) {
			Game.runOnRenderThread( r::run );
		}

		private CoopSession session() {
			CoopSession s = CoopConnection.session();
			return s != null && s.state() == CoopSession.State.RUNNING ? s : null;
		}
	};

	private static Link link = LIVE;

	//ids restored from item bundles or minted at runtime; level-gen ids come from CoopItemIds
	private static final Map<Item, String> ids = Collections.synchronizedMap( new WeakHashMap<>() );
	//quantities of shared floor items as last seen on the floor: picking up a stackable merges it
	// into our stack first (Item.merge zeroes it), so Heap.pickUp alone can't tell how many we took
	private static final Map<Item, Integer> seen = Collections.synchronizedMap( new WeakHashMap<>() );

	private CoopItems() {}

	public static void link( Link l ) {
		link = l == null ? LIVE : l;
	}

	// ---- ids ----

	public static String idOf( Item item ) {
		if (item == null) return null;
		String id = ids.get( item );
		return id != null ? id : CoopItemIds.idOf( item );
	}

	//tests and level-gen-like spawns
	static void bind( Item item, String id ) {
		ids.put( item, id );
	}

	public static void markLocal( Item item ) {
		if (item != null) ids.put( item, LOCAL );
	}

	static boolean shared( String id ) {
		return id != null && !LOCAL.equals( id );
	}

	//Item.storeInBundle()
	public static void storeId( Item item, Bundle bundle ) {
		if (!Coop.isCoopRun()) return;
		String id = idOf( item );
		if (id != null) bundle.put( BUNDLE_KEY, id );
	}

	//Item.restoreFromBundle(): only co-op saves carry the key (a split/duplicate copies it too)
	public static void restoreId( Item item, Bundle bundle ) {
		if (bundle.contains( BUNDLE_KEY )) ids.put( item, bundle.getString( BUNDLE_KEY ) );
	}

	// ---- upstream hooks ----

	//Heap.pickUp(): the item just left the heap. Claimed only when our hero took it (traps, wells
	// and the like move heap items around through pickUp too). Shops stay per world.
	public static void onPickUp( Heap heap, Item item ) {
		if (!Coop.isCoopRun() || item == null || heap.type == Heap.Type.FOR_SALE) return;
		Hero hero = Dungeon.hero;
		if (hero == null || !tookIt( hero, heap, item )) return;
		claim( item, Dungeon.depth, Dungeon.branch );
	}

	private static boolean tookIt( Hero hero, Heap heap, Item item ) {
		if (hero.curAction instanceof HeroAction.PickUp
				&& hero.curAction.dst == heap.pos && hero.pos == heap.pos) {
			return true;
		}
		//telekinetic grab
		return hero.belongings.contains( item );
	}

	//Item.doDrop() / Item.onThrow(): the item may now lie on the floor at (or near) cell.
	// With the ally online on this floor it also appears in their world (DROP_SHARE).
	public static synchronized void onDropped( Item item, int cell ) {
		if (!Coop.isCoopRun() || item == null || Dungeon.level == null) return;
		CoopItemsState s = state();
		//the item may have merged into a shared stack lying there
		remember( Dungeon.level.heaps.get( cell ) );
		Heap heap = heapOf( Dungeon.level, item, cell );
		//fell into a chasm, merged into a stack, or used up on landing
		if (s == null || heap == null || heap.type == Heap.Type.FOR_SALE) return;

		String old = idOf( item );
		//per-player, or a claim the server hasn't confirmed yet: it stays ours alone
		if (LOCAL.equals( old ) || (old != null && s.pending.containsKey( old ))) return;
		final int depth = Dungeon.depth, branch = Dungeon.branch;
		String me = link.me();
		if (me == null || !link.allyOn( depth, branch )) return;

		final String id = me + ":" + (s.counter + 1);
		ids.put( item, id );
		final String text = ItemCodec.encode( item );
		if (text == null) {
			if (old != null) ids.put( item, old ); else ids.remove( item );
			return;
		}
		s.counter++;
		final int at = heap.pos;
		link.post( () -> link.dropShare( depth, branch, at, id, text ) );
	}

	//Dungeon.switchLevel(): a floor was entered (new, loaded or revisited). Items the ally took
	// go away now; the server's full list follows (CLAIMS_REQ), and unsent claims go out.
	public static synchronized void onLevelEntered( Level level ) {
		if (!Coop.isCoopRun() || level == null) return;
		CoopItemsState s = state();
		if (s == null) return;
		final int depth = Dungeon.depth, branch = Dungeon.branch;

		String key = depth + "_" + branch;
		if (s.legacy && !s.migrated.contains( key )) {
			CoopItemIds.rebind( level, CoopLevelGen.itemIdTable( Coop.run().levelGen, depth, branch ), depth, branch );
			s.migrated.add( key );
		}
		removeFromFloor( level, s.allyTaken, false );
		for (Heap heap : level.heaps.valueList()) remember( heap );
		for (Mob mob : level.mobs) {
			if (mob instanceof Mimic && ((Mimic) mob).items != null) {
				for (Item item : ((Mimic) mob).items) remember( item );
			}
		}
		link.post( () -> {
			requestFloor( depth, branch );
			flush();
		} );
	}

	// ---- claims (pickup side) ----

	static synchronized void claim( Item item, int depth, int branch ) {
		String id = idOf( item );
		CoopItemsState s = state();
		//keys open doors of one world only
		if (s == null || !shared( id ) || item instanceof Key) return;
		if (s.mine.contains( id ) || s.pending.containsKey( id )) return;

		//0: merged into our stack and never seen on the floor (then a lost claim takes nothing back)
		Integer last = seen.get( item );
		int quantity = item.quantity() > 0 ? item.quantity() : last == null ? 0 : last;
		final Pending p = new Pending( id, depth, branch, item.getClass().getName(), quantity );
		if (s.allyTaken.contains( id )) {
			//e.g. a Mimic's loot the ally already took in their world
			link.post( () -> lose( p ) );
			return;
		}
		s.pending.put( id, p );
		link.post( CoopItems::flush );
	}

	//GL thread: sends every claim that isn't on the wire yet, each bracketed by a CLAIMS_REQ
	public static synchronized void flush() {
		CoopItemsState s = state();
		if (s == null || !link.running()) return;
		long now = link.now();
		while (!s.wire.isEmpty() && now - s.wire.peek().sentAt > STALE_MS) {
			s.wire.poll();
		}
		for (Pending p : new ArrayList<>( s.pending.values() )) {
			if (onWire( s, p.id )) continue;
			if (!link.claim( p.depth, p.branch, p.id )) return;
			s.wire.add( new Request( p.depth, p.branch, p.id, now ) );
			if (!link.claimsReq( p.depth, p.branch )) return;
		}
	}

	private static void requestFloor( int depth, int branch ) {
		CoopItemsState s = state();
		if (s != null && link.running() && link.claimsReq( depth, branch )) {
			s.wire.add( new Request( depth, branch, null, link.now() ) );
		}
	}

	private static boolean onWire( CoopItemsState s, String id ) {
		for (Request r : s.wire) {
			if (id.equals( r.claim )) return true;
		}
		return false;
	}

	// ---- server events (CoopConnection listener, GL thread) ----

	public static synchronized void onServerEvent( Message m ) {
		if (!Coop.isCoopRun() || state() == null) return;
		switch (m.t) {
			case Protocol.CLAIMED:
				onClaimed( m.str( "itemNetId" ), m.str( "by" ) );
				break;
			case Protocol.CLAIMS:
				onClaims( (int) m.num( "depth" ), (int) m.num( "branch" ), strings( m.p.get( "itemNetIds" ) ) );
				break;
			case Protocol.DENY:
				//an itemNetId is not in the v1 payload; a server may add it as an optional field (§8)
				if ("taken".equals( m.str( "reason" ) )) onDenied( m.str( "itemNetId" ) );
				break;
			case Protocol.DROP_SHARE:
				onDropShare( m );
				break;
			case Protocol.TRANSFER:
				onTransfer( m );
				break;
			default:
				break;
		}
	}

	private static void onClaimed( String id, String by ) {
		CoopItemsState s = state();
		if (id == null || by == null) return;
		if (by.equals( link.me() )) {
			//the direct reply to our CLAIM (the ally's CLAIMED is not: our DENY still follows it)
			replied( s, id );
			s.pending.remove( id );
			s.mine.add( id );
			return;
		}
		s.allyTaken.add( id );
		Pending p = s.pending.remove( id );
		if (p != null) {
			lose( p );
		} else if (Dungeon.level != null) {
			removeFromFloor( Dungeon.level, Collections.singleton( id ), true );
		}
	}

	private static void onDenied( String id ) {
		CoopItemsState s = state();
		//replies come in request order: the oldest bracket without a reply is the one answered
		Request r = null;
		for (Request w : s.wire) {
			if (w.claim != null && !w.replied && (id == null || id.equals( w.claim ))) {
				r = w;
				break;
			}
		}
		if (r == null) return;
		r.replied = true;
		s.allyTaken.add( r.claim );
		Pending p = s.pending.remove( r.claim );
		if (p != null) lose( p );
	}

	private static void onClaims( int depth, int branch, List<String> claimed ) {
		CoopItemsState s = state();
		Request r = null;
		for (Request w : s.wire) {
			if (w.depth == depth && w.branch == branch) {
				r = w;
				break;
			}
		}
		if (r != null) {
			s.wire.remove( r );
			//no CLAIMED and no DENY before the bracket closed: the server already had it as ours
			if (r.claim != null && !r.replied && s.pending.containsKey( r.claim ) && claimed.contains( r.claim )) {
				s.pending.remove( r.claim );
				s.mine.add( r.claim );
			}
		}
		for (String id : claimed) {
			if (!s.mine.contains( id ) && !s.pending.containsKey( id )) s.allyTaken.add( id );
		}
		if (Dungeon.level != null && Dungeon.depth == depth && Dungeon.branch == branch) {
			removeFromFloor( Dungeon.level, s.allyTaken, true );
		}
		//anything that fell off the wire (not recorded by the server) goes out again
		flush();
	}

	private static void replied( CoopItemsState s, String id ) {
		for (Request w : s.wire) {
			if (id.equals( w.claim )) w.replied = true;
		}
	}

	//the ally dropped an item on our floor: it appears here too, claimable once
	private static void onDropShare( Message m ) {
		CoopItemsState s = state();
		String id = m.str( "itemNetId" );
		if (id == null || m.from == null || m.from.equals( link.me() )) return;
		if (s.received.contains( id ) || s.mine.contains( id ) || s.allyTaken.contains( id )) return;
		Level level = Dungeon.level;
		if (level == null || m.depth == null || m.branch == null
				|| m.depth != Dungeon.depth || m.branch != Dungeon.branch) return;
		int cell = (int) m.num( "cell" );
		if (cell < 0 || cell >= level.length() || (!level.passable[cell] && !level.avoid[cell])) return;
		Item item = ItemCodec.decode( m.str( "item" ) );
		if (item == null) return;

		ids.put( item, id );
		remember( item );
		s.received.add( id );
		Heap heap = level.drop( item, cell );
		if (heap.sprite != null) heap.sprite.drop( cell );
		GLog.i( Messages.get( CoopItems.class, "ally_dropped", item.name() ) );
	}

	//GIVE's result (§5.7): an item the ally handed to us goes into our pack (or at our feet)
	private static void onTransfer( Message m ) {
		CoopItemsState s = state();
		String id = m.str( "itemNetId" );
		Hero hero = Dungeon.hero;
		if (id == null || hero == null || !"item".equals( m.str( "kind" ) )
				|| !String.valueOf( link.me() ).equals( m.str( "to" ) ) || s.received.contains( id )) return;
		Item item = ItemCodec.decode( m.str( "item" ) );
		if (item == null) return;

		ids.put( item, id );
		s.received.add( id );
		s.mine.add( id );
		if (!item.collect( hero.belongings.backpack ) && Dungeon.level != null) {
			Heap heap = Dungeon.level.drop( item, hero.pos );
			if (heap.sprite != null) heap.sprite.drop( hero.pos );
		}
		GLog.i( Messages.get( CoopItems.class, "ally_sent", item.name() ) );
	}

	// ---- world changes (GL thread, or the loading thread before the scene exists) ----

	//our optimistic pickup lost the race: the item leaves our inventory
	static void lose( Pending p ) {
		Hero hero = Dungeon.hero;
		if (hero == null) return;
		String name = null;

		Item held = null;
		for (Item i : hero.belongings) {
			if (p.id.equals( idOf( i ) )) {
				held = i;
				break;
			}
		}
		if (held != null) {
			name = held.name();
			if (held.stackable && held.quantity() > p.quantity && p.quantity > 0) {
				//other copies merged into it: only ours goes
				held.quantity( held.quantity() - p.quantity );
				held.updateQuickslot();
			} else if (held instanceof EquipableItem && ((EquipableItem) held).isEquipped( hero )) {
				//a cursed item can't be taken off by the hero, but this one was never ours
				boolean cursed = held.cursed;
				held.cursed = false;
				((EquipableItem) held).doUnequip( hero, false, false );
				held.cursed = cursed;
			} else {
				held.detachAll( hero.belongings.backpack );
			}
		} else if (p.cls.equals( Gold.class.getName() )) {
			Dungeon.gold = Math.max( 0, Dungeon.gold - p.quantity );
			name = new Gold( p.quantity ).name();
		} else if (p.quantity > 0) {
			//it merged into a stack we already had
			for (Item i : hero.belongings.backpack) {
				if (i.stackable && i.getClass().getName().equals( p.cls ) && !(i instanceof Key)) {
					name = i.name();
					if (i.quantity() > p.quantity) {
						i.quantity( i.quantity() - p.quantity );
						i.updateQuickslot();
					} else {
						i.detachAll( hero.belongings.backpack );
					}
					break;
				}
			}
		}
		//already used up: nothing left to take back
		if (name == null) name = className( p.cls );
		GLog.n( Messages.get( CoopItems.class, "ally_first", name ) );
	}

	private static void removeFromFloor( Level level, Set<String> gone, boolean fx ) {
		if (gone.isEmpty()) return;
		for (Heap heap : new ArrayList<>( level.heaps.valueList() )) {
			if (heap.type == Heap.Type.FOR_SALE) continue;
			for (Item item : new ArrayList<>( heap.items )) {
				String id = idOf( item );
				if (id == null || !gone.contains( id ) || item instanceof Key) continue;
				heap.remove( item );
				if (fx && heap.sprite != null && ShatteredPixelDungeon.scene() instanceof GameScene) {
					CellEmitter.get( heap.pos ).burst( SmokeParticle.FACTORY, 4 );
				}
			}
		}
	}

	// ---- helpers ----

	private static void remember( Heap heap ) {
		if (heap == null) return;
		for (Item item : heap.items) remember( item );
	}

	private static void remember( Item item ) {
		if (shared( idOf( item ) )) seen.put( item, item.quantity() );
	}

	private static CoopItemsState state() {
		CoopRun run = Coop.run();
		return run == null ? null : run.items;
	}

	//the heap the item ended up in: on cell, or next to it (Level.drop moves items off chests)
	private static Heap heapOf( Level level, Item item, int cell ) {
		Heap heap = level.heaps.get( cell );
		if (heap != null && heap.items.contains( item )) return heap;
		for (Heap h : level.heaps.valueList()) {
			if (h.items.contains( item )) return h;
		}
		return null;
	}

	private static List<String> strings( Object list ) {
		ArrayList<String> out = new ArrayList<>();
		if (list instanceof List) {
			for (Object o : (List<?>) list) {
				if (o instanceof String) out.add( (String) o );
			}
		}
		return out;
	}

	private static String className( String cls ) {
		try {
			Class<?> c = Reflection.forNameUnhandled( cls );
			Object o = Reflection.newInstance( c );
			if (o instanceof Item) return ((Item) o).name();
		} catch (Exception e) {
			//fall through
		}
		return "?";
	}
}
