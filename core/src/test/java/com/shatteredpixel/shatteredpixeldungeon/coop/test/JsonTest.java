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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The golden reader/writer must round-trip its own output exactly (TST-01):
 * the whole comparison relies on it.
 */
class JsonTest {

	@Test
	void writtenDocumentsParseBackToTheSameDocument() {
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("format", "llpd-vanilla-goldens");
		root.put("challenges", 0L);
		Map<String, Object> origins = new LinkedHashMap<>();
		origins.put("0", "text with \"quotes\", \\backslashes\\ and\nnewlines = 0 (AAA-AAA-AAA)");
		origins.put("5429503678975", "high boundary");
		root.put("seedOrigins", origins);
		root.put("list", List.of("a", "b", "c"));
		root.put("emptyMap", new LinkedHashMap<String, Object>());
		root.put("emptyList", List.of());
		root.put("nested", List.of(Map.of("k", List.of(1L, 2L, 3L))));

		String json = Json.write(root);
		Assertions.assertEquals(json, Json.write(Json.parse(json)), "json must round-trip byte-for-byte");
	}

	@Test
	void duplicateKeysAreRejected() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> Json.parse("{\"a\": 1, \"a\": 2}"));
	}

	@Test
	void numberTextIsNotANumber() {
		Map<String, Object> parsed = Json.parseObject("{\"seed\": \"0000000000001\"}");
		Assertions.assertEquals("0000000000001", parsed.get("seed"));
	}
}
