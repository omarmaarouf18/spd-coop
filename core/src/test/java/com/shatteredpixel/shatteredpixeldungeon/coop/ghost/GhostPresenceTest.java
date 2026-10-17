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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopSession;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.FrameLink;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Direction;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Message;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.HeadlessEnv;
import com.shatteredpixel.shatteredpixeldungeon.coop.ui.AllyCompass;
import com.shatteredpixel.shatteredpixeldungeon.coop.ui.AllyStatusChip;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.SewerLevel;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.watabou.utils.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

//the ghost end to end inside one client: what goes on the wire after a move, floor filtering
// (other depth → hidden + "↓N"), never drawn on unseen cells, offline status, no gameplay RNG
class GhostPresenceTest {

	private static final String PID = "a1b2c3d4e5f60718";
	private static final String ALLY = "b2c3d4e5f6071829";
	private static final String TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

	private Hero hero;
	private Level level;

	@BeforeEach
	void setUp() {
		HeadlessEnv.start();
		Messages.setup( Languages.ENGLISH );
		CoopGhosts.reset();
		hero = new Hero();
		hero.heroClass = HeroClass.MAGE;
		hero.HP = hero.HT = 20;
		level = new SewerLevel();
		level.setSize( 10, 10 );
		Dungeon.hero = hero;
		Dungeon.level = level;
		Dungeon.depth = 3;
		Dungeon.branch = 0;
	}

	@AfterEach
	void tearDown() throws Exception {
		setActorTime( 0f );
		CoopGhosts.reset();
		Dungeon.hero = null;
		Dungeon.level = null;
	}

	// ---- sending ----

	@Test
	void theCellAfterTheMoveIsWhatGetsSent() throws Exception {
		Wire wire = new Wire();
		PresenceSender sender = new PresenceSender( s -> wire.session.ghost( s.depth, s.branch, s.pos, s.anim, s.hpPct, s.heroClass, s.tier ) );

		hero.pos = 44;
		sender.offer( CoopGhosts.turnSnapshot( hero ), 1_000 );
		//the hero steps from 44 to 45; Hero.act() runs again once that move has resolved, a turn later
		hero.pos = 45;
		setActorTime( 1f );
		sender.offer( CoopGhosts.turnSnapshot( hero ), 1_100 );

		Message last = wire.last();
		assertEquals( Protocol.GHOST, last.t );
		assertEquals( 45, last.num( "pos" ), "the new cell, not the one the move started from" );
		assertEquals( "run", last.str( "anim" ) );
		assertEquals( 3, (int) last.depth );
		assertEquals( 0, (int) last.branch );
		assertEquals( "MAGE", last.str( "heroClass" ) );
		assertEquals( 100, last.num( "hpPct" ) );
		assertEquals( 0, last.num( "tier" ) );
	}

	@Test
	void animationsFromTheHeroHooks() {
		hero.pos = 10;
		assertEquals( "idle", CoopGhosts.turnSnapshot( hero ).anim );
		CoopGhosts.onHeroAnim( hero, HeroSnapshot.ATTACK );
		assertEquals( "attack", CoopGhosts.turnSnapshot( hero ).anim );
		assertEquals( "idle", CoopGhosts.turnSnapshot( hero ).anim, "consumed by one turn" );
		CoopGhosts.onHeroAnim( hero, HeroSnapshot.OPERATE );
		assertEquals( "operate", CoopGhosts.turnSnapshot( hero ).anim );
		hero.HP = 7;
		assertEquals( 35, CoopGhosts.turnSnapshot( hero ).hpPct );
	}

	@Test
	void onlyTheRealHeroIsReported() {
		//co-op level gen runs with a neutral Hero (DET-01): it must never be sent
		Hero neutral = new Hero();
		assertEquals( null, CoopGhosts.turnSnapshot( neutral ) );
	}

	// ---- receiving: floors ----

	@Test
	void otherDepthHidesTheGhostAndTheCompassShowsFloors() {
		AllyState ally = new AllyState();
		ally.onGhost( new HeroSnapshot( 4, 0, 33, "run", 80, "WARRIOR", 1, 0 ), 1 );
		level.visited[33] = true;

		assertEquals( AllyState.Floor.BELOW, ally.floor( 3, 0 ) );
		assertFalse( GhostLayer.shouldDraw( ally.ghost(), level, 3, 0 ), "ally one floor down: hidden" );
		assertEquals( "↓1", AllyCompass.floorsText( ally, 3, 0 ) );
		assertEquals( "↑2", AllyCompass.floorsText( ally, 6, 0 ) );
		assertEquals( "≠", AllyCompass.floorsText( ally, 4, 1 ), "same depth, other branch" );
		assertEquals( "", AllyCompass.floorsText( ally, 4, 0 ), "same floor: an arrow instead" );
		assertTrue( GhostLayer.shouldDraw( ally.ghost(), level, 4, 0 ) );
	}

