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
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopSession;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Message;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Game;
import com.watabou.noosa.audio.Sample;

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
	//a received change is being applied: the hooks stay quiet
	private static boolean applying = false;
	//the door the hero last opened (cell, depth, branch): a knockback (WandOfBlastWave.throwChar)
	// moves the hero off it before Door.leave runs
	private static int heroDoor = -1, heroDoorDepth, heroDoorBranch;

	private CoopLinks() {}

	public static void link( Link l ) {
		link = l == null ? LIVE : l;
		heroDoor = -1;
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
		if (!Coop.isCoopRun() || !Protocol.TERRAIN.equals( m.t )) return;
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
}
