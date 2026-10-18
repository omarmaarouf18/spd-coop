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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Invulnerability;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopTestRuns;
import com.shatteredpixel.shatteredpixeldungeon.coop.items.ScrollOfRecall;
import com.shatteredpixel.shatteredpixeldungeon.coop.mission.CoopMissions;
import com.shatteredpixel.shatteredpixeldungeon.coop.mission.MissionState;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Direction;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Message;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.HeadlessEnv;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.PotionOfShielding;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

//MEC-02, game-design §5, D-022, PROTOCOL.md §5.8: Downed instead of death (after the Ankh),
// DOWNED once, Scroll of Recall and the Healing throw, REVIVE / DOWNED_EXPIRED / MISSION_END,
// saves, and nothing in vanilla runs
class DownedTest {

	static final long SEED = 123456789L;
	static final String ME = "a1b2c3d4e5f60718";
	static final String ALLY = "b2c3d4e5f6071829";
	static final String M1 = "0123456789abcdef";
	static final int DEPTH = 7;

	private FakeLink link;
	private Game previousGame;
	private static long seq = 900;

	@BeforeEach
	void setUp() {
		HeadlessEnv.start();
		Messages.setup( Languages.ENGLISH );
		previousGame = Game.instance;
		new Game( null, null ) {};
		link = new FakeLink();
		CoopDowned.link( link );
		CoopDowned.install();
		CoopMissions.addListener( CoopDowned.LISTENER );
	}

	@AfterEach
	void tearDown() {
		CoopDowned.link( null );
		if (Dungeon.hero != null) Actor.remove( Dungeon.hero );
		Dungeon.hero = null;
		Game.instance = previousGame;
	}

	// ---- Downed ----

	@Test
	void theHeroIsDownedInsteadOfDeadAndDownedGoesOutOnce() {
		Hero hero = coopHero();
		Rat rat = new Rat();
		//Char.damage ends in die() at HP <= 0 (its interrupt needs a scene, so straight to die here)
		hero.HP = 0;
		hero.die( rat );

		assertTrue( hero.isAlive() );
		assertEquals( 1, hero.HP );
		Downed downed = hero.buff( Downed.class );
		assertNotNull( downed );
		assertEquals( "Rat", downed.cause );
		assertTrue( hero.paralysed > 0, "paralysed" );
		assertTrue( hero.invisible > 0, "mobs don't see it" );
		assertTrue( hero.isInvulnerable( Rat.class ) );
		assertEquals( 1, link.downed.size() );
		assertEquals( DEPTH + " 0 Rat", link.downed.get( 0 ) );

		//another death is swallowed: still one DOWNED
		hero.die( rat );
		assertEquals( 1, hero.HP );
		assertTrue( hero.isAlive() );
		downed.act();
		assertEquals( 1, link.downed.size() );

		//no ALLY_DOWNED for us after RESEND_MS: asked again; once the server has it, never again
		link.now += CoopDowned.RESEND_MS;
		downed.act();
		assertEquals( 2, link.downed.size() );
		event( allyDowned( ME, 99_000 ) );
		link.now += 10 * CoopDowned.RESEND_MS;
		downed.act();
		assertEquals( 2, link.downed.size() );
	}

	//the Ankh's revive needs a scene (sprites, flash, window): checked on the source instead. The
	// hook sits after the whole Ankh block and before the vanilla death, so an Ankh always wins.
	@Test
	void theHookComesAfterTheAnkh() throws IOException {
		String src = new String( Files.readAllBytes( Paths.get( "../java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java" ) ),
				StandardCharsets.UTF_8 );
		int die = src.indexOf( "public void die( Object cause )" );
		int ankh = src.indexOf( "if (ankh != null) {", die );
		int hook = src.indexOf( "CoopDowned.onHeroDying( this, cause )", die );
		int vanilla = src.indexOf( "super.die( cause );", die );
		assertTrue( die > 0 && ankh > die && hook > ankh && vanilla > hook, die + " " + ankh + " " + hook + " " + vanilla );
		//nothing but the end of the Ankh block between it and the hook
		assertTrue( src.substring( ankh, hook ).contains( "\t\t\treturn;\n\t\t}" ) );
	}

