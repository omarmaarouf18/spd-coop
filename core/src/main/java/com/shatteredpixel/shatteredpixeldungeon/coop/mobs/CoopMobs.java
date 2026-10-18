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

package com.shatteredpixel.shatteredpixeldungeon.coop.mobs;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopConnection;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopRun;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopSession;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Message;
import com.shatteredpixel.shatteredpixeldungeon.effects.CellEmitter;
import com.shatteredpixel.shatteredpixeldungeon.effects.FloatingText;
import com.shatteredpixel.shatteredpixeldungeon.effects.particles.SmokeParticle;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

//Shared monsters in different places (D-023, game-design §6A.9, MOB-01). Every // COOP: hook
// for shared mobs calls one method here; everything is a no-op outside co-op runs.
//- Ids: co-op level gen numbers the floor's shared mobs (levelgen.CoopMobIds); the id lives on
//  the Mob instance and travels in its bundle ("coop_mob_id"). Runtime mobs never get one.
//- Places: on this player's first entry to a floor, the wandering shared mobs move to this
//  player's own cells (MobPlacement).
//- Kills: a shared mob that dies for real, or turns into an ally (corruption, enthrallment),
//  is reported with MOB_KILLED. The server's MOB_DEAD/MOB_KILLS remove it from this world:
//  quietly (a puff, no loot, no death effects) and with its XP when the ally killed it on the
//  floor this player is on, or on a floor they haven't entered yet. A kill on a floor they
//  already left is only removed on the next visit, without XP. XP is never granted twice.
//- Kills that can't be sent yet stay in MobState.unsent (saved) until the server confirms them.
public final class CoopMobs {

	static final String ID_KEY = "coop_mob_id";

	//the network and the GL thread, replaceable in tests
	public interface Link {
		//our playerId, or null before the first WELCOME
		String me();
		boolean running();
		boolean mobKilled( int depth, int branch, String mobNetId );
		boolean mobKillsReq( int depth, int branch );
		//runs on the GL thread (AGENTS.md rule 20)
		void post( Runnable r );
		//a GameScene is showing the current floor (not mid floor switch)
		boolean worldReady();
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
		public boolean mobKilled( int depth, int branch, String mobNetId ) {
			CoopSession s = session();
			return s != null && s.mobKilled( depth, branch, mobNetId );
		}

		@Override
		public boolean mobKillsReq( int depth, int branch ) {
			CoopSession s = session();
			return s != null && s.mobKillsReq( depth, branch );
		}

		@Override
		public void post( Runnable r ) {
			Game.runOnRenderThread( r::run );
		}

		@Override
		public boolean worldReady() {
			return ShatteredPixelDungeon.scene() instanceof GameScene;
		}

		private CoopSession session() {
			CoopSession s = CoopConnection.session();
			return s != null && s.state() == CoopSession.State.RUNNING ? s : null;
		}
	};

	private static Link link = LIVE;

	private static final Map<Mob, String> ids = Collections.synchronizedMap( new WeakHashMap<>() );

	private CoopMobs() {}

	//tests: null restores the live link
	public static void link( Link l ) {
		link = l == null ? LIVE : l;
	}

	// ---- ids ----

	//the mob's mobNetId, or null if it isn't a shared mob
	public static String idOf( Mob mob ) {
		return mob == null ? null : ids.get( mob );
	}

	//co-op level gen (CoopMobIds)
	public static void bind( Mob mob, String id ) {
		if (mob != null && id != null) ids.put( mob, id );
	}

	//Mob.storeInBundle
	public static void store( Mob mob, Bundle bundle ) {
		if (!Coop.isCoopRun()) return;
		String id = idOf( mob );
		if (id != null) bundle.put( ID_KEY, id );
	}

	//Mob.restoreFromBundle
	public static void restore( Mob mob, Bundle bundle ) {
		if (bundle.contains( ID_KEY )) bind( mob, bundle.getString( ID_KEY ) );
	}

	// ---- kills in this world ----

	//Mob.die, before the death itself (a Ghoul's fake death returns before it: not a kill)
	public static void onDeath( Mob mob ) {
		killed( mob, mob.alignment == Char.Alignment.ENEMY && Dungeon.hero != null && Dungeon.hero.isAlive() );
	}

