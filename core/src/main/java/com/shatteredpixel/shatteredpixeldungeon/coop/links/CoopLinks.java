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

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopConnection;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopRun;
import com.shatteredpixel.shatteredpixeldungeon.coop.ghost.AllyState;
import com.shatteredpixel.shatteredpixeldungeon.coop.ghost.CoopGhosts;
import com.shatteredpixel.shatteredpixeldungeon.coop.mission.cell.BindingTrap;
import com.shatteredpixel.shatteredpixeldungeon.coop.mission.cell.CellPrison;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopSession;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Message;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Game;
import com.watabou.noosa.audio.Sample;

import java.util.List;
import java.util.Map;

//door mirroring (LINK-01, game-design §6A.6, PROTOCOL.md §5.10 TERRAIN).
//A regular door that our hero opens or closes, or that burns in our world (whatever burnt it),
// does the same on that cell in the ally's world, while the ally is online on the same floor.
// Every upstream hook calls one method here, and each is a no-op outside co-op runs.
//
//- Sent: Door.enter (DOOR -> OPEN_DOOR) when the hero stepped in, Door.leave (OPEN_DOOR -> DOOR)
//  when the hero stepped (or was knocked) out, and Level.destroy (a DOOR/OPEN_DOOR burnt to
//  EMBERS). Mobs opening and closing doors stay in our world. Doors changed any other way
//  (unlocking, Skeleton Key, fire spells forcing a door open) are not sent; locked, crystal and sealed doors never go through
//  these hooks as a regular door.
//- Applied: only on a cell that is a regular door (DOOR/OPEN_DOOR) in our world too, so a locked
//  door, a sealed Cell door (MEC-01) or a door we already burnt never changes. A door never closes
//  on a cell with a char or a heap on it (the same rule as Door.leave): it stays open.
//- No echo: an applied change goes through Level.set, never through the hooks, and the hooks
//  ignore anything while a change is being applied.
//
//Connected worlds (LINK-02, D-023, game-design §6A.10–§6A.11, PROTOCOL.md §5.11.2): permanent
// changes go to the server with persist:true, whether or not the ally is on the floor (the server
// stores them for the floor and relays them when the ally is there):
//- Sent: a key (any key, the Skeleton Key too) unlocking a locked door (DOOR), a crystal door
//  (EMPTY) or the locked exit (UNLOCKED_EXIT); a hero-locked door opened (DOOR); a door locked with
//  the Skeleton Key (HERO_LKD_DR); a floor trap used up here, whatever did it (Trap.disarm:
//  stepping on it, a chain reaction, a Reclaim Trap, a time freeze; INACTIVE_TRAP); a hidden trap
//  Level.discover revealed (TRAP). Never: the Binding Trap's trigger (MEC-01 spends it with
//  MISSION_START; revealing it is sent), a door the Cell sealed (CellPrison).
//- Reliable: a change that can't go out now (offline) waits in TerrainOutbox (saved with the
//  run), in order, and goes out on the next server event, floor entry or reconnect.
//- TERRAIN_REQ on every floor entry and every transition to RUNNING (fresh start, reconnect,
//  resume); one already in flight for that floor on this connection isn't sent again.
//- Applied (TERRAIN with persist, TERRAINS on floor entry): only along Protocol.terrainTransition
//  (forward-only), never on a sealed Cell door, never consuming the Binding Trap, never locking or
//  closing a door on a char or a heap. A trap is updated as an object too (consumed: inactive and
//  visible; revealed: visible) without firing; no key is used. Idempotent: an applied tile can't
//  apply again.
public final class CoopLinks {

	//the network and the GL thread, replaceable in tests
	public interface Link {
		//our playerId, or null before the first WELCOME
		String me();
		boolean allyOn( int depth, int branch );
		boolean terrain( int depth, int branch, int cell, int tile );
		//runs on the GL thread (AGENTS.md rule 20)
		void post( Runnable r );
		//a level is shown and can be changed (not mid floor switch)
		boolean worldReady();
		//redraw a changed cell and refresh the hero's view of it
		void redraw( Level level, int cell, int tile );
		//TERRAIN{persist:true} (LINK-02): false when it can't go out now
		default boolean terrainPersist( int depth, int branch, int cell, int tile ) {
			return false;
		}
		//TERRAIN_REQ (LINK-02): false when it can't go out now
		default boolean terrainReq( int depth, int branch ) {
			return false;
		}
	}

