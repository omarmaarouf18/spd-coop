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

package com.shatteredpixel.shatteredpixeldungeon.coop.mission.cell;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopConnection;
import com.shatteredpixel.shatteredpixeldungeon.coop.ghost.AllyState;
import com.shatteredpixel.shatteredpixeldungeon.coop.ghost.CoopGhosts;
import com.shatteredpixel.shatteredpixeldungeon.coop.ghost.Marks;
import com.shatteredpixel.shatteredpixeldungeon.coop.mission.CoopMissions;
import com.shatteredpixel.shatteredpixeldungeon.coop.mission.MissionListener;
import com.shatteredpixel.shatteredpixeldungeon.coop.mission.MissionState;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopSession;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.noosa.Game;

//facade of The Cell (MEC-01, D-020, game-design §4): every // COOP: hook for the Cell calls one
// method here, and it is the Cell's MissionListener and MissionReward (MEC-00).
//- co-op level gen places 0–1 hidden BindingTrap per eligible floor (placeTrap);
//- the hero stepping on it sends MISSION_TRIGGER{CELL, trapCell, roomId} while the ally is online
//  on this floor and no mission runs; otherwise nothing happens: no message, still hidden (armed);
//- MISSION_START: the trap is spent in both worlds; the prisoner gets CellPrison (sealed room,
//  waves), the rescuer CellRescue (the Cell Key, local to their world, and a ping);
//- the rescuer USEs the key next to a door of the cell: GIVE{kind:"cell_key"};
//- MISSION_END: unseal, no more waves, keys gone; success → CellReward (+ Bonded) once the hero
//  can act (MEC-00 applies it). The failure penalty is MEC-00's, the prisoner's Downed is MEC-02's.
//Listening: CoopMissions has no co-op start hook for us (Coop.java is not ours), so this class
// registers itself when it is first loaded, and everything that can be part of a Cell loads it:
// co-op level gen (placeTrap, every co-op floor), a loaded BindingTrap, and the hero's
// CellPrison/CellRescue buffs and Cell Keys (a resume on another floor).
//Single-player: the hooks return the vanilla answer at once (no BindingTrap, no Bonded).
public final class CoopCell {

	public static final String TASK_KEY = "key";

	//the network, the mission mirror and the GL thread, replaceable in tests
	public interface Link {
		//our playerId
		String me();
		boolean allyOn( int depth, int branch );
		boolean trigger( int depth, int branch, int trapCell, long roomId );
		boolean giveKey( int depth, int branch, String itemNetId );
		MissionState.Mission mission();
		long serverNow();
		//the mission clock runs (not paused, link up)
		boolean clockRunning();
		//we are Downed (MEC-02): no waves meanwhile
		boolean downed();
		//a local ping on the key (the ally doesn't need it)
		void ping( int depth, int branch, int cell );
		//runs on the GL thread (AGENTS.md rule 20)
		void post( Runnable r );
	}

