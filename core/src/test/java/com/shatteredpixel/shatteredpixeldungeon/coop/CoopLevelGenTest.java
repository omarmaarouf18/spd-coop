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

package com.shatteredpixel.shatteredpixeldungeon.coop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.HeadlessEnv;
import com.shatteredpixel.shatteredpixeldungeon.coop.test.LevelFingerprint;
import com.shatteredpixel.shatteredpixeldungeon.journal.Document;
import com.watabou.utils.Bundle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

//co-op runs must give both players the same floors whatever their profiles (NET-01c, decision 4)
class CoopLevelGenTest {

	private static final long SEED = 123456789L;
	private static final int DEPTHS = 5;

	//a brand-new player: no guide pages, intro on (EntranceRoom drops the guidebook unseeded)
	private static void freshProfile() {
		HeadlessEnv.resetPersistentState();
		//HeadlessEnv pins these two as seen (TST-01); a real new player has neither
		Document.ADVENTURERS_GUIDE.deletePage( Document.GUIDE_INTRO );
		Document.ADVENTURERS_GUIDE.deletePage( Document.GUIDE_SEARCHING );
		SPDSettings.intro( true );
	}

	//a veteran: every guide/alchemy page found, some lore found, intro done
	private static void veteranProfile() {
		HeadlessEnv.resetPersistentState();
		SPDSettings.intro( false );
		for (String page : Document.ADVENTURERS_GUIDE.pageNames()) Document.ADVENTURERS_GUIDE.readPage( page );
		for (String page : Document.ALCHEMY_GUIDE.pageNames()) Document.ALCHEMY_GUIDE.findPage( page );
		Document.SEWERS_GUARD.findPage( Document.SEWERS_GUARD.pageNames().iterator().next() );
	}

	private static List<String> floors( boolean coop ) {
		if (coop) {
			CoopRun run = new CoopRun();
			run.runId = "c0ffee0123456789";
			run.seed = SEED;
			run.heroClass = "WARRIOR";
			run.saveSlot = CoopSaves.FIRST;
			Coop.beginRun( run );
		} else {
			Dungeon.hero = null;
			Dungeon.daily = Dungeon.dailyReplay = false;
			Dungeon.customSeedText = "";
			Dungeon.seed = SEED;
		}
		Dungeon.init();
		assertEquals( coop, Coop.isCoopRun() );
		List<String> out = new ArrayList<>();
		for (int depth = 1; depth <= DEPTHS; depth++) {
			Dungeon.depth = depth;
			Dungeon.branch = 0;
			out.add( LevelFingerprint.of( Dungeon.newLevel(), depth, 0 ).all() );
		}
		return out;
	}

	@Test
	void coopFloorsDontDependOnTheProfile() {
		HeadlessEnv.start();
		freshProfile();
		List<String> fresh = floors( true );
		veteranProfile();
		List<String> veteran = floors( true );
		assertEquals( fresh, veteran, "co-op floors must be identical for a new and a veteran player" );
	}

	@Test
	void withoutTheNeutralProfileTheyWouldDiffer() {
		//the control: single-player (vanilla) generation does depend on the profile
		HeadlessEnv.start();
		freshProfile();
		List<String> fresh = floors( false );
		veteranProfile();
		List<String> veteran = floors( false );
		assertNotEquals( fresh, veteran, "vanilla generation reads the profile; if not, this test proves nothing" );
	}

	@Test
	void theRealProfileIsRestoredAfterGeneration() {
		HeadlessEnv.start();
		veteranProfile();
		Bundle before = new Bundle();
		Document.store( before );
		floors( true );
		Bundle after = new Bundle();
		Document.store( after );
		assertEquals( before.toString(), after.toString(), "journal pages must be exactly as before" );
		assertFalse( SPDSettings.intro() );

		freshProfile();
		floors( true );
		assertTrue( SPDSettings.intro(), "intro setting must be put back" );
		assertFalse( Document.ADVENTURERS_GUIDE.isPageFound( Document.GUIDE_SEARCHING ) );
	}

	@Test
	void coopRunsHaveNoChallengesAndASeedCode() {
		HeadlessEnv.start();
		freshProfile();
		SPDSettings.challenges( 1 );
		floors( true );
		assertEquals( 0, Dungeon.challenges );
		assertFalse( Dungeon.customSeedText.isEmpty(), "co-op runs are seeded runs (unranked, no remains)" );
		SPDSettings.challenges( 0 );
	}

	@Test
	void aSinglePlayerRunAfterACoopRunIsNotCoop() {
		HeadlessEnv.start();
		freshProfile();
		floors( true );
		assertTrue( Coop.isCoopRun() );
		floors( false );
		assertFalse( Coop.isCoopRun() );
	}

	@Test
	void coopRunSurvivesTheSaveBundle() throws Exception {
		CoopRun run = new CoopRun();
		run.runId = "c0ffee0123456789";
		run.seed = 5194191911102L;
		run.roleSeed = 987654321L;
		run.slot = 1;
		run.heroClass = "MAGE";
		run.roomCode = "K7MZQ4";
		run.serverUrl = "ws://localhost:8080/ws";
		run.saveSlot = 102;
		run.playerId = "a1b2c3d4e5f60718";
		run.resumeToken = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
		run.epoch = "e1e2e3e4e5e6e7e8";
		run.ack = 44;
		Bundle b = new Bundle();
		b.put( Coop.BUNDLE_KEY, run );
		CoopRun back = (CoopRun) Bundle.read( new java.io.ByteArrayInputStream(
				b.toString().getBytes( java.nio.charset.StandardCharsets.UTF_8 ) ) ).get( Coop.BUNDLE_KEY );
		assertEquals( run.runId, back.runId );
		assertEquals( run.seed, back.seed );
		assertEquals( run.roleSeed, back.roleSeed );
		assertEquals( run.slot, back.slot );
		assertEquals( run.heroClass, back.heroClass );
		assertEquals( run.roomCode, back.roomCode );
		assertEquals( run.serverUrl, back.serverUrl );
		assertEquals( run.saveSlot, back.saveSlot );
		assertEquals( run.playerId, back.playerId );
		assertEquals( run.resumeToken, back.resumeToken );
		assertEquals( run.epoch, back.epoch );
		assertEquals( run.ack, back.ack );
	}

	@Test
	void coopSlotsUseTheirOwnFolders() {
		assertTrue( Coop.isCoopSlot( 101 ) );
		assertTrue( Coop.isCoopSlot( 106 ) );
		assertFalse( Coop.isCoopSlot( 1 ) );
		assertFalse( Coop.isCoopSlot( 6 ) );
		assertFalse( Coop.isCoopSlot( 107 ) );
		assertEquals( "coop1", Coop.gameFolder( 101 ) );
		assertEquals( "coop6", Coop.gameFolder( 106 ) );
	}
}
