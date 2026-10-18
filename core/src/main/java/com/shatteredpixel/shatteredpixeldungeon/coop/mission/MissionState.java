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

import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Message;
import com.watabou.utils.Bundlable;
import com.watabou.utils.Bundle;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

//the client's mirror of the server's shared timed state (MEC-00): the active mission
// (MISSION_START/UPDATE/END), Downed windows (ALLY_DOWNED/REVIVE/DOWNED_EXPIRED) and the Super
// Sync session (SYNC_START/END). State only: behavior lives in CoopMissions and the mechanics
// built on it (MEC-01, MEC-02, SYN-01).
//Saved with the run (CoopRun, key "coop_missions"). On resume the server's replayed reliable
// events run through apply() again, so every transition is idempotent: a replayed MISSION_END or
// MISSION_START of a mission that already ended is ignored (ended ids), and a MISSION_UPDATE for
// another mission is ignored.
//Times: deadline/startsAt are server Unix ms; remaining times are computed from a server "now"
// (ServerClock), never from the turn clock.
public class MissionState implements Bundlable {

	public static final String CELL = "CELL";

	public static final String SUCCESS = "success";
	public static final String REVIVED = "revived";
	public static final String FAILED = "failed";
	public static final String ABORTED = "aborted";

	//how many ended mission ids we remember to drop replays
	static final int ENDED_MEMORY = 16;

	public static class Mission implements Bundlable {
		public String id = "";
		public String kind = "";
		public String prisoner = "";
		public String rescuer = "";
		public int depth;
		public int branch;
		public int trapCell;
		public long roomId;
		public long keySeed;
		public long startsAt;
		//server ms; while running, remaining = deadline − server now
		public long deadline;
		public long waveIntervalMs;
		public boolean paused;
		//the server's remaining time, frozen while paused
		public long pausedRemainingMs;
		//task flags from MISSION_UPDATE.tasks, sorted by name for a stable HUD
		public final TreeMap<String, Boolean> tasks = new TreeMap<>();

		public long remainingMs( long serverNow ) {
			return paused ? pausedRemainingMs : Math.max( 0, deadline - serverNow );
		}

		@Override
		public void storeInBundle( Bundle b ) {
			b.put( "id", id );
			b.put( "kind", kind );
			b.put( "prisoner", prisoner );
			b.put( "rescuer", rescuer );
			b.put( "depth", depth );
			b.put( "branch", branch );
			b.put( "trap_cell", trapCell );
			b.put( "room_id", roomId );
			b.put( "key_seed", keySeed );
			b.put( "starts_at", startsAt );
			b.put( "deadline", deadline );
			b.put( "wave_ms", waveIntervalMs );
			b.put( "paused", paused );
			b.put( "paused_remaining", pausedRemainingMs );
			String[] names = tasks.keySet().toArray( new String[0] );
			boolean[] done = new boolean[names.length];
			for (int i = 0; i < names.length; i++) done[i] = tasks.get( names[i] );
			b.put( "task_names", names );
			b.put( "task_done", done );
		}

		@Override
		public void restoreFromBundle( Bundle b ) {
			id = b.getString( "id" );
			kind = b.getString( "kind" );
			prisoner = b.getString( "prisoner" );
			rescuer = b.getString( "rescuer" );
			depth = b.getInt( "depth" );
			branch = b.getInt( "branch" );
			trapCell = b.getInt( "trap_cell" );
			roomId = b.getLong( "room_id" );
			keySeed = b.getLong( "key_seed" );
			startsAt = b.getLong( "starts_at" );
			deadline = b.getLong( "deadline" );
			waveIntervalMs = b.getLong( "wave_ms" );
			paused = b.getBoolean( "paused" );
			pausedRemainingMs = b.getLong( "paused_remaining" );
			tasks.clear();
			if (b.contains( "task_names" )) {
				String[] names = b.getStringArray( "task_names" );
				boolean[] done = b.getBooleanArray( "task_done" );
				for (int i = 0; i < names.length && i < done.length; i++) tasks.put( names[i], done[i] );
			}
		}
	}

	//a MISSION_END waiting to be applied (CoopMissions does it once the hero can travel)
	public static class End implements Bundlable {
		public String missionId = "";
		public String result = "";
		//"failed" only
		public int regionStartDepth;
		public int goldPct;
		//"success" only
		public long rewardSeed;
		//the mission as we knew it; null when the END came for a mission we never saw
		public Mission mission;

		public boolean failed() {
			return FAILED.equals( result );
		}

		public boolean success() {
			return SUCCESS.equals( result );
		}

		@Override
		public void storeInBundle( Bundle b ) {
			b.put( "id", missionId );
			b.put( "result", result );
			b.put( "region_start", regionStartDepth );
			b.put( "gold_pct", goldPct );
			b.put( "reward_seed", rewardSeed );
			if (mission != null) b.put( "mission", mission );
		}

