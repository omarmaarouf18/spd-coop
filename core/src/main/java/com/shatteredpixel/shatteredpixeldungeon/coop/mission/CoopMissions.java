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

package com.shatteredpixel.shatteredpixeldungeon.coop.mission;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopConnection;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopRun;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopSession;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Message;
import com.shatteredpixel.shatteredpixeldungeon.coop.ui.MissionHud;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.noosa.Game;
import com.watabou.noosa.Group;
import com.watabou.noosa.ui.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

//facade of the client mission framework (MEC-00): every // COOP: hook for missions calls one
// method here, and CoopConnection hands it the mission/downed/sync messages and the server time.
//- the state mirror lives in the run (CoopRun.missions), so it is saved and loaded with it;
//- real-time clocks come from the server (ServerClock), forced turns from ForcedTurns;
//- MISSION_END is applied once the hero waits for input: reward hook, or the return penalty.
//Single-player: every hook returns at once (no CoopRun).
//Threads: onHeroReady() on the actor thread (it only stamps a time); everything else on the GL thread.
public final class CoopMissions {

	private static final ServerClock serverClock = new ServerClock();
	private static final ForcedTurns forced = new ForcedTurns( System::currentTimeMillis );
	private static final List<MissionListener> listeners = new CopyOnWriteArrayList<>();
	private static MissionReward reward = MissionReward.NONE;
	private static Runnable sceneSwitch = () -> Game.switchScene( InterlevelScene.class );

	//local time the link (us + ally) went down; -1 while up
	private static long linkDownSince = -1;

	private CoopMissions() {}

	// ---- API for MEC-01 / MEC-02 / SYN-01 ----

	public static void addListener( MissionListener l ) {
		if (!listeners.contains( l )) listeners.add( l );
	}

	public static void removeListener( MissionListener l ) {
		listeners.remove( l );
	}

	public static void reward( MissionReward r ) {
		reward = r == null ? MissionReward.NONE : r;
	}

	//the run's mirror; null outside a co-op run
	public static MissionState state() {
		CoopRun run = Coop.run();
		return run == null ? null : run.missions;
	}

	public static MissionState.Mission mission() {
		MissionState s = state();
		return s == null ? null : s.mission();
	}

	public static boolean missionActive() {
		return mission() != null;
	}

	//our playerId (saved with the run, so known before the WELCOME)
	public static String me() {
		CoopRun run = Coop.run();
		return run == null || run.playerId.isEmpty() ? CoopConnection.playerId() : run.playerId;
	}

	public static boolean isPrisoner() {
		MissionState.Mission m = mission();
		return m != null && m.prisoner.equals( me() );
	}

	public static boolean isRescuer() {
		MissionState.Mission m = mission();
		return m != null && m.rescuer.equals( me() );
	}

	public static long serverNow() {
		return serverClock.serverNow( System.currentTimeMillis() );
	}

	public static ServerClock clock() {
		return serverClock;
	}

	public static long missionRemainingMs() {
		MissionState.Mission m = mission();
		return m == null ? 0 : m.remainingMs( serverNow() );
	}

	public static boolean isDowned( String playerId ) {
		MissionState s = state();
		return s != null && s.isDowned( playerId );
	}

	public static long downedRemainingMs( String playerId ) {
		MissionState s = state();
		return s == null ? 0 : s.downedRemainingMs( playerId, serverNow() );
	}

	public static boolean syncActive() {
		MissionState s = state();
		return s != null && s.sync() != null;
	}

	//both of us connected: otherwise the server holds every clock (D-005)
	public static boolean linkUp() {
		return CoopConnection.state() == CoopSession.State.RUNNING && CoopConnection.allyConnected();
	}

	public static boolean clockRunning() {
		MissionState s = state();
		return s != null && s.clockRunning( linkUp() );
	}

	public static ForcedTurns forcedTurns() {
		return forced;
	}

	// ---- upstream hooks ----

	//Hero.ready(): the hero waits for input; a forced wait may follow (D-005). Actor thread.
	public static void onHeroReady( Hero hero ) {
		if (Coop.isCoopRun() && hero == Dungeon.hero) forced.onHeroReady();
	}

