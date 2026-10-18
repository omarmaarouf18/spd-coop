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

import static com.shatteredpixel.shatteredpixeldungeon.coop.mission.MissionWire.ALLY;
import static com.shatteredpixel.shatteredpixeldungeon.coop.mission.MissionWire.M1;
import static com.shatteredpixel.shatteredpixeldungeon.coop.mission.MissionWire.ME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.HeadlessEnv;
import com.shatteredpixel.shatteredpixeldungeon.levels.SewerLevel;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

//MISSION_END applied: the failure penalty (gold, then the vanilla RETURN travel, never a depth
// write), the reward hook, and when it is applied (the hero waits for input / is dead)
class MissionEndTest {

	private Hero hero;
	private int switches;
	private final Runnable switchScene = () -> switches++;
	private final List<MissionState.End> rewarded = new ArrayList<>();

	@BeforeEach
	void setUp() {
		HeadlessEnv.start();
		Messages.setup( Languages.ENGLISH );
		hero = new Hero();
		hero.HP = hero.HT = 20;
		hero.ready = true;
		SewerLevel level = new SewerLevel();
		level.setSize( 10, 10 );
		Dungeon.hero = hero;
		Dungeon.level = level;
		Dungeon.depth = 8;
		Dungeon.branch = 0;
		Dungeon.gold = 200;
		InterlevelScene.mode = InterlevelScene.Mode.CONTINUE;
		InterlevelScene.returnDepth = InterlevelScene.returnBranch = InterlevelScene.returnPos = 0;
		CoopMissions.reward( rewarded::add );
	}

	@AfterEach
	void tearDown() {
		CoopMissions.reward( null );
		Dungeon.hero = null;
		Dungeon.level = null;
	}

	private MissionState failedMission( int regionStart, int goldPct ) {
		MissionState s = new MissionState();
		s.apply( MissionWire.start( M1, ME, ALLY, 0, 240_000 ), 0 );
		s.apply( MissionWire.failed( M1, regionStart, goldPct ), 0 );
		return s;
	}

	@Test
	void failureCostsGoldThenReturnsToTheRegionStartWithVanillaReturn() {
		MissionState s = failedMission( 6, 25 );
		CoopMissions.applyEnds( s, hero, switchScene );

		assertEquals( 150, Dungeon.gold, "−25 % of 200" );
		assertEquals( InterlevelScene.Mode.RETURN, InterlevelScene.mode );
		assertEquals( 6, InterlevelScene.returnDepth );
		assertEquals( 0, InterlevelScene.returnBranch );
		assertEquals( -1, InterlevelScene.returnPos, "-1 = that floor's entrance (Dungeon.switchLevel)" );
		assertEquals( 1, switches, "one scene switch to InterlevelScene" );
		assertEquals( 8, Dungeon.depth, "the depth is InterlevelScene.returnTo()'s job, never ours" );
		assertNull( s.nextEnd() );

		//applied once: nothing left for the next frame
		CoopMissions.applyEnds( s, hero, switchScene );
		assertEquals( 1, switches );
		assertEquals( 150, Dungeon.gold );
	}

	@Test
	void goldLossRoundsDownAndIsClamped() {
		assertEquals( 0, MissionPenalty.goldLost( 0, 25 ) );
		assertEquals( 0, MissionPenalty.goldLost( 3, 25 ) );
		assertEquals( 2, MissionPenalty.goldLost( 9, 25 ) );
		assertEquals( 0, MissionPenalty.goldLost( 100, 0 ) );
		assertEquals( 100, MissionPenalty.goldLost( 100, 100 ) );
		assertEquals( 100, MissionPenalty.goldLost( 100, 250 ) );
		assertEquals( 536_870_911, MissionPenalty.goldLost( Integer.MAX_VALUE, 25 ), "no int overflow" );
	}

	@Test
	void waitsUntilTheHeroWaitsForInput() {
		MissionState s = failedMission( 1, 25 );
		hero.ready = false;
		CoopMissions.applyEnds( s, hero, switchScene );
		assertEquals( 0, switches, "mid-action: not yet" );
		assertEquals( 200, Dungeon.gold );
		assertNotNull( s.nextEnd() );

		hero.ready = true;
		CoopMissions.applyEnds( s, hero, switchScene );
		assertEquals( 1, switches );
		assertEquals( 1, InterlevelScene.returnDepth );
	}

	@Test
	void waitsWhileABossFightSealsTheFloor() {
		MissionState s = failedMission( 1, 25 );
		Dungeon.depth = 5;
		Dungeon.level.locked = true;
		CoopMissions.applyEnds( s, hero, switchScene );
		assertEquals( 0, switches );
		assertNotNull( s.nextEnd() );

		Dungeon.level.locked = false;
		CoopMissions.applyEnds( s, hero, switchScene );
		assertEquals( 1, switches );
		assertEquals( 5, Dungeon.depth );
	}

	@Test
	void aDeadHeroGetsNoTravel() {
		MissionState s = failedMission( 6, 25 );
		hero.HP = 0;
		hero.ready = false;
		CoopMissions.applyEnds( s, hero, switchScene );
		assertEquals( 0, switches );
		assertEquals( 200, Dungeon.gold );
		assertNull( s.nextEnd(), "consumed: the real death path owns this hero now" );
	}

	@Test
	void successCallsTheRewardHookOnceAndAbortChargesNothing() {
		MissionState s = new MissionState();
		s.apply( MissionWire.start( M1, ME, ALLY, 0, 240_000 ), 0 );
		s.apply( MissionWire.success( M1, 777 ), 0 );
		CoopMissions.applyEnds( s, hero, switchScene );
		CoopMissions.applyEnds( s, hero, switchScene );
		assertEquals( 1, rewarded.size() );
		assertEquals( 777, rewarded.get( 0 ).rewardSeed );
		assertEquals( M1, rewarded.get( 0 ).mission.id );
		assertEquals( 0, switches );

		MissionState a = new MissionState();
		a.apply( MissionWire.start( M1, ME, ALLY, 0, 240_000 ), 0 );
		a.apply( MissionWire.ended( M1, MissionState.ABORTED ), 0 );
		CoopMissions.applyEnds( a, hero, switchScene );
		assertNull( a.nextEnd() );
		assertEquals( 0, switches );
		assertEquals( 200, Dungeon.gold );
		assertEquals( 1, rewarded.size() );
	}

	//the v3.3.8 save-corruption bug: nothing in the mission framework may write Dungeon.depth
	@Test
	void noMissionCodeWritesTheDepth() throws IOException {
		Path coop = Paths.get( "../java/com/shatteredpixel/shatteredpixeldungeon/coop" );
		Pattern write = Pattern.compile( "Dungeon\\.depth\\s*(=[^=]|\\+=|-=|\\+\\+|--)" );
		List<Path> sources = new ArrayList<>();
		try (Stream<Path> files = Files.walk( coop.resolve( "mission" ) )) {
			files.filter( p -> p.toString().endsWith( ".java" ) ).forEach( sources::add );
		}
		sources.add( coop.resolve( "ui/MissionHud.java" ) );
		assertTrue( sources.size() >= 7, "found the sources: " + sources );
		for (Path p : sources) {
			String text = new String( Files.readAllBytes( p ), StandardCharsets.UTF_8 );
			assertTrue( !write.matcher( text ).find(), p + " writes Dungeon.depth" );
		}
	}
}
