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

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopConnection;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopSession;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Message;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.noosa.Game;
import com.watabou.utils.DeviceCompat;

import java.util.List;
import java.util.Locale;
import java.util.Map;

//presence in a co-op run (GHO-01): our GHOST out, the ally's GHOST/MARK/QUICK/ALLY_STATUS in.
//Visual and social only: nothing here changes the world, consumes gameplay Random or is saved.
//Threads: the Hero hooks run on the actor thread and only take a snapshot, which is posted to the
// GL thread; everything else (network, state, UI) runs on the GL thread.
public final class CoopGhosts {

	private static final String TAG = "CoopGhosts";
	//a quick message stays on screen this long
	public static final long TOAST_MS = 3_000;

	private static final AllyState ally = new AllyState();
	private static final Marks marks = new Marks();
	private static final PresenceSender sender = new PresenceSender( CoopGhosts::sendGhost );

	//the ghost is drawn fully opaque (Twin Switches room, game-design §6A.5): set by later tasks
	private static boolean opaque;

	//actor thread: what the hero did since its last turn
	private static volatile String pendingAnim;
	private static int lastTurnPos = -1;
	private static int lastTurnDepth = -1;
	private static float lastTurnTime = -1;

	//GL thread: change detection for tick()
	private static String runId;
	private static CoopSession.State lastState;
	private static Map<String, Object> lastRoom;

	//newest quick message, for the toast
	private static String toast;
	private static long toastAt;

	private CoopGhosts() {}

	public static AllyState ally() {
		return ally;
	}

	public static Marks marks() {
		return marks;
	}

	public static PresenceSender sender() {
		return sender;
	}

	public static boolean opaque() {
		return opaque;
	}

	public static void opaque( boolean value ) {
		opaque = value;
	}

	//forget the previous run's ally when another co-op run is being played (GL thread)
	public static void syncRun() {
		String current = Coop.run() == null ? null : Coop.run().runId;
		if (current == null ? runId != null : !current.equals( runId )) {
			reset();
			runId = current;
		}
	}

	public static void reset() {
		ally.clear();
		marks.clear();
		sender.reset();
		opaque = false;
		pendingAnim = null;
		lastTurnPos = lastTurnDepth = -1;
		lastTurnTime = -1;
		lastRoom = null;
		lastState = null;
		toast = null;
	}

	// ---- our hero (Hero hooks, actor thread) ----

	//Hero.act(): the hero's previous action has resolved, so pos is the new cell
	public static void onHeroTurn( Hero hero ) {
		HeroSnapshot s = turnSnapshot( hero );
		if (s != null) post( s );
	}

	//the GHOST for this turn: hero.pos is already the cell the last action ended on
	static HeroSnapshot turnSnapshot( Hero hero ) {
		if (hero != Dungeon.hero || Dungeon.level == null) return null;
		String anim = pendingAnim;
		pendingAnim = null;
		if (anim == null) anim = inferAnim( hero );
		lastTurnPos = hero.pos;
		lastTurnDepth = Dungeon.depth;
		lastTurnTime = Actor.now();
		return snapshot( hero, anim );
	}

	//Hero.onAttackComplete / onOperateComplete / die: remembered for the next turn's GHOST;
	// death has no next turn, so it goes out now
	public static void onHeroAnim( Hero hero, String anim ) {
		if (hero != Dungeon.hero || Dungeon.level == null) return;
		if (HeroSnapshot.DIE.equals( anim )) {
			pendingAnim = null;
			post( snapshot( hero, anim ) );
		} else {
			pendingAnim = anim;
		}
	}

	//a zap/throw doesn't tell the Hero anything: it shows as a non-looping sprite animation on a
	// turn where the hero spent time without moving, attacking or operating
	private static String inferAnim( Hero hero ) {
		if (!hero.isAlive()) return HeroSnapshot.DIE;
		if (lastTurnDepth == Dungeon.depth && lastTurnPos >= 0 && hero.pos != lastTurnPos) return HeroSnapshot.RUN;
		if (Actor.now() > lastTurnTime && lastTurnTime >= 0 && hero.sprite != null && !hero.sprite.looping()) {
			return HeroSnapshot.ZAP;
		}
		return HeroSnapshot.IDLE;
	}

	public static HeroSnapshot snapshot( Hero hero, String anim ) {
		return new HeroSnapshot( Dungeon.depth, Dungeon.branch, hero.pos, anim,
				hero.isAlive() ? HeroSnapshot.hpPct( hero.HP, hero.HT ) : 0,
				hero.heroClass.name(), hero.tier(), Actor.now() );
	}

	private static void post( final HeroSnapshot s ) {
		if (Gdx.app == null) return;
		Game.runOnRenderThread( () -> sender.offer( s, System.currentTimeMillis() ) );
	}

	private static boolean sendGhost( HeroSnapshot s ) {
		CoopSession session = running();
		return session != null && session.ghost( s.depth, s.branch, s.pos, s.anim, s.hpPct, s.heroClass, s.tier );
	}

	private static CoopSession running() {
		CoopSession session = CoopConnection.session();
		return session != null && session.state() == CoopSession.State.RUNNING ? session : null;
	}

	public static boolean sessionRunning() {
		return running() != null;
	}

	// ---- GL thread ----

	//GameScene created for a level: our position goes out now, whatever the throttle says
	public static void onLevelShown() {
		syncRun();
		sender.force();
		if (Dungeon.hero != null && Dungeon.level != null) {
			sender.offer( snapshot( Dungeon.hero, Dungeon.hero.isAlive() ? HeroSnapshot.IDLE : HeroSnapshot.DIE ),
					System.currentTimeMillis() );
		}
	}