	public static final Link LIVE = new Link() {
		@Override
		public String me() {
			CoopRun run = Coop.run();
			if (run != null && !run.playerId.isEmpty()) return run.playerId;
			return CoopConnection.playerId();
		}

		@Override
		public boolean allyOn( int depth, int branch ) {
			AllyState ally = CoopGhosts.ally();
			return session() != null && ally.online() && ally.floor( depth, branch ) == AllyState.Floor.SAME;
		}

		@Override
		public boolean terrain( int depth, int branch, int cell, int tile ) {
			CoopSession s = session();
			return s != null && s.terrain( depth, branch, cell, tile );
		}

		@Override
		public boolean terrainPersist( int depth, int branch, int cell, int tile ) {
			CoopSession s = session();
			return s != null && s.terrain( depth, branch, cell, tile, true );
		}

		@Override
		public boolean terrainReq( int depth, int branch ) {
			CoopSession s = session();
			return s != null && s.terrainReq( depth, branch );
		}

		@Override
		public void post( Runnable r ) {
			Game.runOnRenderThread( r::run );
		}

		@Override
		public boolean worldReady() {
			return ShatteredPixelDungeon.scene() instanceof GameScene;
		}

		@Override
		public void redraw( Level level, int cell, int tile ) {
			GameScene.updateMap( cell );
			//like Door.enter / Door.leave: the view changes only if the hero sees the door
			if (Dungeon.hero != null && level.heroFOV[cell]) {
				Dungeon.observe();
				if (tile == Terrain.OPEN_DOOR) Sample.INSTANCE.play( Assets.Sounds.OPEN );
			}
		}

		private CoopSession session() {
			CoopSession s = CoopConnection.session();
			return s != null && s.state() == CoopSession.State.RUNNING ? s : null;
		}
	};

	private static Link link = LIVE;
	//what waits outside a run (never filled: every hook is co-op only); a run has its own, saved
	private static final TerrainOutbox noRun = new TerrainOutbox();
	//connections seen (each transition to RUNNING starts one): a request sent on an older one may
	// have been lost with it
	private static int connection = 0;
	//the TERRAIN_REQ sent and not answered yet: depth, branch, connection; null if none
	private static int[] inFlight;
	//a floor entry waits this many frames at most for the scene before asking anyway
	static final int READY_WAIT = 600;
	//a received change is being applied: the hooks stay quiet
	private static boolean applying = false;
	//the door the hero last opened (cell, depth, branch): a knockback (WandOfBlastWave.throwChar)
	// moves the hero off it before Door.leave runs
	private static int heroDoor = -1, heroDoorDepth, heroDoorBranch;

	private CoopLinks() {}

	public static void link( Link l ) {
		link = l == null ? LIVE : l;
		heroDoor = -1;
		inFlight = null;
	}

	static TerrainOutbox outbox() {
		CoopRun run = Coop.run();
		return run != null ? run.terrainOutbox : noRun;
	}

	private static final TerrainOutbox.Sender SENDER = new TerrainOutbox.Sender() {
		@Override
		public boolean change( int depth, int branch, int cell, int tile ) {
			return link.terrainPersist( depth, branch, cell, tile );
		}

		@Override
		public boolean request( int depth, int branch ) {
			return link.terrainReq( depth, branch );
		}
	};

	//GL thread
	private static void flush() {
		int[] sent = outbox().flush( SENDER );
		if (sent != null) inFlight = new int[]{ sent[0], sent[1], connection };
	}

	//GL thread: ask for a floor's record unless it's already on its way on this connection
	private static void request( int depth, int branch ) {
		if (inFlight != null && inFlight[0] == depth && inFlight[1] == branch && inFlight[2] == connection) {
			flush();
			return;
		}
		outbox().request( depth, branch );
		flush();
	}

	static boolean regularDoor( int tile ) {
		return tile == Terrain.DOOR || tile == Terrain.OPEN_DOOR;
	}

	//the TERRAIN whitelist (PROTOCOL.md §5.10)
	static boolean doorTile( int tile ) {
		return tile == Protocol.TILE_DOOR || tile == Protocol.TILE_OPEN_DOOR || tile == Protocol.TILE_EMBERS;
	}

	// ---- our world changed (actor or GL thread) ----

	//Door.enter / Door.leave: the door at pos on the current floor just opened or closed.
	// Only the hero's own door actions are mirrored.
	public static void onDoor( int pos ) {
		if (!Coop.isCoopRun() || applying) return;
		Level level = Dungeon.level;
		Hero hero = Dungeon.hero;
		if (level == null || hero == null || pos < 0 || pos >= level.length()) return;
		int tile = level.map[pos];
		if (tile == Terrain.OPEN_DOOR) {
			if (Actor.findChar( pos ) != hero) return;
			heroDoor = pos;
			heroDoorDepth = Dungeon.depth;
			heroDoorBranch = Dungeon.branch;
		} else if (tile == Terrain.DOOR) {
			//Char.move and the hero's dash abilities close the door before the hero leaves the cell;
			// a knockback closes it after, with nobody left in the doorway
			boolean knockedOut = heroDoor == pos && heroDoorDepth == Dungeon.depth && heroDoorBranch == Dungeon.branch
					&& Actor.findChar( pos ) == null;
			if (hero.pos != pos && !knockedOut) return;
			heroDoor = -1;
		} else {
			return;
		}
		send( pos, tile );
	}

