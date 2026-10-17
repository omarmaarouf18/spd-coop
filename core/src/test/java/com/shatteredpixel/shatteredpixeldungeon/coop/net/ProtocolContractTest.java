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

package com.shatteredpixel.shatteredpixeldungeon.coop.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.DecodeError;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Direction;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.Protocol.Message;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

//the Java side of the protocol contract (PROTOCOL.md §11): every fixture in
// docs/coop/protocol/fixtures gets the same verdict as server/internal/protocol.
class ProtocolContractTest {

	//the test JVM runs in core/src/main/assets (core/build.gradle); fixtures live at the repo root
	private static File fixturesDir() {
		File dir = new File( "../../../../docs/coop/protocol/fixtures" );
		if (!dir.isDirectory()) fail( "fixtures not found at " + dir.getAbsolutePath() );
		return dir;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> loadFixtures() throws IOException, ProtocolJson.ParseException {
		File[] files = fixturesDir().listFiles( ( d, n ) -> n.endsWith( ".json" ) );
		Arrays.sort( files );
		List<Map<String, Object>> out = new ArrayList<>();
		for (File f : files) {
			Map<String, Object> fx = (Map<String, Object>) ProtocolJson.parse(
					new String( Files.readAllBytes( f.toPath() ), StandardCharsets.UTF_8 ) );
			assertEquals( f.getName().replace( ".json", "" ), fx.get( "name" ), "fixture name must match its file" );
			out.add( fx );
		}
		return out;
	}

	private static String frame( Map<String, Object> fx ) {
		Object m = fx.get( "message" );
		return m instanceof String ? (String) m : ProtocolJson.write( m );
	}

	@Test
	@SuppressWarnings("unchecked")
	void everyFixtureGetsTheServersVerdict() throws Exception {
		int valid = 0, invalid = 0;
		List<String> failures = new ArrayList<>();
		for (Map<String, Object> fx : loadFixtures()) {
			String name = (String) fx.get( "name" );
			Direction dir = "s2c".equals( fx.get( "direction" ) ) ? Direction.S2C : Direction.C2S;
			boolean expectValid = Boolean.TRUE.equals( fx.get( "valid" ) );
			Message msg = null;
			DecodeError err = null;
			try {
				msg = Protocol.decode( dir, frame( fx ) );
			} catch (DecodeError e) {
				err = e;
			}

			if (!expectValid) {
				invalid++;
				String reason = (String) fx.get( "reason" );
				String field = fx.containsKey( "field" ) ? (String) fx.get( "field" ) : "";
				if (err == null) {
					failures.add( name + ": accepted an invalid message (want " + reason + " " + field + ")" );
				} else if (!err.kind.equals( reason ) || (!field.isEmpty() && !err.field.equals( field ))) {
					failures.add( name + ": got " + err.kind + " at '" + err.field + "', want " + reason + " at '" + field + "'" );
				}
				continue;
			}

			valid++;
			if (err != null) {
				failures.add( name + ": rejected a valid message: " + err.getMessage() );
				continue;
			}
			List<String> wantIgnored = new ArrayList<>();
			if (fx.containsKey( "ignoredFields" )) {
				for (Object o : (List<Object>) fx.get( "ignoredFields" )) wantIgnored.add( (String) o );
			}
			List<String> gotIgnored = new ArrayList<>( msg.ignored );
			Collections.sort( wantIgnored );
			Collections.sort( gotIgnored );
			if (!gotIgnored.equals( wantIgnored )) {
				failures.add( name + ": ignored fields " + gotIgnored + ", want " + wantIgnored );
				continue;
			}
			//re-encoding what was decoded must give the fixture minus the ignored fields
			Object got = ProtocolJson.parse( Protocol.encode( msg ) );
			Map<String, Object> want = (Map<String, Object>) ProtocolJson.parse( frame( fx ) );
			for (String path : wantIgnored) deletePath( want, path.split( "\\." ) );
			if (!got.equals( want )) {
				failures.add( name + ": re-encoded message differs\n  got:  " + ProtocolJson.write( got )
						+ "\n  want: " + ProtocolJson.write( want ) );
			}
		}
		assertTrue( failures.isEmpty(), failures.size() + " fixture(s) failed:\n" + String.join( "\n", failures ) );
		System.out.println( "contract fixtures exercised: " + (valid + invalid) + " (" + valid + " valid, " + invalid + " invalid)" );
		assertTrue( valid > 0 && invalid > 0 );
	}

	@Test
	@SuppressWarnings("unchecked")
	void everyTypeHasAValidFixtureAndReservedNamesAreUnknown() throws Exception {
		Set<String> have = new HashSet<>();
		for (Map<String, Object> fx : loadFixtures()) {
			if (!Boolean.TRUE.equals( fx.get( "valid" ) )) continue;
			Map<String, Object> m = (Map<String, Object>) fx.get( "message" );
			have.add( fx.get( "direction" ) + ":" + m.get( "t" ) );
		}
		for (Direction dir : Direction.values()) {
			for (String t : Protocol.types( dir )) {
				assertTrue( have.contains( dir.name().toLowerCase() + ":" + t ), "no valid " + dir + " fixture for " + t );
			}
		}
		List<String> reserved = Protocol.RESERVED;
		for (String r : reserved) {
			for (Direction dir : Direction.values()) {
				try {
					Protocol.decode( dir, "{\"v\":1,\"t\":\"" + r + "\",\"seq\":1,\"p\":{}}" );
					fail( "reserved " + r + " (" + dir + ") was accepted" );
				} catch (DecodeError e) {
					assertEquals( Protocol.KIND_UNKNOWN_TYPE, e.kind, r );
					assertEquals( Protocol.UNKNOWN_TYPE, e.code(), r );
				}
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static void deletePath( Map<String, Object> obj, String[] path ) {
		Object cur = obj;
		for (int i = 0; i < path.length - 1; i++) {
			cur = cur instanceof Map ? ((Map<String, Object>) cur).get( path[i] )
					: ((List<Object>) cur).get( Integer.parseInt( path[i] ) );
		}
		if (cur instanceof Map) ((Map<String, Object>) cur).remove( path[path.length - 1] );
	}
}