		@Override
		public void restoreFromBundle( Bundle b ) {
			missionId = b.getString( "id" );
			result = b.getString( "result" );
			regionStartDepth = b.getInt( "region_start" );
			goldPct = b.getInt( "gold_pct" );
			rewardSeed = b.getLong( "reward_seed" );
			mission = b.contains( "mission" ) ? (Mission) b.get( "mission" ) : null;
		}
	}

	public static class Sync implements Bundlable {
		public String id = "";
		public String snapshot = "";
		public long turns;

		@Override
		public void storeInBundle( Bundle b ) {
			b.put( "id", id );
			b.put( "snapshot", snapshot );
			b.put( "turns", turns );
		}

		@Override
		public void restoreFromBundle( Bundle b ) {
			id = b.getString( "id" );
			snapshot = b.getString( "snapshot" );
			turns = b.getLong( "turns" );
		}
	}

	private Mission mission;
	private final List<End> pendingEnds = new ArrayList<>();
	private final List<String> ended = new ArrayList<>();
	//Downed players → their window's deadline (server ms)
	private final TreeMap<String, Long> downed = new TreeMap<>();
	//players whose Downed window expired: out for the rest of the run (PROTOCOL.md §5.8)
	private final Set<String> out = new LinkedHashSet<>();
	private Sync sync;

	public Mission mission() {
		return mission;
	}

	public List<End> pendingEnds() {
		return pendingEnds;
	}

	public End nextEnd() {
		return pendingEnds.isEmpty() ? null : pendingEnds.get( 0 );
	}

	public void endApplied( End end ) {
		pendingEnds.remove( end );
	}

	public boolean hasEnded( String missionId ) {
		return ended.contains( missionId );
	}

	public Map<String, Long> downed() {
		return downed;
	}

	public boolean isDowned( String playerId ) {
		return downed.containsKey( playerId );
	}

	public long downedRemainingMs( String playerId, long serverNow ) {
		Long deadline = downed.get( playerId );
		return deadline == null ? 0 : Math.max( 0, deadline - serverNow );
	}

	public boolean isOut( String playerId ) {
		return out.contains( playerId );
	}

	public Sync sync() {
		return sync;
	}

	//a real-time clock is ticking on the server: the mission's, or a Downed window
	public boolean clockActive() {
		return mission != null || !downed.isEmpty();
	}

	//forced turns run (D-005) while some clock is active and not paused. linkUp: we are connected
	// and so is the ally; the server freezes every clock otherwise, even before it can tell us.
	public boolean clockRunning( boolean linkUp ) {
		if (!linkUp) return false;
		return (mission != null && !mission.paused) || !downed.isEmpty();
	}

	//the server froze the Downed windows while the link was down for pausedMs; it sends no new
	// deadline when it resumes them, so we move ours by the time we saw it paused
	public void shiftDowned( long pausedMs ) {
		if (pausedMs <= 0) return;
		for (Map.Entry<String, Long> e : downed.entrySet()) e.setValue( e.getValue() + pausedMs );
	}

	//apply one s2c message; serverNow = the server's clock now (for MISSION_UPDATE.remainingMs).
	//Returns false when the message was ignored (not ours, stale or a replay already applied).
	public boolean apply( Message m, long serverNow ) {
		String t = m.t;
		if (Protocol.MISSION_START.equals( t )) {
			return start( m );
		} else if (Protocol.MISSION_UPDATE.equals( t )) {
			return update( m, serverNow );
		} else if (Protocol.MISSION_END.equals( t )) {
			return end( m );
		} else if (Protocol.ALLY_DOWNED.equals( t )) {
			String p = m.str( "player" );
			if (out.contains( p )) return false;
			downed.put( p, m.num( "deadline" ) );
			return true;
		} else if (Protocol.REVIVE.equals( t )) {
			return downed.remove( m.str( "target" ) ) != null;
		} else if (Protocol.DOWNED_EXPIRED.equals( t )) {
			String p = m.str( "player" );
			downed.remove( p );
			return out.add( p );
		} else if (Protocol.SYNC_START.equals( t )) {
			Sync s = new Sync();
			s.id = m.str( "syncId" );
			s.snapshot = m.str( "snapshot" );
			s.turns = m.num( "turns" );
			sync = s;
			return true;
		} else if (Protocol.SYNC_END.equals( t )) {
			if (sync == null || !sync.id.equals( m.str( "syncId" ) )) return false;
			sync = null;
			return true;
		}
		return false;
	}