	//Level.destroy: a regular door at pos (was) burnt through
	public static void onDestroyed( Level level, int pos, int before ) {
		if (!Coop.isCoopRun() || applying || level == null || level != Dungeon.level) return;
		if (regularDoor( before ) && level.map[pos] == Terrain.EMBERS) send( pos, Terrain.EMBERS );
	}

	private static void send( final int cell, final int tile ) {
		final int depth = Dungeon.depth, branch = Dungeon.branch;
		if (!link.allyOn( depth, branch )) return;
		link.post( () -> link.terrain( depth, branch, cell, tile ) );
	}

	// ---- the ally's world changed (CoopConnection listener, GL thread) ----

	public static void onServerEvent( Message m ) {
		if (!Coop.isCoopRun()) return;
		//any server event means we're connected: send what's waiting
		flush();
		if (Protocol.TERRAIN.equals( m.t )) {
			if (m.bool( "persist" )) onPersisted( m );
			else onDoorChange( m );
		} else if (Protocol.TERRAINS.equals( m.t )) {
			onTerrains( m );
		}
	}

	//LINK-01: a door opened, closed or burnt in the ally's world (relay only)
	private static void onDoorChange( Message m ) {
		if (m.from == null || m.from.equals( link.me() )) return;
		Level level = Dungeon.level;
		if (level == null || m.depth == null || m.branch == null
				|| m.depth != Dungeon.depth || m.branch != Dungeon.branch || !link.worldReady()) return;
		int cell = (int) m.num( "cell" ), tile = (int) m.num( "tile" );
		if (cell < 0 || cell >= level.length() || !doorTile( tile )) return;
		int current = level.map[cell];
		if (!regularDoor( current ) || current == tile) return;
		//never shut a door on something (Door.leave's rule): it stays open in our world
		if (tile == Terrain.DOOR && (Actor.findChar( cell ) != null || level.heaps.get( cell ) != null)) return;

		applying = true;
		try {
			Level.set( cell, tile, level );
		} finally {
			applying = false;
		}
		link.redraw( level, cell, tile );
	}

	// ---- connected worlds (LINK-02) ----

	//Hero.onOperateComplete, SkeletonKey: the door/exit at cell was just unlocked or locked; its
	// new tile is the change (DOOR, EMPTY, UNLOCKED_EXIT, HERO_LKD_DR)
	public static void onLockChanged( int cell ) {
		if (!Coop.isCoopRun() || applying) return;
		Level level = Dungeon.level;
		if (level == null || cell < 0 || cell >= level.length() || sealable( level, cell )) return;
		int tile = level.map[cell];
		if (tile == Terrain.DOOR || tile == Terrain.EMPTY || tile == Terrain.UNLOCKED_EXIT || tile == Terrain.HERO_LKD_DR) {
			persist( cell, tile );
		}
	}

	//Trap.disarm, before it changes anything: a trap is being used up in our world, whatever did it (stepped on, a chain reaction
	// of Flock/Summoning/Distortion traps, a Reclaim Trap, a time freeze ending): it's used up in the
	// ally's world too (it fires only here). Only a trap of the floor being played (never level gen,
	// a level being loaded, or a trap object outside the floor), never the Binding Trap (MEC-01
	// disarms it in both worlds on MISSION_START). Traps that stay armed when they fire (gateway,
	// flashing, gripping) never call disarm then.
	public static void onTrapDisarmed( Trap trap ) {
		if (!Coop.isCoopRun() || applying || trap == null || trap instanceof BindingTrap) return;
		Level level = Dungeon.level;
		if (level == null || Dungeon.hero == null || !link.worldReady()) return;
		if (!trap.active || trap.pos < 0 || trap.pos >= level.length() || level.traps.get( trap.pos ) != trap) return;
		int tile = level.map[trap.pos];
		if (tile == Terrain.TRAP || tile == Terrain.SECRET_TRAP) persist( trap.pos, Terrain.INACTIVE_TRAP );
	}

	//Level.discover, before the cell changes: a hidden trap there is about to be revealed
	// (searching, a scroll, a trap going off, anything)
	public static void onDiscover( Level level, int cell ) {
		if (!Coop.isCoopRun() || applying || level == null || level != Dungeon.level) return;
		if (cell < 0 || cell >= level.length() || level.map[cell] != Terrain.SECRET_TRAP) return;
		persist( cell, Terrain.TRAP );
	}