	@Test
	void vanillaRunsDieAsBefore() {
		HeadlessEnv.resetPersistentState();
		Dungeon.hero = null;
		Dungeon.daily = Dungeon.dailyReplay = false;
		GamesInProgress.selectedClass = HeroClass.WARRIOR;
		Dungeon.init();
		assertFalse( Coop.isCoopRun() );
		assertFalse( CoopDowned.onHeroDying( Dungeon.hero, new Rat() ) );
		assertNull( Dungeon.hero.buff( Downed.class ) );
		assertFalse( CoopDowned.onPotionThrown( new PotionOfHealing(), 5 ) );
		assertTrue( link.downed.isEmpty() );
	}

	// ---- our revive ----

	@Test
	void recallRevivesUsAtTheRegionStartWithAQuarterHp() {
		Hero hero = coopHero();
		goDown( hero );
		event( revive( ME, CoopDowned.MODE_REGION_START ) );
		assertNull( hero.buff( Downed.class ) );
		assertNull( hero.buff( Invulnerability.class ) );
		assertEquals( 0, hero.paralysed );
		assertEquals( 0, hero.invisible );
		assertEquals( Math.round( hero.HT * 0.25f ), hero.HP );
		assertEquals( List.of( 6 ), link.travels, "region start of depth 7" );
	}

	@Test
	void healingAndCellRevivesKeepUsInPlace() {
		Hero hero = coopHero();
		goDown( hero );
		event( revive( ME, CoopDowned.MODE_IN_PLACE ) );
		assertNull( hero.buff( Downed.class ) );
		assertTrue( link.travels.isEmpty() );
		assertEquals( DEPTH, Dungeon.depth );
	}

	@Test
	void expiryOutsideAMissionIsTheRealDeath() {
		Hero hero = coopHero();
		Downed downed = goDown( hero );
		event( expired( ME ) );
		assertTrue( downed.expired );
		assertTrue( link.died.isEmpty(), "the death runs on the actor thread, at the next act" );
		downed.act();
		assertEquals( 1, link.died.size() );
		assertTrue( link.died.get( 0 ) instanceof DownedExpiry );
		assertNull( hero.buff( Downed.class ) );
		assertEquals( 0, hero.HP );
	}

	@Test
	void anExpiryWhileNotDownedHereStillDies() {
		Hero hero = coopHero();
		event( expired( ME ) );
		Downed downed = hero.buff( Downed.class );
		assertNotNull( downed, "never a free revive" );
		downed.act();
		assertEquals( 1, link.died.size() );
	}

	@Test
	void aFailedCellClearsTheDownedPrisonerAtAQuarterHp() {
		Hero hero = coopHero();
		event( start( ME, ALLY ) );
		goDown( hero );
		assertTrue( CoopMissions.isDowned( ME ) );
		event( failed() );
		assertNull( hero.buff( Downed.class ) );
		assertEquals( Math.round( hero.HT * 0.25f ), hero.HP );
		assertFalse( CoopMissions.isDowned( ME ), "the mirror no longer has our window" );
		assertTrue( link.died.isEmpty() );
		assertEquals( 1, CoopMissions.state().pendingEnds().size(), "MEC-00 applies the penalty" );
	}

	@Test
	void anAbortRevivesTheDownedPrisonerInPlace() {
		Hero hero = coopHero();
		event( start( ME, ALLY ) );
		goDown( hero );
		event( revive( ME, CoopDowned.MODE_IN_PLACE ) );
		event( ended( "aborted" ) );
		assertNull( hero.buff( Downed.class ) );
		assertEquals( Math.round( hero.HT * 0.25f ), hero.HP );
		assertTrue( link.travels.isEmpty() );
	}

	// ---- the ally's revive tools ----