	//GameScene.create(): the mission bar under the boss bar. It also drives tick().
	public static void addHud( Group scene, Component under ) {
		if (!Coop.isCoopRun()) return;
		linkDownSince = -1;
		forced.reset();
		scene.add( new MissionHud( under ) );
	}

	// ---- CoopConnection ----

	//WELCOME (state HANDSHAKE: CoopSession calls this before it leaves that state) or PONG
	public static void onServerTime( long serverTime, long localNow ) {
		serverClock.onServerTime( serverTime, localNow, CoopConnection.state() == CoopSession.State.HANDSHAKE );
	}

	public static boolean handles( String t ) {
		return Protocol.MISSION_START.equals( t ) || Protocol.MISSION_UPDATE.equals( t ) || Protocol.MISSION_END.equals( t )
				|| Protocol.ALLY_DOWNED.equals( t ) || Protocol.REVIVE.equals( t ) || Protocol.DOWNED_EXPIRED.equals( t )
				|| Protocol.SYNC_START.equals( t ) || Protocol.SYNC_END.equals( t );
	}

	//every s2c event; ours are mission/downed/sync lifecycle (live or replayed on resume)
	public static void onServerEvent( Message m ) {
		MissionState s = state();
		if (s == null || !handles( m.t )) return;
		if (s.apply( m, serverNow() )) notify( s, m );
	}

	static void notify( MissionState s, Message m ) {
		String t = m.t;
		for (MissionListener l : listeners) {
			if (Protocol.MISSION_START.equals( t )) {
				l.onMissionStart( s.mission() );
			} else if (Protocol.MISSION_UPDATE.equals( t )) {
				l.onMissionUpdate( s.mission() );
			} else if (Protocol.MISSION_END.equals( t )) {
				List<MissionState.End> ends = s.pendingEnds();
				l.onMissionEnd( ends.get( ends.size() - 1 ) );
			} else if (Protocol.ALLY_DOWNED.equals( t )) {
				l.onAllyDowned( m.str( "player" ), m.num( "deadline" ) );
			} else if (Protocol.REVIVE.equals( t )) {
				l.onRevive( m.str( "target" ), m.str( "mode" ), (int) m.num( "hpPct" ) );
			} else if (Protocol.DOWNED_EXPIRED.equals( t )) {
				l.onDownedExpired( m.str( "player" ) );
			} else if (Protocol.SYNC_START.equals( t )) {
				l.onSyncStart( s.sync() );
			} else if (Protocol.SYNC_END.equals( t )) {
				l.onSyncEnd( m.str( "syncId" ), m.num( "hpLost" ), m.str( "reason" ) );
			}
		}
	}

	// ---- every frame (MissionHud.update) ----

	public static void tick( long now ) {
		MissionState s = state();
		if (s == null) return;

		boolean up = linkUp();
		if (!up && linkDownSince < 0) {
			linkDownSince = now;
		} else if (up && linkDownSince >= 0) {
			s.shiftDowned( now - linkDownSince );
			linkDownSince = -1;
		}

		Hero hero = Dungeon.hero;
		boolean waiting = hero != null && hero.ready && hero.isAlive();
		if (forced.tick( waiting, s.clockRunning( up ) )) {
			forceWait( hero );
		}
		applyEnds( s, hero, sceneSwitch );
	}

	//the same as pressing rest once (Toolbar's wait button), whatever window is open (game-design §4.4)
	private static void forceWait( Hero hero ) {
		GameScene.cancel();
		if (hero.ready) hero.rest( false );
	}

	//one pending MISSION_END per call, once the hero waits for input (or is dead)
	static void applyEnds( MissionState s, Hero hero, Runnable switchScene ) {
		MissionState.End end = s.nextEnd();
		if (end == null || hero == null) return;
		boolean alive = hero.isAlive();
		if (alive && !hero.ready) return;
		if (alive && end.failed() && MissionPenalty.travelBlocked()) return;
		s.endApplied( end );
		if (!alive) return;
		if (end.success()) {
			GLog.p( Messages.get( CoopMissions.class, "success" ) );
			reward.grant( end );
		} else if (end.failed()) {
			MissionPenalty.apply( end, switchScene );
		} else if (MissionState.ABORTED.equals( end.result )) {
			GLog.i( Messages.get( CoopMissions.class, "aborted" ) );
		} else {
			GLog.i( Messages.get( CoopMissions.class, "over" ) );
		}
	}
}
