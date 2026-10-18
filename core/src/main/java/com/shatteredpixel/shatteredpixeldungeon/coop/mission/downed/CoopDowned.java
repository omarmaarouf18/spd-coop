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

package com.shatteredpixel.shatteredpixeldungeon.coop.mission.downed;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Invulnerability;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopConnection;
import com.shatteredpixel.shatteredpixeldungeon.coop.ghost.AllyState;
import com.shatteredpixel.shatteredpixeldungeon.coop.ghost.CoopGhosts;
import com.shatteredpixel.shatteredpixeldungeon.coop.ghost.GhostAlly;
import com.shatteredpixel.shatteredpixeldungeon.coop.items.ScrollOfRecall;
import com.shatteredpixel.shatteredpixeldungeon.coop.mission.CoopMissions;
import com.shatteredpixel.shatteredpixeldungeon.coop.mission.ForcedTurns;
import com.shatteredpixel.shatteredpixeldungeon.coop.mission.MissionListener;
import com.shatteredpixel.shatteredpixeldungeon.coop.mission.MissionPenalty;
import com.shatteredpixel.shatteredpixeldungeon.coop.mission.MissionState;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopSession;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Message;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndMessage;
import com.watabou.noosa.Game;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

//facade of Downed & revive (MEC-02, game-design §5, D-021, D-022, PROTOCOL.md §5.8). Every
// // COOP: hook for it calls one method here, and it is a MissionListener (MEC-00) for
// ALLY_DOWNED / REVIVE / DOWNED_EXPIRED / MISSION_END.
//- Hero.die, after the Ankh: in a co-op run the hero is Downed instead (HP 1, paralysed,
//  invulnerable, hidden from mobs) and we send DOWNED (again every RESEND_MS until the server's
//  ALLY_DOWNED for us is in the mirror).
//- Revives are the server's: Scroll of Recall (USE_RECALL via "recall", any floor) or a Potion of
//  Healing thrown onto the ally's ghost (via "healing", same floor, ghost visible). REVIVE for us:
//  hpPct of HT, and with mode "regionStart" Mode.RETURN to our region start.
//- DOWNED_EXPIRED for us: the real vanilla death, on the actor thread. Inside a Cell mission the
//  server sends MISSION_END{failed} instead (D-022): Downed is cleared, 25% HP, MEC-00's penalty.
//Single-player: every hook returns at once.
public final class CoopDowned {

	public static final String VIA_RECALL = "recall";
	public static final String VIA_HEALING = "healing";
	public static final String MODE_REGION_START = "regionStart";
	public static final String MODE_IN_PLACE = "inPlace";
	//§5.2/§5.3 and the D-022 arrival at the region start
	public static final int REVIVE_HP_PCT = 25;

	static final long RESEND_MS = 5_000;
	//no answer to a USE_RECALL for this long (link up): the scroll comes back
	static final long PENDING_MS = 30_000;
	static final float INVULNERABLE_TURNS = 1_000_000f;
	//DENY reasons a USE_RECALL can get (PROTOCOL.md §5.7–§5.8)
	static final Set<String> RECALL_DENIES = new HashSet<>( Arrays.asList(
			"ally_offline", "ally_other_floor", "invalid_state", "downed", "not_downed" ) );

	//the network, the mirror, the clock, the scene; replaceable in tests
	public interface Link {
		String me();
		boolean downed( int depth, int branch, String cause );
		boolean useRecall( int depth, int branch, String target, String via );
		//the ally is online on this floor
		boolean allyOn( int depth, int branch );
		MissionState state();
		boolean clockRunning();
		long now();
		//a world is on screen: Downed turns wait in real time (tests: never)
		boolean paced();
		boolean sleep( long ms );
		void post( Runnable r );
		//Mode.RETURN to that depth's entrance (branch 0)
		void travel( int depth );
		//a clear, blocking message (a window)
		void alert( String text );
		void showFx( String allyId );
		//a throw landing here hits the ally's visible ghost (GhostAlly.isTarget)
		boolean ghostTarget( int cell );
		//the vanilla death (Hero.die)
		void die( Hero hero, Object cause );
	}