	//every frame while a co-op GameScene is up
	public static void tick( long now ) {
		CoopSession.State state = CoopConnection.state();
		if (state == CoopSession.State.RUNNING && lastState != CoopSession.State.RUNNING) {
			//we (re)joined the run: tell the ally where we are
			sender.force();
		}
		lastState = state;

		Map<String, Object> room = CoopConnection.room();
		if (room != null && room != lastRoom) {
			lastRoom = room;
			Boolean c = allyConnectedIn( room, CoopConnection.playerId() );
			if (c != null) ally.onStatus( c, 0 );
		}
		sender.tick( now );
		marks.prune( now );
	}

	@SuppressWarnings("unchecked")
	static Boolean allyConnectedIn( Map<String, Object> room, String me ) {
		Object players = room.get( "players" );
		if (!(players instanceof List) || me == null) return null;
		for (Object o : (List<Object>) players) {
			if (!(o instanceof Map)) continue;
			Map<String, Object> p = (Map<String, Object>) o;
			if (!me.equals( p.get( "playerId" ) ) && p.get( "connected" ) instanceof Boolean) {
				return (Boolean) p.get( "connected" );
			}
		}
		return null;
	}

	//CoopConnection: relayed GHOST / MARK / QUICK
	public static void onPresence( Message m ) {
		syncRun();
		long now = System.currentTimeMillis();
		if (Protocol.GHOST.equals( m.t )) {
			if (m.depth == null || m.branch == null) return;
			ally.onGhost( new HeroSnapshot( m.depth, m.branch, (int) m.num( "pos" ), m.str( "anim" ),
					(int) m.num( "hpPct" ), m.str( "heroClass" ), (int) m.num( "tier" ), 0 ), now );
		} else if (Protocol.MARK.equals( m.t )) {
			if (m.depth == null || m.branch == null) return;
			ally.onFloor( m.depth, m.branch, now );
			Marks.Mark mark = marks.receive( m.depth, m.branch, (int) m.num( "cell" ),
					Marks.Kind.fromWire( m.str( "kind" ) ), Dungeon.depth, Dungeon.branch, now );
			if (mark != null) {
				GLog.h( Messages.get( CoopGhosts.class, "ping_log", allyName(), kindName( mark.kind ) ) );
			}
		} else if (Protocol.QUICK.equals( m.t )) {
			String text = QuickPhrases.text( (int) m.num( "phraseId" ) );
			if (text == null) return;
			GLog.h( Messages.get( CoopGhosts.class, "quick_log", allyName(), text ) );
			toast = Messages.get( CoopGhosts.class, "quick_toast", allyName(), text );
			toastAt = now;
		}
	}

	//CoopConnection: ALLY_STATUS
	public static void onAllyStatus( Message m ) {
		syncRun();
		boolean was = ally.online();
		boolean now = m.bool( "connected" );
		ally.onStatus( now, m.num( "lastSeen" ) );
		if (now && !was) {
			//the ally is back: it needs our position (it only keeps what it last received)
			sender.force();
		}
		log( "ally " + (now ? "online" : "offline") );
	}

	// ---- our pings and quick messages ----

	//false when rate-limited; the mark shows locally even when the ally can't get it
	public static boolean ping( int cell, Marks.Kind kind ) {
		if (Dungeon.level == null || cell < 0 || cell >= Dungeon.level.length()) return false;
		Marks.Mark mark = marks.placeLocal( Dungeon.depth, Dungeon.branch, cell, kind, System.currentTimeMillis() );
		if (mark == null) return false;
		CoopSession session = running();
		if (session != null) session.mark( Dungeon.depth, Dungeon.branch, cell, kind.wire() );
		return true;
	}

	public static boolean quick( int phraseId ) {
		if (!QuickPhrases.valid( phraseId )) return false;
		CoopSession session = running();
		boolean sent = session != null && session.quick( phraseId, Dungeon.depth, Dungeon.branch );
		if (sent) {
			GLog.i( Messages.get( CoopGhosts.class, "quick_sent", QuickPhrases.text( phraseId ) ) );
		} else {
			GLog.w( Messages.get( CoopGhosts.class, "not_sent" ) );
		}
		return sent;
	}

	public static String toast( long now ) {
		return toast != null && now - toastAt < TOAST_MS ? toast : null;
	}

	public static long toastAt() {
		return toastAt;
	}

	// ---- names ----

	public static String allyName() {
		HeroSnapshot g = ally.ghost();
		HeroClass cls = g == null ? null : heroClass( g.heroClass );
		if (cls == null) return Messages.get( CoopGhosts.class, "ally" );
		return Messages.get( CoopGhosts.class, "ally_named", Messages.titleCase( cls.title() ) );
	}

	public static String kindName( Marks.Kind kind ) {
		return Messages.get( CoopGhosts.class, "mark_" + kind.name().toLowerCase( Locale.ROOT ) );
	}

	public static HeroClass heroClass( String name ) {
		if (name == null) return null;
		for (HeroClass c : HeroClass.values()) {
			if (c.name().equals( name )) return c;
		}
		return null;
	}

	private static void log( String message ) {
		if (Gdx.app != null && Game.version != null && DeviceCompat.isDebug()) {
			Gdx.app.debug( TAG, message );
		}
	}
}