	@Test
	void recallNeedsADownedAllyAndNeverTargetsUs() {
		Hero hero = coopHero();
		ScrollOfRecall scroll = new ScrollOfRecall();
		assertTrue( scroll.collect( hero.belongings.backpack ) );

		//nobody Downed: it stays
		assertFalse( CoopDowned.readRecall( hero, scroll ) );
		assertNotNull( hero.belongings.getItem( ScrollOfRecall.class ) );
		//only we are Downed (on the server): not usable on ourselves
		event( allyDowned( ME, 99_000 ) );
		assertNull( CoopDowned.downedAlly() );
		event( revive( ME, CoopDowned.MODE_IN_PLACE ) );

		//the ally is Downed, on another floor: USE_RECALL to them, the scroll waits
		event( allyDowned( ALLY, 99_000 ) );
		link.allyOn = false;
		assertTrue( CoopDowned.readRecall( hero, scroll ) );
		assertEquals( List.of( DEPTH + " 0 " + ALLY + " recall" ), link.recalls );
		assertNull( hero.belongings.getItem( ScrollOfRecall.class ) );
		assertNotNull( hero.buff( RecallPending.class ) );
		//a second read while waiting: nothing
		assertFalse( CoopDowned.readRecall( hero, new ScrollOfRecall() ) );
		assertEquals( 1, link.recalls.size() );

		//REVIVE of the ally: the scroll is used up
		event( revive( ALLY, CoopDowned.MODE_REGION_START ) );
		assertNull( hero.buff( RecallPending.class ) );
		assertNull( hero.belongings.getItem( ScrollOfRecall.class ) );
		assertTrue( link.travels.isEmpty(), "only the revived player travels" );
	}

	@Test
	void aDenyOrTheAllysDeathGivesTheScrollBack() {
		Hero hero = coopHero();
		event( allyDowned( ALLY, 99_000 ) );
		ScrollOfRecall scroll = new ScrollOfRecall();
		scroll.collect( hero.belongings.backpack );
		assertTrue( CoopDowned.readRecall( hero, scroll ) );

		//a DENY of another kind (a claim): not ours
		CoopDowned.onServerEvent( deny( "taken" ) );
		assertNotNull( hero.buff( RecallPending.class ) );
		CoopDowned.onServerEvent( deny( "not_downed" ) );
		assertNull( hero.buff( RecallPending.class ) );
		assertNotNull( hero.belongings.getItem( ScrollOfRecall.class ) );

		//no answer at all for PENDING_MS of running link: back
		assertTrue( CoopDowned.readRecall( hero, hero.belongings.getItem( ScrollOfRecall.class ) ) );
		RecallPending pending = hero.buff( RecallPending.class );
		pending.act();
		assertNotNull( hero.buff( RecallPending.class ) );
		link.now += CoopDowned.PENDING_MS;
		pending.act();
		assertNull( hero.buff( RecallPending.class ) );
		assertNotNull( hero.belongings.getItem( ScrollOfRecall.class ) );

		//read again; the ally bleeds out first: back again, and a clear message
		assertTrue( CoopDowned.readRecall( hero, hero.belongings.getItem( ScrollOfRecall.class ) ) );
		event( expired( ALLY ) );
		assertNull( hero.buff( RecallPending.class ) );
		assertNotNull( hero.belongings.getItem( ScrollOfRecall.class ) );
		assertEquals( 1, link.alerts.size() );
		assertTrue( hero.isAlive(), "the ally's death is theirs only" );
		assertNull( CoopDowned.downedAlly(), "an out ally can't be recalled" );
	}

	@Test
	void healingOnTheGhostRevivesOnlyAHitOnADownedAllyHere() {
		Hero hero = coopHero();
		int ghost = 40;
		link.ghost = ghost;

		//ally not Downed: a normal throw
		assertFalse( CoopDowned.onPotionThrown( new PotionOfHealing(), ghost ) );
		event( allyDowned( ALLY, 99_000 ) );
		//a miss, another potion, the ally on another floor: normal throws
		assertFalse( CoopDowned.onPotionThrown( new PotionOfHealing(), ghost + 1 ) );
		assertFalse( CoopDowned.onPotionThrown( new PotionOfShielding(), ghost ) );
		link.allyOn = false;
		assertFalse( CoopDowned.onPotionThrown( new PotionOfHealing(), ghost ) );
		assertTrue( link.recalls.isEmpty() );

		//a hit: consumed here, no shatter, USE_RECALL via healing
		link.allyOn = true;
		assertTrue( CoopDowned.onPotionThrown( new PotionOfHealing(), ghost ) );
		assertEquals( List.of( DEPTH + " 0 " + ALLY + " healing" ), link.recalls );
		assertNull( hero.buff( RecallPending.class ) );
	}