	public static final Link LIVE = new Link() {
		@Override
		public String me() {
			return CoopMissions.me();
		}

		@Override
		public boolean downed( int depth, int branch, String cause ) {
			CoopSession s = session();
			return s != null && s.downed( depth, branch, cause );
		}

		@Override
		public boolean useRecall( int depth, int branch, String target, String via ) {
			CoopSession s = session();
			return s != null && s.useRecall( depth, branch, target, via );
		}

		@Override
		public boolean allyOn( int depth, int branch ) {
			AllyState ally = CoopGhosts.ally();
			return session() != null && ally.online() && ally.floor( depth, branch ) == AllyState.Floor.SAME;
		}

		@Override
		public MissionState state() {
			return CoopMissions.state();
		}

		@Override
		public boolean clockRunning() {
			return CoopMissions.clockRunning();
		}

		@Override
		public long now() {
			return System.currentTimeMillis();
		}

		@Override
		public boolean paced() {
			return ShatteredPixelDungeon.scene() instanceof GameScene && !Game.switchingScene();
		}

		@Override
		public boolean sleep( long ms ) {
			try {
				Thread.sleep( ms );
				return true;
			} catch (InterruptedException e) {
				//Actor.process sees it and stops (a scene switch)
				Thread.currentThread().interrupt();
				return false;
			}
		}

		@Override
		public void post( Runnable r ) {
			Game.runOnRenderThread( r::run );
		}

		//the same travel as MEC-00's failed-mission penalty (MissionPenalty): never Dungeon.depth
		@Override
		public void travel( int depth ) {
			Level.beforeTransition();
			InterlevelScene.mode = InterlevelScene.Mode.RETURN;
			InterlevelScene.returnDepth = depth;
			InterlevelScene.returnBranch = 0;
			InterlevelScene.returnPos = -1;
			Game.switchScene( InterlevelScene.class );
		}

		@Override
		public void alert( String text ) {
			if (ShatteredPixelDungeon.scene() instanceof GameScene) GameScene.show( new WndMessage( text ) );
		}

		@Override
		public void showFx( String allyId ) {
			if (ShatteredPixelDungeon.scene() instanceof GameScene) ShatteredPixelDungeon.scene().add( new DownedFx( allyId ) );
		}

		@Override
		public boolean ghostTarget( int cell ) {
			return GhostAlly.isTarget( cell );
		}

		@Override
		public void die( Hero hero, Object cause ) {
			hero.die( cause );
		}

		private CoopSession session() {
			CoopSession s = CoopConnection.session();
			return s != null && s.state() == CoopSession.State.RUNNING ? s : null;
		}
	};

	private static Link link = LIVE;

	static final MissionListener LISTENER = new MissionListener() {
		@Override
		public void onAllyDowned( String playerId, long deadline ) {
			CoopDowned.onAllyDowned( playerId );
		}

		@Override
		public void onRevive( String targetId, String mode, int hpPct ) {
			CoopDowned.onRevive( targetId, mode, hpPct );
		}

		@Override
		public void onDownedExpired( String playerId ) {
			CoopDowned.onDownedExpired( playerId );
		}

		@Override
		public void onMissionEnd( MissionState.End end ) {
			CoopDowned.onMissionEnd( end );
		}
	};

	static {
		CoopMissions.addListener( LISTENER );
	}

	private CoopDowned() {}

	//loads this class (and with it the listener)
	public static void install() {}

	public static void link( Link l ) {
		link = l == null ? LIVE : l;
	}

	static Link link() {
		return link;
	}

	// ---- queries ----

	public static boolean isDowned( Hero hero ) {
		return hero != null && hero.buff( Downed.class ) != null;
	}

	//the server's word (the mirror): this player's window is open
	static boolean isDownedOnServer( String playerId ) {
		MissionState s = link.state();
		return s != null && playerId != null && s.isDowned( playerId );
	}

	//the Downed player we can revive: never ourselves, never a player who is out
	static String downedAlly() {
		MissionState s = link.state();
		if (s == null) return null;
		String me = link.me();
		for (String id : s.downed().keySet()) {
			if (!id.equals( me ) && !s.isOut( id )) return id;
		}
		return null;
	}

	static int regionStart( int depth ) {
		return ((Math.max( 1, depth ) - 1) / 5) * 5 + 1;
	}

	// ---- Hero.die (actor thread) ----

	//true: the death is swallowed (Downed now, or already). False: the vanilla death goes on.
	public static boolean onHeroDying( Hero hero, Object cause ) {
		if (!Coop.isCoopRun() || hero != Dungeon.hero) return false;
		Downed downed = hero.buff( Downed.class );
		if (downed != null) {
			if (dying) return false;
			hero.HP = Math.max( 1, hero.HP );
			return true;
		}
		goDown( hero, cause == null ? "" : cause.getClass().getSimpleName() );
		return true;
	}