	private boolean start( Message m ) {
		String id = m.str( "missionId" );
		if (hasEnded( id )) return false;
		Mission mi = mission != null && mission.id.equals( id ) ? mission : new Mission();
		mi.id = id;
		mi.kind = m.str( "kind" );
		mi.prisoner = m.str( "prisoner" );
		mi.rescuer = m.str( "rescuer" );
		mi.depth = (int) m.num( "depth" );
		mi.branch = (int) m.num( "branch" );
		mi.trapCell = (int) m.num( "trapCell" );
		mi.roomId = m.num( "roomId" );
		mi.keySeed = m.num( "keySeed" );
		mi.startsAt = m.num( "startsAt" );
		mi.deadline = m.num( "deadline" );
		mi.waveIntervalMs = m.num( "waveIntervalMs" );
		if (mi != mission) {
			mi.paused = false;
			mi.pausedRemainingMs = 0;
		}
		mission = mi;
		return true;
	}

	private boolean update( Message m, long serverNow ) {
		if (mission == null || !mission.id.equals( m.str( "missionId" ) )) return false;
		long remaining = m.num( "remainingMs" );
		mission.paused = m.bool( "paused" );
		if (mission.paused) {
			mission.pausedRemainingMs = remaining;
		} else {
			//remainingMs is the truth after a pause (the server moved its deadline)
			mission.deadline = serverNow + remaining;
		}
		Map<String, Object> tasks = m.obj( "tasks" );
		if (tasks != null) {
			for (Map.Entry<String, Object> e : tasks.entrySet()) {
				if (e.getValue() instanceof Boolean) mission.tasks.put( e.getKey(), (Boolean) e.getValue() );
			}
		}
		return true;
	}

	private boolean end( Message m ) {
		String id = m.str( "missionId" );
		if (hasEnded( id )) return false;
		End e = new End();
		e.missionId = id;
		e.result = m.str( "result" );
		Map<String, Object> penalty = m.obj( "penalty" );
		if (penalty != null) {
			e.regionStartDepth = (int) longOf( penalty.get( "regionStartDepth" ) );
			e.goldPct = (int) longOf( penalty.get( "goldPct" ) );
		}
		Map<String, Object> reward = m.obj( "reward" );
		if (reward != null) e.rewardSeed = longOf( reward.get( "rewardSeed" ) );
		if (mission != null && mission.id.equals( id )) {
			e.mission = mission;
			mission = null;
		}
		ended.add( id );
		while (ended.size() > ENDED_MEMORY) ended.remove( 0 );
		pendingEnds.add( e );
		return true;
	}

	private static long longOf( Object o ) {
		return o == null ? 0 : Long.parseLong( o.toString() );
	}

	// ---- Bundlable ----

	private static final String MISSION = "coop_m_mission";
	private static final String ENDS = "coop_m_ends";
	private static final String ENDED = "coop_m_ended";
	private static final String DOWNED_IDS = "coop_m_downed_ids";
	private static final String DOWNED_DEADLINES = "coop_m_downed_deadlines";
	private static final String OUT = "coop_m_out";
	private static final String SYNC = "coop_m_sync";

	@Override
	public void storeInBundle( Bundle bundle ) {
		if (mission != null) bundle.put( MISSION, mission );
		bundle.put( ENDS, pendingEnds );
		bundle.put( ENDED, ended.toArray( new String[0] ) );
		String[] ids = downed.keySet().toArray( new String[0] );
		long[] deadlines = new long[ids.length];
		for (int i = 0; i < ids.length; i++) deadlines[i] = downed.get( ids[i] );
		bundle.put( DOWNED_IDS, ids );
		bundle.put( DOWNED_DEADLINES, deadlines );
		bundle.put( OUT, out.toArray( new String[0] ) );
		if (sync != null) bundle.put( SYNC, sync );
	}

	@Override
	public void restoreFromBundle( Bundle bundle ) {
		mission = bundle.contains( MISSION ) ? (Mission) bundle.get( MISSION ) : null;
		pendingEnds.clear();
		if (bundle.contains( ENDS )) {
			for (Bundlable b : bundle.getCollection( ENDS )) pendingEnds.add( (End) b );
		}
		ended.clear();
		if (bundle.contains( ENDED )) {
			for (String s : bundle.getStringArray( ENDED )) ended.add( s );
		}
		downed.clear();
		if (bundle.contains( DOWNED_IDS )) {
			String[] ids = bundle.getStringArray( DOWNED_IDS );
			long[] deadlines = bundle.getLongArray( DOWNED_DEADLINES );
			for (int i = 0; i < ids.length && i < deadlines.length; i++) downed.put( ids[i], deadlines[i] );
		}
		out.clear();
		if (bundle.contains( OUT )) {
			for (String s : bundle.getStringArray( OUT )) out.add( s );
		}
		sync = bundle.contains( SYNC ) ? (Sync) bundle.get( SYNC ) : null;
	}
}