	//AllyBuff.affectAndLoot: an enemy turned into an ally (corruption, Siren's Song, ...) is a kill;
	// affectAndLoot gives this hero its XP
	public static void onTurnedAlly( Mob mob ) {
		killed( mob, true );
	}

	private static synchronized void killed( Mob mob, boolean heroGetsXp ) {
		MobState s = state();
		String id = idOf( mob );
		if (!Coop.isCoopRun() || s == null || id == null) return;
		//its XP goes to this hero by the vanilla path (Mob.destroy / affectAndLoot)
		if (heroGetsXp) s.xp.add( id );
		if (!s.dead.add( id )) return;
		s.unsent.add( id );
		link.post( CoopMobs::flush );
	}

	//GL thread: sends every kill the server hasn't confirmed yet (MOB_KILLED is idempotent)
	public static synchronized void flush() {
		MobState s = state();
		if (s == null || !link.running()) return;
		for (String id : new ArrayList<>( s.unsent )) {
			int[] floor = floorOf( id );
			if (floor == null) {
				s.unsent.remove( id );
				continue;
			}
			if (!link.mobKilled( floor[0], floor[1], id )) return;
		}
	}

	// ---- floor entry and resume ----

	//Dungeon.switchLevel(), after the DET-02 overlay and ITM-01's hook: the level is in place, the
	// actors are initialized, nothing is saved or shown yet
	public static synchronized void onLevelEntered( Level level ) {
		CoopRun run = Coop.run();
		MobState s = state();
		Hero hero = Dungeon.hero;
		if (!Coop.isCoopRun() || run == null || s == null || level == null) return;
		final int depth = Dungeon.depth, branch = Dungeon.branch;
		String key = floorKey( depth, branch );

		if (s.legacy) {
			//a run saved before MOB-01: its floors were generated without mob ids
			for (int k : Dungeon.generatedLevels) s.entered.add( floorKey( k % 1000, k / 1000 ) );
			s.legacy = false;
		}
		if (!s.current.isEmpty() && !s.current.equals( key )) s.left.add( s.current );
		s.current = key;
		if (s.entered.add( key )) {
			MobPlacement.relocate( level, Dungeon.seedForDepth( depth, branch ), player( run ),
					hero == null ? -1 : hero.pos );
		}

		//kills learned while away: gone now, with XP only for floors not entered before
		for (Mob mob : new ArrayList<>( level.mobs )) {
			String id = idOf( mob );
			//still an enemy here: a mob this player turned into an ally stays (it is our kill)
			if (id == null || !s.dead.contains( id ) || !isEnemy( mob )) continue;
			boolean xp = s.pendingXp.remove( id ) && !s.xp.contains( id );
			remove( level, mob, false );
			if (xp) grantXp( s, mob );
		}

		link.post( () -> {
			flush();
			request( depth, branch );
		} );
	}

	//CoopConnection.onRunStart for a WELCOME snapshot (a resume): events may have been missed,
	// so ask again for the floor we are on (applied idempotently)
	public static void onResumed() {
		if (!Coop.isCoopRun() || state() == null) return;
		link.post( () -> {
			flush();
			if (Dungeon.level != null) request( Dungeon.depth, Dungeon.branch );
		} );
	}

	private static void request( int depth, int branch ) {
		if (link.running()) link.mobKillsReq( depth, branch );
	}

	// ---- server events (CoopConnection listener, GL thread) ----

	public static synchronized void onServerEvent( Message m ) {
		if (!Coop.isCoopRun() || state() == null) return;
		switch (m.t) {
			case Protocol.MOB_DEAD:
				onMobDead( m.str( "mobNetId" ), m.str( "by" ) );
				break;
			case Protocol.MOB_KILLS:
				onMobKills( (int) m.num( "depth" ), (int) m.num( "branch" ), strings( m.p.get( "mobNetIds" ) ) );
				break;
			default:
				break;
		}
	}

