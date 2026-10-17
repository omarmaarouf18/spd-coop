/*
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
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
 *
 * Logic Link PD — Copyright (C) 2026 Logic Link PD contributors
 */

package com.shatteredpixel.shatteredpixeldungeon.coop.test;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.journal.Document;
import com.shatteredpixel.shatteredpixeldungeon.levels.VaultLevel;
import com.watabou.noosa.Game;
import com.watabou.utils.FileUtils;
import com.watabou.utils.GameSettings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Test-only bootstrap for headless level generation (TST-01).
 *
 * <p>Starts a libGDX {@link HeadlessApplication} so that {@code Gdx.app},
 * {@code Gdx.files} and preferences exist, then points every writable path at a
 * throw-away temp directory: tests never touch the user's real settings or save
 * slots. Nothing here changes game code; it only sets up the environment the
 * desktop/Android launchers normally provide (see {@code DesktopLauncher}).
 */
public final class HeadlessEnv {

	private static boolean started;

	private static Path tempDir;

	private static HeadlessApplication application;

	private HeadlessEnv() {
	}

	/** Idempotent; safe to call from every test. */
	public static synchronized void start() {
		if (started) {
			return;
		}
		started = true;

		try {
			tempDir = Files.createTempDirectory("llpd-tst01-");
			Path prefsDir = Files.createDirectories(tempDir.resolve("prefs"));
			Path filesDir = Files.createDirectories(tempDir.resolve("files"));

			HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
			config.preferencesDirectory = prefsDir.toAbsolutePath() + "/";

			application = new HeadlessApplication(new ApplicationListener() {
				@Override public void create() {}
				@Override public void resize(int width, int height) {}
				@Override public void render() {}
				@Override public void pause() {}
				@Override public void resume() {}
				@Override public void dispose() {}
			}, config);

			// what AndroidLauncher/DesktopLauncher set before the game starts
			Game.version = "4.0.2"; // not "INDEV": DeviceCompat.isDebug() must be false
			Game.versionCode = 922; // upstream v4.0.2 appVersionCode

			// every save/badge/journal file resolves under the temp dir
			FileUtils.setDefaultFileProperties(com.badlogic.gdx.Files.FileType.Absolute,
					filesDir.toAbsolutePath() + "/");

			Preferences prefs = Gdx.app.getPreferences("llpd-tests");
			prefs.putInteger(SPDSettings.KEY_CHALLENGES, 0);
			prefs.putBoolean(SPDSettings.KEY_INTRO, false);
			prefs.putString("lang", "en");
			prefs.flush();
			GameSettings.set(prefs);

			GamesInProgress.selectedClass = HeroClass.WARRIOR;
			GamesInProgress.curSlot = 1;

			capturePersistentStatics();

			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				if (application != null) {
					application.exit();
				}
			}));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static Path tempDir() {
		start();
		return tempDir;
	}

	// ----- persistent statics -----------------------------------------------

	/**
	 * Level generation reads state that outlives a run and that
	 * {@code Dungeon.init()} does not reset: journal meta progression, in-place
	 * shuffled static arrays, and the entropy-seeded {@code Random} behind
	 * unseeded {@code Collections.shuffle} calls. Vanilla seeds are only
	 * reproducible against a fixed state, so the harness pins all of it and
	 * restores the pins before every generated run.
	 */
	private static Map<Document, LinkedHashMap<String, Integer>> documentDefaults;
	private static Map<Field, Object[]> staticArrayDefaults;
	private static Field collectionsRandomField;

	/**
	 * Seed for {@code java.util.Collections}' default {@code Random}. Upstream
	 * {@code VaultLevel.setupConsumables} (and a few runtime spots) call
	 * {@code Collections.shuffle(list)} without the game's RNG, so vanilla
	 * vault loot order is nondeterministic in real play. Pinning that Random
	 * is what makes vault floors reproducible here. The golden therefore
	 * depends on JDK {@code java.util.Random} shuffle behavior.
	 */
	private static final long SHUFFLE_SEED = 1L;

	private static void capturePersistentStatics() {
		documentDefaults = new LinkedHashMap<>();
		for (Document document : Document.values()) {
			documentDefaults.put(document, new LinkedHashMap<>(documentPages(document)));
		}

		// A fresh process has every guide page at NOT_FOUND, and then
		// EntranceRoom.placeEarlyGuidePages places the intro guidebook (floor 1)
		// and the searching guide page (floor 2) using an unseeded generator.
		// That position is not reproducible, and it leaks into seeded passes:
		// RegularPainter.paintGrass skips its Random.Float() draw on cells that
		// have a heap, so the whole floor's grass/deco/trap stream shifts.
		// Pinning both pages as already seen disables those two placements, so
		// every guide item that remains is placed by the seeded code path.
		documentDefaults.get(Document.ADVENTURERS_GUIDE).put(Document.GUIDE_INTRO, Document.READ);
		documentDefaults.get(Document.ADVENTURERS_GUIDE).put(Document.GUIDE_SEARCHING, Document.FOUND);

		staticArrayDefaults = new LinkedHashMap<>();
		captureStaticArray(VaultLevel.class, "T2SolveItems");
		captureStaticArray(VaultLevel.class, "T3SolveItems");

		try {
			collectionsRandomField = Collections.class.getDeclaredField("r");
			collectionsRandomField.setAccessible(true);
		} catch (ReflectiveOperationException | RuntimeException e) {
			throw new AssertionError("cannot access java.util.Collections.r "
					+ "(is --add-opens java.base/java.util=ALL-UNNAMED set?)", e);
		}
	}

	/** Restores all pinned persistent state to its fresh-process defaults. */
	public static void resetPersistentState() {
		start();

		// guide/lore page meta progression, which feeds guide page drops
		for (Map.Entry<Document, LinkedHashMap<String, Integer>> defaults : documentDefaults.entrySet()) {
			LinkedHashMap<String, Integer> pages = documentPages(defaults.getKey());
			pages.clear();
			pages.putAll(defaults.getValue());
		}

		// VaultLevel shuffles these in place while generating; upstream never
		// resets them, so a same-seed replay in one process differs otherwise
		for (Map.Entry<Field, Object[]> defaults : staticArrayDefaults.entrySet()) {
			Field field = defaults.getKey();
			Object[] snapshot = defaults.getValue();
			try {
				Object[] current = (Object[]) field.get(null);
				System.arraycopy(snapshot, 0, current, 0, snapshot.length);
			} catch (IllegalAccessException e) {
				throw new AssertionError("cannot reset " + field, e);
			}
		}

		// the entropy-seeded Random behind unseeded Collections.shuffle calls
		try {
			collectionsRandomField.set(null, new Random(SHUFFLE_SEED));
		} catch (IllegalAccessException e) {
			throw new AssertionError("cannot reset java.util.Collections.r", e);
		}
	}

	private static void captureStaticArray(Class<?> owner, String name) {
		try {
			Field field = owner.getDeclaredField(name);
			field.setAccessible(true);
			staticArrayDefaults.put(field, ((Object[]) field.get(null)).clone());
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("cannot capture " + owner.getSimpleName() + "." + name, e);
		}
	}

	@SuppressWarnings("unchecked")
	private static LinkedHashMap<String, Integer> documentPages(Document document) {
		try {
			Field field = Document.class.getDeclaredField("pagesStates");
			field.setAccessible(true);
			return (LinkedHashMap<String, Integer>) field.get(document);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("cannot access Document.pagesStates", e);
		}
	}
}
