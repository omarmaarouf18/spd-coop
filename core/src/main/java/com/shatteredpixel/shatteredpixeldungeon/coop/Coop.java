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

import com.shatteredpixel.shatteredpixeldungeon.Chrome;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.coop.ghost.CoopGhosts;
import com.shatteredpixel.shatteredpixeldungeon.coop.ghost.GhostLayer;
import com.shatteredpixel.shatteredpixeldungeon.coop.ghost.HeroSnapshot;
import com.shatteredpixel.shatteredpixeldungeon.coop.levelgen.CoopLevelGen;
import com.shatteredpixel.shatteredpixeldungeon.coop.ui.AllyCompass;
import com.shatteredpixel.shatteredpixeldungeon.coop.ui.AllyStatusChip;
import com.shatteredpixel.shatteredpixeldungeon.coop.ui.CoopHud;
import com.shatteredpixel.shatteredpixeldungeon.coop.ui.WndCoop;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.ActionIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.StyledButton;
import com.shatteredpixel.shatteredpixeldungeon.utils.DungeonSeed;
import com.watabou.noosa.Group;
import com.watabou.noosa.Image;
import com.watabou.noosa.ui.Component;
import com.watabou.utils.Bundle;

import java.util.List;
import java.util.Map;

//facade for the co-op mod: every // COOP: hook in an upstream file calls one method here, and
// each is a no-op for single-player runs (AGENTS.md rules 5–6).
public final class Coop {

	public static final String BUNDLE_KEY = "coop_run";

	//the co-op run being played; null for single-player runs
	private static CoopRun run;
	//set by startRun, consumed by the Dungeon.init that follows it
	private static CoopRun pendingStart;

	private Coop() {}

	public static boolean isCoopRun() {
		return run != null;
	}

	public static CoopRun run() {
		return run;
	}

	// ---- upstream hooks ----

	//Dungeon.init(): a new run starts. It's a co-op run only if startRun just prepared one.
	public static void onInit() {
		if (pendingStart != null) {
			run = pendingStart;
			pendingStart = null;
			//co-op v1 has no challenges: both worlds must be generated with the same rules
			Dungeon.challenges = 0;
		} else {
			run = null;
			CoopConnection.stop();
		}
	}

	//Dungeon.newLevel(): replaces level.create(). Co-op floors depend only on seed+depth+branch
	// (coop/levelgen, D-019), with a neutral player profile (CoopProfile).
	public static void generate( Level level ) {
		if (run == null) {
			level.create();
		} else {
			run.levelGen = CoopLevelGen.generate( level, run.levelGen, () -> CoopProfile.generate( level ) );
		}
	}

	//Dungeon.saveGame()
	public static void storeInBundle( Bundle bundle ) {
		if (run == null) return;
		if (CoopConnection.session() != null) run.ack = CoopConnection.session().ack();
		bundle.put( BUNDLE_KEY, run );
	}

	//Dungeon.loadGame(): a saved co-op run reconnects with its credentials
	public static void restoreFromBundle( Bundle bundle ) {
		run = bundle.contains( BUNDLE_KEY ) ? (CoopRun) bundle.get( BUNDLE_KEY ) : null;
		pendingStart = null;
		final CoopRun loaded = run;
		//loadGame runs on InterlevelScene's loading thread; connections live on the GL thread
		ShatteredPixelDungeon.runOnRenderThread( () -> {
			if (loaded == null) {
				CoopConnection.stop();
			} else if (!CoopConnection.active()) {
				CoopConnection.resume( loaded );
			}
		} );
	}

	//GamesInProgress.gameFolder()
	public static boolean isCoopSlot( int slot ) {
		return CoopSaves.isCoopSlot( slot );
	}

	public static String gameFolder( int slot ) {
		return CoopSaves.folder( slot );
	}

	//TitleScene: a full-width Co-op row under the last row of buttons, with the same spacing.
	// (Splitting the Play row was tried first: "Enter the Dungeon" overflows half a row.)
	public static void addTitleButton( PixelScene scene, StyledButton play, StyledButton secondRow, StyledButton lastRow ) {
		StyledButton coop = new StyledButton( Chrome.Type.GREY_BUTTON_TR, Messages.get( Coop.class, "title_button" ) ) {
			@Override
			protected void onClick() {
				ShatteredPixelDungeon.scene().addToFront( new WndCoop() );
			}
		};
		coop.icon( Icons.get( Icons.TALENT ) );
		float gap = secondRow.top() - play.bottom();
		coop.setRect( play.left(), lastRow.bottom() + gap, lastRow.right() - play.left(), play.height() );
		scene.add( coop );
	}