	public static final Link LIVE = new Link() {
		@Override
		public String me() {
			return CoopMissions.me();
		}

		@Override
		public boolean allyOn( int depth, int branch ) {
			AllyState ally = CoopGhosts.ally();
			return session() != null && ally.online() && ally.floor( depth, branch ) == AllyState.Floor.SAME;
		}

		@Override
		public boolean trigger( int depth, int branch, int trapCell, long roomId ) {
			CoopSession s = session();
			return s != null && s.missionTrigger( depth, branch, MissionState.CELL, trapCell, roomId );
		}

		@Override
		public boolean giveKey( int depth, int branch, String itemNetId ) {
			CoopSession s = session();
			return s != null && s.give( depth, branch, itemNetId, KIND_KEY, null, null );
		}

		@Override
		public MissionState.Mission mission() {
			return CoopMissions.mission();
		}

		@Override
		public long serverNow() {
			return CoopMissions.serverNow();
		}

		@Override
		public boolean clockRunning() {
			return CoopMissions.clockRunning();
		}

		@Override
		public boolean downed() {
			return CoopMissions.isDowned( me() );
		}

		@Override
		public void ping( int depth, int branch, int cell ) {
			CoopGhosts.marks().placeLocal( depth, branch, cell, Marks.Kind.ITEM, System.currentTimeMillis() );
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

	static final String KIND_KEY = "cell_key";
	//the key's itemNetId is "<rescuer>:<counter>" (PROTOCOL.md §3), counter from keySeed above
	// the range CoopItems' own counters use
	static final long KEY_COUNTER_BASE = 1_000_000_000L;
	static final long KEY_COUNTER_SPAN = 8_999_999_999L;

	private static Link link = LIVE;

	static final MissionListener LISTENER = new MissionListener() {
		@Override
		public void onMissionStart( MissionState.Mission mission ) {
			CoopCell.onMissionStart( mission );
		}

		@Override
		public void onMissionUpdate( MissionState.Mission mission ) {
			CoopCell.onMissionUpdate( mission );
		}

		@Override
		public void onMissionEnd( MissionState.End end ) {
			CoopCell.onMissionEnd( end );
		}
	};

	static final CellReward REWARD = new CellReward();

	static {
		CoopMissions.addListener( LISTENER );
		CoopMissions.reward( REWARD );
	}

	private CoopCell() {}

	//loads this class (and with it the listener and the reward)
	public static void install() {}

	public static void link( Link l ) {
		link = l == null ? LIVE : l;
	}

	static Link link() {
		return link;
	}

	// ---- upstream hooks ----

	//CoopLevelGen, after level.create(): 0–1 hidden Binding Trap (co-op runs only)
	public static void placeTrap( Level level ) {
		if (Coop.isCoopRun()) CellLayout.place( level, Dungeon.depth, Dungeon.branch );
	}

	//Level.pressCell, on a hidden trap: false keeps a Binding Trap that can't fire right now
	// silent (no "a hidden trap activates" line). Any other trap: true, as upstream.
	public static boolean armed( Trap trap ) {
		return !(trap instanceof BindingTrap) || canTrigger( (BindingTrap) trap );
	}

	//Hero.attackSkill: Bonded's +10% accuracy (never outside co-op runs: nothing gives Bonded there)
	public static float accuracyFactor( Hero hero ) {
		return hero.buff( Bonded.class ) != null ? Bonded.ACCURACY : 1f;
	}

	// ---- the trap ----

	static boolean canTrigger( BindingTrap trap ) {
		Hero hero = Dungeon.hero;
		Level level = Dungeon.level;
		return Coop.isCoopRun() && trap.active && hero != null && hero.isAlive() && level != null
				&& hero.pos == trap.pos && level.traps.get( trap.pos ) == trap
				&& link.mission() == null && link.allyOn( Dungeon.depth, Dungeon.branch );
	}

	//BindingTrap.trigger (actor thread): ask the server; the trap stays as it is until it answers
	static void onTriggered( BindingTrap trap ) {
		if (!canTrigger( trap )) return;
		final int depth = Dungeon.depth, branch = Dungeon.branch, cell = trap.pos;
		final long roomId = trap.roomId;
		link.post( () -> link.trigger( depth, branch, cell, roomId ) );
	}

	//the hero's view after a door changed, when a world is on screen
	static void refreshView() {
		if (Dungeon.hero != null && ShatteredPixelDungeon.scene() instanceof GameScene) Dungeon.observe();
	}

	static BindingTrap trapAt( Level level, int cell ) {
		if (level == null || cell < 0 || cell >= level.length()) return null;
		Trap trap = level.traps.get( cell );
		return trap instanceof BindingTrap ? (BindingTrap) trap : null;
	}

	// ---- missions (GL thread) ----

	static void onMissionStart( MissionState.Mission m ) {
		if (!Coop.isCoopRun() || !MissionState.CELL.equals( m.kind )) return;
		Hero hero = Dungeon.hero;
		if (hero == null) return;
		boolean here = Dungeon.level != null && Dungeon.depth == m.depth && Dungeon.branch == m.branch;
		BindingTrap trap = here ? trapAt( Dungeon.level, m.trapCell ) : null;
		if (trap != null && trap.roomId != m.roomId) trap = null;
		//spent in both worlds: one Cell per trap
		if (trap != null && trap.active) {
			trap.reveal();
			trap.disarm();
		}

		String me = link.me();
		if (me.equals( m.prisoner )) {
			CellPrison prison = Buff.affect( hero, CellPrison.class );
			if (!m.id.equals( prison.missionId )) {
				prison.start( m, trap );
				prison.begin();
			}
		} else if (me.equals( m.rescuer )) {
			CellRescue rescue = Buff.affect( hero, CellRescue.class );
			if (!m.id.equals( rescue.missionId )) {
				rescue.start( m );
				GLog.w( Messages.get( CoopCell.class, "trapped" ) );
				rescue.spawnKey();
			}
		}
	}

	static void onMissionUpdate( MissionState.Mission m ) {
		if (!Coop.isCoopRun() || !MissionState.CELL.equals( m.kind )) return;
		if (Boolean.TRUE.equals( m.tasks.get( TASK_KEY ) )) {
			CellRescue rescue = Dungeon.hero == null ? null : Dungeon.hero.buff( CellRescue.class );
			if (rescue != null && m.id.equals( rescue.missionId )) rescue.finish();
		}
	}

	//any result: unseal, stop the waves, the keys go. The penalty (failed) is MEC-00's; the
	// reward (success) comes through REWARD once the hero can act.
	static void onMissionEnd( MissionState.End end ) {
		if (!Coop.isCoopRun() || Dungeon.hero == null) return;
		CellPrison prison = Dungeon.hero.buff( CellPrison.class );
		if (prison != null && (end.missionId.equals( prison.missionId ) || prison.missionId.isEmpty())) prison.finish();
		CellRescue rescue = Dungeon.hero.buff( CellRescue.class );
		if (rescue != null && (end.missionId.equals( rescue.missionId ) || rescue.missionId.isEmpty())) rescue.finish();
		if (rescue == null) CellRescue.removeKeys( Dungeon.level );
	}

	// ---- the key ----

	static String keyNetId( MissionState.Mission m ) {
		return m.rescuer + ":" + (KEY_COUNTER_BASE + Math.floorMod( m.keySeed, KEY_COUNTER_SPAN ));
	}

	//the key belongs to the running mission, whose key task is still open
	static boolean keyValid( CellKey key ) {
		MissionState.Mission m = link.mission();
		return m != null && MissionState.CELL.equals( m.kind ) && m.id.equals( key.missionId )
				&& !Boolean.TRUE.equals( m.tasks.get( TASK_KEY ) );
	}

	//CellKey USE (GL thread): next to a door of the ally's cell, hand the key over
	static boolean useKey( Hero hero, CellKey key ) {
		MissionState.Mission m = link.mission();
		if (!keyValid( key ) || !link.me().equals( m.rescuer )) {
			GLog.w( Messages.get( CoopCell.class, "key_stale" ) );
			key.detachAll( hero.belongings.backpack );
			return false;
		}
		Level level = Dungeon.level;
		BindingTrap trap = Dungeon.depth == m.depth && Dungeon.branch == m.branch ? trapAt( level, m.trapCell ) : null;
		boolean atDoor = false;
		if (trap != null) {
			for (int door : trap.doors) {
				if (level.distance( hero.pos, door ) <= 1) atDoor = true;
			}
		}
		if (!atDoor) {
			GLog.w( Messages.get( CoopCell.class, "key_far" ) );
			return false;
		}
		if (!link.giveKey( m.depth, m.branch, keyNetId( m ) )) {
			GLog.w( Messages.get( CoopCell.class, "key_not_sent" ) );
			return false;
		}
		GLog.i( Messages.get( CoopCell.class, "key_given" ) );
		hero.spendAndNext( 1f );
		return true;
	}
}