	// ---- saves ----

	@Test
	void downedAndAWaitingRecallSurviveASave() {
		Hero hero = coopHero();
		event( allyDowned( ALLY, 99_000 ) );
		ScrollOfRecall scroll = new ScrollOfRecall();
		scroll.collect( hero.belongings.backpack );
		CoopDowned.readRecall( hero, scroll );
		Downed downed = goDown( hero );
		downed.expired = true;

		Bundle b = new Bundle();
		hero.storeInBundle( b );
		Hero back = new Hero();
		back.restoreFromBundle( roundTrip( b ) );

		Downed d = back.buff( Downed.class );
		assertNotNull( d );
		assertTrue( d.expired );
		assertEquals( "Rat", d.cause );
		assertEquals( 1, back.HP );
		assertEquals( 1, back.paralysed, "counters come back with the buff" );
		assertEquals( 1, back.invisible );
		assertNotNull( back.buff( Invulnerability.class ) );
		RecallPending p = back.buff( RecallPending.class );
		assertNotNull( p );
		assertEquals( ALLY, p.targetId );
		assertTrue( p.scroll instanceof ScrollOfRecall );
	}

	// ---- pacing ----

	@Test
	void aDownedTurnWaitsOneForcedTurnOfRunningClock() {
		FakeClock c = new FakeClock();
		assertTrue( DownedPace.waitTurn( c, 5_000 ) );
		assertEquals( 5_000, c.t );

		//held (link down) for a while: the count restarts when it runs again
		c = new FakeClock();
		c.runningFrom = 3_000;
		assertTrue( DownedPace.waitTurn( c, 5_000 ) );
		assertEquals( 8_000, c.t );

		//revived meanwhile: stops at once
		c = new FakeClock();
		c.downedUntil = 1_000;
		assertTrue( DownedPace.waitTurn( c, 5_000 ) );
		assertEquals( 1_000, c.t );

		//interrupted (a scene switch)
		c = new FakeClock();
		c.interruptAt = 500;
		assertFalse( DownedPace.waitTurn( c, 5_000 ) );
	}

	// ---- helpers ----

	private Hero coopHero() {
		HeadlessEnv.resetPersistentState();
		Dungeon.hero = null;
		Dungeon.daily = Dungeon.dailyReplay = false;
		CoopTestRuns.begin( SEED, HeroClass.WARRIOR ).playerId = ME;
		Dungeon.init();
		assertTrue( Coop.isCoopRun() );
		Dungeon.depth = DEPTH;
		Dungeon.branch = 0;
		Level level = Dungeon.newLevel();
		Dungeon.level = level;
		Hero hero = Dungeon.hero;
		hero.HP = hero.HT;
		for (int i = 0; i < level.length(); i++) if (level.map[i] == Terrain.EMPTY) { hero.pos = i; break; }
		Actor.add( hero );
		return hero;
	}

	//Downed, and the server's ALLY_DOWNED for us in the mirror
	private static Downed goDown( Hero hero ) {
		hero.HP = 0;
		hero.die( new Rat() );
		event( allyDowned( ME, 99_000 ) );
		return hero.buff( Downed.class );
	}

	private static void event( Message m ) {
		CoopMissions.onServerEvent( m );
	}

	private static Message s2c( String t, String payload ) {
		try {
			return Protocol.decode( Direction.S2C, "{\"v\":1,\"t\":\"" + t + "\",\"seq\":" + (++seq) + ",\"p\":" + payload + "}" );
		} catch (Protocol.DecodeError e) {
			throw new AssertionError( t + " " + payload + ": " + e.getMessage(), e );
		}
	}

	private static Message allyDowned( String player, long deadline ) {
		return s2c( Protocol.ALLY_DOWNED, "{\"player\":\"" + player + "\",\"deadline\":" + deadline + "}" );
	}