	//DesktopLauncher: LLPD_PROFILE=b keeps a second local player's settings and saves apart,
	// so two copies of the game can play together on one machine
	public static String desktopProfileDir( String basePath ) {
		String profile = System.getenv( "LLPD_PROFILE" );
		if (profile == null) return basePath;
		profile = profile.toLowerCase( java.util.Locale.ROOT ).replaceAll( "[^a-z0-9]", "" );
		if (profile.isEmpty()) return basePath;
		return basePath + "profile-" + profile + "/";
	}

	// ---- run start / continue ----

	//RUN_START from the server: create the CoopRun and enter depth 1 with the server's seed
	@SuppressWarnings("unchecked")
	static void startRun( Map<String, Object> runInfo, String playerId, String token, String epoch, String serverUrl ) {
		String runId = (String) runInfo.get( "runId" );
		CoopSaves.Saved saved = CoopSaves.find( runId );
		if (saved != null) {
			//this device already has the run (e.g. the lobby was reopened): continue it
			continueRun( saved.slot );
			return;
		}

		CoopRun r = new CoopRun();
		r.runId = runId;
		r.seed = Long.parseLong( runInfo.get( "seed" ).toString() );
		r.roleSeed = Long.parseLong( runInfo.get( "roleSeed" ).toString() );
		for (Object o : (List<Object>) runInfo.get( "players" )) {
			Map<String, Object> p = (Map<String, Object>) o;
			if (p.get( "playerId" ).equals( playerId )) {
				r.slot = Integer.parseInt( p.get( "slot" ).toString() );
				r.heroClass = (String) p.get( "heroClass" );
			}
		}
		Map<String, Object> room = CoopConnection.room();
		r.roomCode = room == null ? "" : (String) room.get( "code" );
		r.serverUrl = serverUrl;
		r.playerId = playerId == null ? "" : playerId;
		r.resumeToken = token == null ? "" : token;
		r.epoch = epoch == null ? "" : epoch;
		r.saveSlot = CoopSaves.slotFor( runId );

		beginRun( r );
		ActionIndicator.clearAction();
		InterlevelScene.mode = InterlevelScene.Mode.DESCEND;
		ShatteredPixelDungeon.switchScene( InterlevelScene.class );
	}

	//prepares the next Dungeon.init() to start this co-op run (the scene switch is the caller's)
	static void beginRun( CoopRun r ) {
		pendingStart = r;
		Dungeon.hero = null;
		Dungeon.daily = Dungeon.dailyReplay = false;
		//the server's seed, not Dungeon.initSeed(): co-op never touches the custom-seed setting.
		// A seed code makes the run "seeded": unranked, no badges, no remains from earlier runs.
		Dungeon.seed = r.seed;
		Dungeon.customSeedText = DungeonSeed.convertToCode( r.seed );
		GamesInProgress.selectedClass = HeroClass.valueOf( r.heroClass );
		GamesInProgress.curSlot = r.saveSlot;
	}

	public static void continueRun( int slot ) {
		GamesInProgress.curSlot = slot;
		Dungeon.hero = null;
		Dungeon.daily = Dungeon.dailyReplay = false;
		ActionIndicator.clearAction();
		InterlevelScene.mode = InterlevelScene.Mode.CONTINUE;
		ShatteredPixelDungeon.switchScene( InterlevelScene.class );
	}

	// --- GHO-01 ---
	// ghost presence, ally compass/status, pings, quick messages (coop/ghost, coop/ui).
	// Visual and social only: no world change, no gameplay Random, nothing saved with the run.

	//Hero.act(): the hero's previous action has resolved; our GHOST (new pos) goes to the ally
	public static void onHeroTurn( Hero hero ) {
		if (run != null) CoopGhosts.onHeroTurn( hero );
	}

	//Hero.onAttackComplete()
	public static void onHeroAttacked( Hero hero ) {
		if (run != null) CoopGhosts.onHeroAnim( hero, HeroSnapshot.ATTACK );
	}

	//Hero.onOperateComplete()
	public static void onHeroOperated( Hero hero ) {
		if (run != null) CoopGhosts.onHeroAnim( hero, HeroSnapshot.OPERATE );
	}

	//Hero.die()
	public static void onHeroDied( Hero hero ) {
		if (run != null) CoopGhosts.onHeroAnim( hero, HeroSnapshot.DIE );
	}

	//GameScene.create(), between the heaps and the mobs: the ghost is drawn above the floor and
	// items, under the mobs and our hero
	public static void addGhostLayer( Group scene ) {
		if (run != null) scene.add( new GhostLayer() );
	}

	//GameScene.create(), right after the CellSelector: ping input on cells, ping markers, HUD
	public static void addGameHud( GameScene scene, CellSelector selector ) {
		if (run != null) CoopHud.attach( scene, selector );
	}

	//StatusPane: the ally compass around our portrait and the ally status chip
	public static void addStatusHud( Component pane, Image avatar ) {
		if (run == null) return;
		pane.add( new AllyCompass( avatar ) );
		pane.add( new AllyStatusChip( pane ) );
	}
}