	@Test
	void ghostAllyApiFollowsTheFloor() {
		CoopGhosts.ally().onGhost( new HeroSnapshot( 3, 0, 21, "idle", 90, "ROGUE", 0, 0 ), 1 );
		level.visited[21] = true;
		assertEquals( 21, GhostAlly.cell() );
		assertTrue( GhostAlly.isVisible() );
		assertEquals( 21, GhostAlly.targetCell() );
		assertTrue( GhostAlly.isTarget( 21 ) );
		assertFalse( GhostAlly.isTarget( 22 ) );

		Dungeon.depth = 2;
		assertEquals( -1, GhostAlly.cell() );
		assertFalse( GhostAlly.isVisible() );
		assertEquals( -1, GhostAlly.targetCell() );

		GhostAlly.setOpaque( true );
		assertTrue( GhostAlly.isOpaque() );
		GhostAlly.setOpaque( false );
		assertFalse( GhostAlly.isOpaque() );
	}

	// ---- receiving: never reveal unseen cells ----

	@Test
	void neverDrawnOnACellWeHaventSeen() {
		HeroSnapshot g = new HeroSnapshot( 3, 0, 55, "idle", 100, "MAGE", 0, 0 );
		assertFalse( GhostLayer.shouldDraw( g, level, 3, 0 ), "unseen cell" );
		level.mapped[55] = true;
		assertTrue( GhostLayer.shouldDraw( g, level, 3, 0 ), "mapped (e.g. magic mapping)" );
		level.mapped[55] = false;
		level.visited[55] = true;
		assertTrue( GhostLayer.shouldDraw( g, level, 3, 0 ), "visited" );
		assertFalse( GhostLayer.shouldDraw( new HeroSnapshot( 3, 0, 500, "idle", 100, "MAGE", 0, 0 ), level, 3, 0 ),
				"a cell outside our level" );
	}

	// ---- receiving: messages and status ----

	@Test
	void relayedGhostAndMarkUpdateTheAlly() {
		Message ghost = new Message( Protocol.GHOST ).put( "pos", 12 ).put( "anim", "run" ).put( "hpPct", 64 )
				.put( "heroClass", "HUNTRESS" ).put( "tier", 2 );
		ghost.depth = 3;
		ghost.branch = 0;
		ghost.from = ALLY;
		CoopGhosts.onPresence( ghost );
		assertEquals( 12, CoopGhosts.ally().ghost().pos );
		assertEquals( "HUNTRESS", CoopGhosts.ally().ghost().heroClass );

		Message mark = new Message( Protocol.MARK ).put( "cell", 30 ).put( "kind", "DANGER" );
		mark.depth = 3;
		mark.branch = 0;
		CoopGhosts.onPresence( mark );
		assertEquals( 1, CoopGhosts.marks().active( 3, 0, System.currentTimeMillis() ).size() );

		Message elsewhere = new Message( Protocol.MARK ).put( "cell", 31 ).put( "kind", "GO" );
		elsewhere.depth = 5;
		elsewhere.branch = 0;
		CoopGhosts.onPresence( elsewhere );
		assertEquals( 1, CoopGhosts.marks().active( 3, 0, System.currentTimeMillis() ).size(), "other floor ignored" );
		assertEquals( 5, CoopGhosts.ally().depth(), "but the compass learns the ally's floor" );
	}

	@Test
	void offlineStatusAndLastSeen() {
		AllyState ally = new AllyState();
		String[] l = AllyStatusChip.lines( ally, true, 1_000_000 );
		assertEquals( "online", l[1], "no status yet: assume the run start (both connected)" );

		ally.onGhost( new HeroSnapshot( 3, 0, 1, "idle", 42, "MAGE", 0, 0 ), 900_000 );
		ally.onStatus( false, 1_000_000 - 125_000 );
		l = AllyStatusChip.lines( ally, true, 1_000_000 );
		assertEquals( "42% · D3", l[0] );
		assertEquals( "offline 2m", l[1] );
		assertFalse( ally.online() );

		ally.onStatus( true, 1_000_000 );
		assertEquals( "online", AllyStatusChip.lines( ally, true, 1_000_000 )[1] );
		assertEquals( "reconnecting...", AllyStatusChip.lines( ally, false, 1_000_000 )[1] );
	}