	//set while the expired Downed dies for real
	private static volatile boolean dying;

	static Downed goDown( Hero hero, String cause ) {
		hero.HP = 1;
		if (ShatteredPixelDungeon.scene() instanceof GameScene) hero.interrupt();
		Downed downed = Buff.affect( hero, Downed.class );
		downed.cause = cause.length() > 64 ? cause.substring( 0, 64 ) : cause;
		Buff.affect( hero, Invulnerability.class, INVULNERABLE_TURNS );
		GLog.n( Messages.get( CoopDowned.class, "downed" ) );
		resend( downed );
		return downed;
	}

	//DOWNED goes out once, and again only while the server hasn't confirmed our window
	static void resend( Downed downed ) {
		if (downed.expired || isDownedOnServer( link.me() )) return;
		long now = link.now();
		if (downed.sentAt >= 0 && now - downed.sentAt < RESEND_MS) return;
		downed.sentAt = now;
		final int depth = Dungeon.depth, branch = Dungeon.branch;
		final String cause = downed.cause;
		link.post( () -> link.downed( depth, branch, cause.isEmpty() ? null : cause ) );
	}

	//Downed.act: wait one forced-turn period of running clock (actor thread)
	static boolean pace( final Downed downed ) {
		if (!link.paced()) return true;
		return DownedPace.waitTurn( new DownedPace.Clock() {
			@Override
			public long now() {
				return link.now();
			}

			@Override
			public boolean running() {
				return link.clockRunning();
			}

			@Override
			public boolean stillDowned() {
				return downed.active && !downed.expired && link.paced();
			}

			@Override
			public boolean sleep( long ms ) {
				return link.sleep( ms );
			}
		}, ForcedTurns.WAIT_MS );
	}

	//Downed.act after DOWNED_EXPIRED: the real vanilla death (actor thread)
	static void expireNow( Downed downed ) {
		Hero hero = Dungeon.hero;
		if (hero == null || downed.target != hero) {
			downed.detach();
			return;
		}
		standUp( hero );
		hero.HP = 0;
		dying = true;
		try {
			link.die( hero, new DownedExpiry() );
		} finally {
			dying = false;
		}
	}

	//no longer Downed (revive, D-022 failure, death)
	static void standUp( Hero hero ) {
		Downed downed = hero.buff( Downed.class );
		if (downed != null) downed.detach();
		Buff.detach( hero, Invulnerability.class );
	}

	// ---- the ally's revive tools (GL thread) ----

	//ScrollOfRecall READ
	public static boolean readRecall( Hero hero, ScrollOfRecall scroll ) {
		if (!Coop.isCoopRun() || isDowned( hero )) return false;
		String target = downedAlly();
		if (target == null) {
			GLog.w( Messages.get( CoopDowned.class, "no_downed_ally" ) );
			return false;
		}
		if (hero.buff( RecallPending.class ) != null) {
			GLog.w( Messages.get( CoopDowned.class, "recall_pending" ) );
			return false;
		}
		if (!link.useRecall( Dungeon.depth, Dungeon.branch, target, VIA_RECALL )) {
			GLog.w( Messages.get( CoopDowned.class, "not_sent" ) );
			return false;
		}
		RecallPending pending = Buff.affect( hero, RecallPending.class );
		pending.targetId = target;
		pending.scroll = scroll.detach( hero.belongings.backpack );
		pending.sentAt = link.now();
		GLog.i( Messages.get( CoopDowned.class, "recall_read" ) );
		hero.spendAndNext( 1f );
		return true;
	}

	//Potion.onThrow: a Potion of Healing landing on the Downed ally's visible ghost revives them.
	// True: it was ours (consumed here, no shatter). The item was already taken out of the pack.
	public static boolean onPotionThrown( Potion potion, int cell ) {
		if (!Coop.isCoopRun() || potion.getClass() != PotionOfHealing.class) return false;
		String target = downedAlly();
		if (target == null || !link.ghostTarget( cell ) || !link.allyOn( Dungeon.depth, Dungeon.branch )) return false;
		if (!link.useRecall( Dungeon.depth, Dungeon.branch, target, VIA_HEALING )) return false;
		GLog.p( Messages.get( CoopDowned.class, "healing_thrown" ) );
		return true;
	}