	private static void onMobDead( String id, String by ) {
		MobState s = state();
		int[] floor = floorOf( id );
		if (id == null || by == null || floor == null) return;
		s.unsent.remove( id );
		if (by.equals( link.me() )) {
			s.dead.add( id );
			return;
		}
		s.dead.add( id );
		String key = floorKey( floor[0], floor[1] );
		if (onCurrentFloor( floor[0], floor[1] )) {
			//the ally killed it on the floor we are on: its XP is ours too
			Mob mob = find( Dungeon.level, id );
			if (mob != null) {
				remove( Dungeon.level, mob, true );
				if (!s.xp.contains( id )) grantXp( s, mob );
			}
		} else if (!s.entered.contains( key )) {
			s.pendingXp.add( id );
		}
	}

	private static void onMobKills( int depth, int branch, List<String> killed ) {
		MobState s = state();
		String key = floorKey( depth, branch );
		boolean current = onCurrentFloor( depth, branch );
		for (String id : killed) {
			s.unsent.remove( id );
			boolean known = !s.dead.add( id );
			if (current) {
				Mob mob = find( Dungeon.level, id );
				if (mob == null) continue;
				//the floor's snapshot: XP unless this player left the floor since (D-023)
				boolean xp = !s.xp.contains( id ) && !s.left.contains( key );
				remove( Dungeon.level, mob, true );
				if (xp) grantXp( s, mob );
			} else if (!known && !s.entered.contains( key )) {
				s.pendingXp.add( id );
			}
		}
		//anything the server doesn't list yet goes out again
		flush();
	}

	// ---- removal and XP ----

	//takes a shared mob out of this world without dying: no loot, no death effects, no on-kill
	// procs, no XP of its own (Mob.destroy would grant them)
	static void remove( Level level, Mob mob, boolean fx ) {
		mob.HP = 0;
		Actor.remove( mob );
		level.mobs.remove( mob );
		if (mob.sprite != null) {
			if (fx && link.worldReady()) CellEmitter.get( mob.pos ).burst( SmokeParticle.FACTORY, 6 );
			mob.sprite.killAndErase();
		}
	}

	//the ally's kill: this hero earns the mob's XP by the vanilla rule (hero level vs maxLvl)
	static void grantXp( MobState s, Mob mob ) {
		Hero hero = Dungeon.hero;
		String id = idOf( mob );
		if (hero == null || !hero.isAlive() || id == null || !s.xp.add( id )) return;
		int exp = hero.lvl <= mob.maxLvl ? mob.EXP : 0;
		if (exp > 0 && hero.sprite != null) {
			hero.sprite.showStatusWithIcon( CharSprite.POSITIVE, Integer.toString( exp ), FloatingText.EXPERIENCE );
		}
		hero.earnExp( exp, mob.getClass() );
	}

	// ---- helpers ----

	private static boolean onCurrentFloor( int depth, int branch ) {
		return Dungeon.level != null && Dungeon.depth == depth && Dungeon.branch == branch && link.worldReady();
	}

	//the shared mob with this id that is still an enemy in this world
	private static Mob find( Level level, String id ) {
		if (level == null) return null;
		for (Mob mob : level.mobs) {
			if (id.equals( idOf( mob ) ) && isEnemy( mob )) return mob;
		}
		return null;
	}

	//not ours: an enemy, or a hidden mimic (NEUTRAL until it reveals itself)
	private static boolean isEnemy( Mob mob ) {
		return mob.isAlive() && mob.alignment != Char.Alignment.ALLY;
	}

	//"depth:branch:index" -> {depth, branch}
	static int[] floorOf( String id ) {
		if (id == null) return null;
		String[] f = id.split( ":" );
		if (f.length != 3) return null;
		try {
			return new int[]{ Integer.parseInt( f[0] ), Integer.parseInt( f[1] ) };
		} catch (NumberFormatException e) {
			return null;
		}
	}

	static String floorKey( int depth, int branch ) {
		return depth + "_" + branch;
	}

	//the playerId the server gave this player (saved in CoopRun); the slot before the first WELCOME
	static String player( CoopRun run ) {
		return run.playerId.isEmpty() ? "slot" + run.slot : run.playerId;
	}

	private static MobState state() {
		CoopRun run = Coop.run();
		return run == null ? null : run.mobs;
	}

	private static List<String> strings( Object list ) {
		ArrayList<String> out = new ArrayList<>();
		if (list instanceof Collection) {
			for (Object o : (Collection<?>) list) {
				if (o instanceof String) out.add( (String) o );
			}
		}
		return out;
	}
}