	private static Message revive( String target, String mode ) {
		return s2c( Protocol.REVIVE, "{\"target\":\"" + target + "\",\"mode\":\"" + mode + "\",\"hpPct\":25}" );
	}

	private static Message expired( String player ) {
		return s2c( Protocol.DOWNED_EXPIRED, "{\"player\":\"" + player + "\"}" );
	}

	private static Message deny( String reason ) {
		return s2c( Protocol.DENY, "{\"reason\":\"" + reason + "\"}" );
	}

	private static Message start( String prisoner, String rescuer ) {
		return s2c( Protocol.MISSION_START, "{\"missionId\":\"" + M1 + "\",\"kind\":\"CELL\",\"prisoner\":\"" + prisoner
				+ "\",\"rescuer\":\"" + rescuer + "\",\"depth\":" + DEPTH + ",\"branch\":0,\"trapCell\":45,\"roomId\":12,"
				+ "\"keySeed\":99,\"startsAt\":1000,\"deadline\":241000,\"waveIntervalMs\":30000}" );
	}

	private static Message failed() {
		return s2c( Protocol.MISSION_END, "{\"missionId\":\"" + M1 + "\",\"result\":\"failed\",\"penalty\":{\"regionStartDepth\":6,\"goldPct\":25}}" );
	}

	private static Message ended( String result ) {
		return s2c( Protocol.MISSION_END, "{\"missionId\":\"" + M1 + "\",\"result\":\"" + result + "\"}" );
	}

	private static Bundle roundTrip( Bundle b ) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		assertTrue( Bundle.write( b, out ) );
		try {
			return Bundle.read( new ByteArrayInputStream( out.toByteArray() ) );
		} catch (IOException e) {
			throw new AssertionError( e );
		}
	}

	static final class FakeClock implements DownedPace.Clock {
		long t;
		long runningFrom = 0;
		long downedUntil = Long.MAX_VALUE;
		long interruptAt = Long.MAX_VALUE;

		@Override
		public long now() {
			return t;
		}

		@Override
		public boolean running() {
			return t >= runningFrom;
		}

		@Override
		public boolean stillDowned() {
			return t < downedUntil;
		}

		@Override
		public boolean sleep( long ms ) {
			if (t + ms > interruptAt) return false;
			t += ms;
			return true;
		}
	}

	static final class FakeLink implements CoopDowned.Link {
		boolean allyOn = true;
		boolean running = true;
		long now = 1_000_000;
		int ghost = -1;
		final List<String> downed = new ArrayList<>();
		final List<String> recalls = new ArrayList<>();
		final List<Integer> travels = new ArrayList<>();
		final List<String> alerts = new ArrayList<>();
		final List<Object> died = new ArrayList<>();

		@Override
		public String me() {
			return ME;
		}

		@Override
		public boolean downed( int depth, int branch, String cause ) {
			downed.add( depth + " " + branch + " " + cause );
			return true;
		}

		@Override
		public boolean useRecall( int depth, int branch, String target, String via ) {
			recalls.add( depth + " " + branch + " " + target + " " + via );
			return true;
		}

		@Override
		public boolean allyOn( int depth, int branch ) {
			return allyOn;
		}

		@Override
		public MissionState state() {
			return CoopMissions.state();
		}

		@Override
		public boolean clockRunning() {
			return running;
		}

		@Override
		public long now() {
			return now;
		}

		@Override
		public boolean paced() {
			return false;
		}

		@Override
		public boolean sleep( long ms ) {
			return true;
		}

		@Override
		public void post( Runnable r ) {
			r.run();
		}

		@Override
		public void travel( int depth ) {
			travels.add( depth );
		}

		@Override
		public void alert( String text ) {
			alerts.add( text );
		}

		@Override
		public void showFx( String allyId ) {}

		@Override
		public boolean ghostTarget( int cell ) {
			return cell >= 0 && cell == ghost;
		}

		@Override
		public void die( Hero hero, Object cause ) {
			died.add( cause );
			assertSame( Dungeon.hero, hero );
		}
	}
}