	//RecallPending.act: no answer at all for PENDING_MS of link time
	static void checkPending( RecallPending pending ) {
		if (!link.clockRunning()) {
			pending.sentAt = -1;
			return;
		}
		long now = link.now();
		if (pending.sentAt < 0) pending.sentAt = now;
		if (!isDownedOnServer( pending.targetId ) || now - pending.sentAt >= PENDING_MS) {
			resolvePending( false );
		}
	}

	//consumed: the scroll did its job; otherwise it comes back to the pack
	static void resolvePending( boolean consumed ) {
		Hero hero = Dungeon.hero;
		RecallPending pending = hero == null ? null : hero.buff( RecallPending.class );
		if (pending == null) return;
		pending.detach();
		Item scroll = pending.scroll;
		if (consumed || scroll == null) return;
		if (!scroll.collect()) Dungeon.level.drop( scroll, hero.pos );
		GLog.i( Messages.get( CoopDowned.class, "recall_back" ) );
	}

	// ---- server events (GL thread) ----

	//CoopConnection: a DENY while our USE_RECALL waits gives the scroll back
	public static void onServerEvent( Message m ) {
		if (!Coop.isCoopRun() || !Protocol.DENY.equals( m.t )) return;
		Hero hero = Dungeon.hero;
		if (hero == null || hero.buff( RecallPending.class ) == null || !RECALL_DENIES.contains( m.str( "reason" ) )) return;
		GLog.w( Messages.get( CoopDowned.class, "recall_denied" ) );
		resolvePending( false );
	}

	static void onAllyDowned( String playerId ) {
		if (!Coop.isCoopRun()) return;
		Hero hero = Dungeon.hero;
		if (playerId.equals( link.me() )) {
			//the server knows us as Downed (a resume onto an older save): be Downed here too
			if (hero != null && hero.isAlive() && !isDowned( hero )) goDown( hero, "" );
			return;
		}
		GLog.n( Messages.get( CoopDowned.class, "ally_downed" ) );
		link.showFx( playerId );
	}

	static void onRevive( String targetId, String mode, int hpPct ) {
		if (!Coop.isCoopRun()) return;
		Hero hero = Dungeon.hero;
		if (hero == null) return;
		if (!targetId.equals( link.me() )) {
			GLog.p( Messages.get( CoopDowned.class, "ally_revived" ) );
			RecallPending pending = hero.buff( RecallPending.class );
			if (pending != null && targetId.equals( pending.targetId )) resolvePending( true );
			return;
		}
		if (!isDowned( hero )) return;
		standUp( hero );
		hero.HP = Math.max( 1, Math.min( hero.HT, Math.round( hero.HT * hpPct / 100f ) ) );
		GLog.p( Messages.get( CoopDowned.class, "revived" ) );
		if (MODE_REGION_START.equals( mode ) && !MissionPenalty.travelBlocked()) {
			link.travel( regionStart( Dungeon.depth ) );
		}
	}

	static void onDownedExpired( String playerId ) {
		if (!Coop.isCoopRun()) return;
		Hero hero = Dungeon.hero;
		if (hero == null) return;
		if (playerId.equals( link.me() )) {
			Downed downed = hero.buff( Downed.class );
			if (downed == null && hero.isAlive()) downed = goDown( hero, "" );
			if (downed != null) downed.expired = true;
			return;
		}
		RecallPending pending = hero.buff( RecallPending.class );
		if (pending != null && playerId.equals( pending.targetId )) resolvePending( false );
		GLog.n( Messages.get( CoopDowned.class, "ally_fell" ) );
		link.alert( Messages.get( CoopDowned.class, "ally_fell_window" ) );
	}

	//D-022: a failed Cell clears the prisoner's Downed (no REVIVE, no DOWNED_EXPIRED comes); the
	// prisoner gets 25% HP and MEC-00's penalty takes both to the region start
	static void onMissionEnd( MissionState.End end ) {
		if (!Coop.isCoopRun() || !end.failed() || end.mission == null) return;
		String prisoner = end.mission.prisoner;
		MissionState s = link.state();
		if (s != null) s.downed().remove( prisoner );
		Hero hero = Dungeon.hero;
		if (hero == null) return;
		if (prisoner.equals( link.me() )) {
			if (isDowned( hero )) {
				standUp( hero );
				hero.HP = Math.max( 1, Math.round( hero.HT * REVIVE_HP_PCT / 100f ) );
			}
		} else {
			RecallPending pending = hero.buff( RecallPending.class );
			if (pending != null && prisoner.equals( pending.targetId )) resolvePending( false );
		}
	}
}