	@Test
	void roomSnapshotGivesTheAllysConnection() {
		Map<String, Object> room = new LinkedHashMap<>();
		List<Object> players = new ArrayList<>();
		players.add( player( PID, true ) );
		players.add( player( ALLY, false ) );
		room.put( "players", players );
		assertEquals( Boolean.FALSE, CoopGhosts.allyConnectedIn( room, PID ) );
		assertEquals( Boolean.TRUE, CoopGhosts.allyConnectedIn( room, ALLY ) );
	}

	private static Map<String, Object> player( String id, boolean connected ) {
		Map<String, Object> p = new LinkedHashMap<>();
		p.put( "playerId", id );
		p.put( "connected", connected );
		return p;
	}

	// ---- no gameplay RNG ----

	@Test
	void presenceNeverDrawsGameplayRandom() {
		Random.pushGenerator( 1234 );
		long expected = Random.Long();
		Random.popGenerator();

		Random.pushGenerator( 1234 );
		try {
			hero.pos = 10;
			CoopGhosts.turnSnapshot( hero );
			hero.pos = 11;
			CoopGhosts.turnSnapshot( hero );
			Message ghost = new Message( Protocol.GHOST ).put( "pos", 12 ).put( "anim", "attack" ).put( "hpPct", 64 )
					.put( "heroClass", "HUNTRESS" ).put( "tier", 2 );
			ghost.depth = 3;
			ghost.branch = 0;
			CoopGhosts.onPresence( ghost );
			Message mark = new Message( Protocol.MARK ).put( "cell", 30 ).put( "kind", "ITEM" );
			mark.depth = 3;
			mark.branch = 0;
			CoopGhosts.onPresence( mark );
			CoopGhosts.marks().placeLocal( 3, 0, 5, Marks.Kind.HERE, 1 );
			CoopGhosts.tick( 10_000 );
			assertEquals( expected, Random.Long(), "the seeded stream is where it would be without presence" );
		} finally {
			Random.popGenerator();
		}
	}

	//the actor loop's clock (Actor.now()); only the game loop advances it
	private static void setActorTime( float t ) throws Exception {
		Field f = Actor.class.getDeclaredField( "now" );
		f.setAccessible( true );
		f.setFloat( null, t );
	}

	//a CoopSession in a running room over a fake socket; frames re-decoded as c2s
	private static final class Wire implements FrameLink {
		final List<String> frames = new ArrayList<>();
		final CoopSession session;

		Wire() throws Exception {
			session = new CoopSession( 923, "desktop", new Quiet(), () -> 1_000L );
			session.attach( this );
			session.onConnected();
			session.onMessage( "{\"v\":1,\"t\":\"WELCOME\",\"seq\":1,\"ack\":1,\"p\":{\"playerId\":\"" + PID
					+ "\",\"resumeToken\":\"" + TOKEN + "\",\"epoch\":\"e1e2e3e4e5e6e7e8\",\"serverTime\":1,"
					+ "\"resumed\":false,\"replayed\":0,\"complete\":true,\"room\":{\"code\":\"K7MZQ4\",\"phase\":\"running\",\"players\":["
					+ "{\"playerId\":\"" + PID + "\",\"slot\":0,\"heroClass\":\"MAGE\",\"ready\":true,\"connected\":true,\"platform\":\"desktop\"},"
					+ "{\"playerId\":\"" + ALLY + "\",\"slot\":1,\"heroClass\":\"WARRIOR\",\"ready\":true,\"connected\":true,\"platform\":\"desktop\"}]}}}" );
			assertEquals( CoopSession.State.RUNNING, session.state() );
		}

		Message last() throws Protocol.DecodeError {
			return Protocol.decode( Direction.C2S, frames.get( frames.size() - 1 ) );
		}

		@Override
		public boolean send( String frame ) {
			frames.add( frame );
			return true;
		}

		@Override
		public void reconnect() {}

		@Override
		public void stop() {}
	}

	private static final class Quiet implements CoopSession.Listener {
		@Override public void onStateChanged( CoopSession.State state ) {}
		@Override public void onCredentials( String playerId, String resumeToken, String epoch ) {}
		@Override public void onRoomState( Map<String, Object> room ) {}
		@Override public void onRunStart( Map<String, Object> run, boolean fromSnapshot ) {}
		@Override public void onAllyStatus( Message status ) {}
		@Override public void onPresence( Message presence ) {}
		@Override public void onServerError( String code, String msg ) {}
		@Override public void onStopped( String code, String msg, Long minGameVersionCode ) {}
	}
}