	//floor entry: ask for the floor's stored changes, once the floor is on screen
	public static void onLevelEntered( Level level ) {
		if (!Coop.isCoopRun() || level == null) return;
		final int depth = Dungeon.depth, branch = Dungeon.branch;
		link.post( () -> requestWhenReady( depth, branch, READY_WAIT ) );
	}

	//CoopConnection, on every transition to RUNNING (fresh start, reconnect, resume): what the ally
	// changed on this floor while we were away may only be in the server's record. A request sent on
	// an earlier connection may have been lost with it, so it counts as not in flight any more.
	public static void onResumed() {
		//now, not in the posted part: a request already queued for the GL thread goes out on this
		// connection and counts as in flight
		connection++;
		if (!Coop.isCoopRun()) return;
		link.post( () -> {
			if (!Coop.isCoopRun() || Dungeon.level == null || Dungeon.hero == null) return;
			flush();
			request( Dungeon.depth, Dungeon.branch );
		} );
	}

	private static void requestWhenReady( int depth, int branch, int frames ) {
		if (depth != Dungeon.depth || branch != Dungeon.branch) return;
		if (!link.worldReady() && frames > 0) {
			link.post( () -> requestWhenReady( depth, branch, frames - 1 ) );
			return;
		}
		request( depth, branch );
	}

	private static void persist( final int cell, final int tile ) {
		final int depth = Dungeon.depth, branch = Dungeon.branch;
		link.post( () -> {
			outbox().add( depth, branch, cell, tile );
			flush();
		} );
	}

	//TERRAIN{persist:true} relayed from the ally
	private static void onPersisted( Message m ) {
		if (m.from == null || m.from.equals( link.me() )) return;
		Level level = Dungeon.level;
		if (level == null || m.depth == null || m.branch == null
				|| m.depth != Dungeon.depth || m.branch != Dungeon.branch || !link.worldReady()) return;
		apply( level, (int) m.num( "cell" ), (int) m.num( "tile" ) );
	}

	//TERRAINS: the floor's record (the answer to our TERRAIN_REQ); our own changes are in it too,
	// and apply as no-ops
	@SuppressWarnings("unchecked")
	private static void onTerrains( Message m ) {
		Level level = Dungeon.level;
		if (level == null || m.num( "depth" ) != Dungeon.depth || m.num( "branch" ) != Dungeon.branch
				|| !link.worldReady()) return;
		Object changes = m.p.get( "changes" );
		if (!(changes instanceof List)) return;
		if (inFlight != null && inFlight[0] == Dungeon.depth && inFlight[1] == Dungeon.branch) inFlight = null;
		for (Object o : (List<Object>) changes) {
			if (!(o instanceof Map)) continue;
			Map<String, Object> c = (Map<String, Object>) o;
			apply( level, (int) asLong( c.get( "cell" ) ), (int) asLong( c.get( "tile" ) ) );
		}
	}

	//one change, if it moves the cell forward here. True if applied.
	static boolean apply( Level level, int cell, int tile ) {
		if (cell < 0 || cell >= level.length() || !Protocol.isTerrainTile( tile )) return false;
		int current = level.map[cell];
		if (current == tile || !Protocol.terrainTransition( current, tile )) return false;
		//a door the Cell sealed in our world stays sealed
		if (current == Terrain.LOCKED_DOOR && sealable( level, cell )) return false;
		Trap trap = level.traps.get( cell );
		if (tile == Terrain.INACTIVE_TRAP && trap instanceof BindingTrap) return false;
		//never shut or lock a door on something
		if ((tile == Terrain.HERO_LKD_DR || (tile == Terrain.DOOR && current == Terrain.OPEN_DOOR))
				&& (Actor.findChar( cell ) != null || level.heaps.get( cell ) != null)) return false;

		applying = true;
		try {
			Level.set( cell, tile, level );
			if (trap != null && tile == Terrain.INACTIVE_TRAP) {
				trap.active = false;
				trap.visible = true;
			} else if (trap != null && tile == Terrain.TRAP) {
				trap.visible = true;
			}
		} finally {
			applying = false;
		}
		link.redraw( level, cell, tile );
		return true;
	}

	//a door of the Binding Trap's room while our hero is the Cell's prisoner (CellPrison seals
	// them with Level.set; MEC-01 keeps the seal in this world only)
	static boolean sealable( Level level, int cell ) {
		Hero hero = Dungeon.hero;
		if (hero == null || hero.buff( CellPrison.class ) == null) return false;
		for (Trap t : level.traps.valueList()) {
			if (!(t instanceof BindingTrap)) continue;
			for (int door : ((BindingTrap) t).doors) {
				if (door == cell) return true;
			}
		}
		return false;
	}

	private static long asLong( Object v ) {
		if (v instanceof Number) return ((Number) v).longValue();
		try {
			return v == null ? -1 : Long.parseLong( v.toString() );
		} catch (NumberFormatException e) {
			return -1;
		}
	}
}
