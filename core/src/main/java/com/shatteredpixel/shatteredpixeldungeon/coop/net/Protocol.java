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

import com.shatteredpixel.shatteredpixeldungeon.coop.net.ProtocolJson.Num;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

//co-op protocol v1 (docs/coop/protocol/PROTOCOL.md): envelope, message types and the strict
// decoder. It mirrors server/internal/protocol (schema.go, messages.go) check for check, so both
// sides give the same verdict on every fixture in docs/coop/protocol/fixtures (§11).
public final class Protocol {

	public static final int VERSION = 1;
	public static final long MAX_SAFE_INT = (1L << 53) - 1;
	//SPD DungeonSeed.TOTAL_SEEDS, 26^9
	public static final long MAX_SEED_EXCLUSIVE = 5429503678976L;
	public static final String ROOM_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

	public enum Direction {
		C2S, S2C
	}

	// message types (§5)
	public static final String HELLO = "HELLO";
	public static final String WELCOME = "WELCOME";
	public static final String REJECT = "REJECT";
	public static final String PING = "PING";
	public static final String PONG = "PONG";
	public static final String ERROR = "ERROR";
	public static final String ROOM_CREATE = "ROOM_CREATE";
	public static final String ROOM_JOIN = "ROOM_JOIN";
	public static final String ROOM_LEAVE = "ROOM_LEAVE";
	public static final String READY = "READY";
	public static final String ROOM_STATE = "ROOM_STATE";
	public static final String RUN_START = "RUN_START";
	public static final String ALLY_STATUS = "ALLY_STATUS";
	public static final String GHOST = "GHOST";
	public static final String MARK = "MARK";
	public static final String QUICK = "QUICK";

	// error / reject codes (§4.3)
	public static final String BAD_MESSAGE = "BAD_MESSAGE";
	public static final String UNKNOWN_TYPE = "UNKNOWN_TYPE";
	public static final String VERSION_MISMATCH = "VERSION_MISMATCH";
	public static final String RESUME_FAILED = "RESUME_FAILED";
	public static final String INVALID_STATE = "INVALID_STATE";
	public static final String NOT_IN_ROOM = "NOT_IN_ROOM";
	public static final String ALREADY_IN_ROOM = "ALREADY_IN_ROOM";
	public static final String ROOM_NOT_FOUND = "ROOM_NOT_FOUND";
	public static final String ROOM_FULL = "ROOM_FULL";
	public static final String RATE_LIMITED = "RATE_LIMITED";
	public static final String SESSION_REPLACED = "SESSION_REPLACED";
	public static final String INTERNAL = "INTERNAL";

	public static final List<String> PLATFORMS = Arrays.asList( "desktop", "android", "ios" );
	public static final List<String> HERO_CLASSES = Arrays.asList( "WARRIOR", "MAGE", "ROGUE", "HUNTRESS", "DUELIST", "CLERIC" );
	public static final List<String> ANIMS = Arrays.asList( "idle", "run", "attack", "operate", "zap", "die" );
	public static final List<String> MARK_KINDS = Arrays.asList( "HERE", "DANGER", "ITEM", "GO" );
	public static final List<String> PHASES = Arrays.asList( "lobby", "running" );
	private static final List<String> REJECT_CODES = Arrays.asList( BAD_MESSAGE, VERSION_MISMATCH, RESUME_FAILED );
	private static final List<String> ERROR_CODES = Arrays.asList( BAD_MESSAGE, UNKNOWN_TYPE, INVALID_STATE, NOT_IN_ROOM,
			ALREADY_IN_ROOM, ROOM_NOT_FOUND, ROOM_FULL, RATE_LIMITED, SESSION_REPLACED, INTERNAL );

	// decode-error kinds (§11)
	public static final String KIND_NOT_JSON = "not_json";
	public static final String KIND_UNSUPPORTED_VERSION = "unsupported_version";
	public static final String KIND_UNKNOWN_TYPE = "unknown_type";
	public static final String KIND_MISSING_FIELD = "missing_field";
	public static final String KIND_UNKNOWN_FIELD = "unknown_field";
	public static final String KIND_FORBIDDEN_FIELD = "forbidden_field";
	public static final String KIND_WRONG_TYPE = "wrong_type";
	public static final String KIND_OUT_OF_RANGE = "out_of_range";
	public static final String KIND_BAD_FORMAT = "bad_format";
	public static final String KIND_TOO_LONG = "too_long";

	private Protocol() {}

	public static boolean reliable( String t ) {
		return ROOM_STATE.equals( t ) || RUN_START.equals( t ) || ALLY_STATUS.equals( t );
	}

	public static boolean presence( String t ) {
		return GHOST.equals( t ) || MARK.equals( t ) || QUICK.equals( t );
	}

	//one decoded or outgoing message. Optional envelope fields are null when absent.
	public static final class Message {
		public String t;
		public long seq;
		public Long ack;
		public String from;
		public Integer depth;
		public Integer branch;
		public Map<String, Object> p = new LinkedHashMap<>();
		//dotted paths of unknown s2c fields the decoder dropped (§5.5)
		public List<String> ignored = new ArrayList<>();

		public Message() {}

		public Message( String t ) {
			this.t = t;
		}

		public String str( String key ) {
			Object v = p.get( key );
			return v instanceof String ? (String) v : null;
		}

		public long num( String key ) {
			return ((Num) p.get( key )).isInteger() ? Long.parseLong( p.get( key ).toString() ) : 0;
		}

		public boolean bool( String key ) {
			return Boolean.TRUE.equals( p.get( key ) );
		}

		@SuppressWarnings("unchecked")
		public Map<String, Object> obj( String key ) {
			Object v = p.get( key );
			return v instanceof Map ? (Map<String, Object>) v : null;
		}

		public Message put( String key, Object value ) {
			if (value instanceof Integer || value instanceof Long) {
				value = new Num( ((Number) value).longValue() );
			}
			p.put( key, value );
			return this;
		}
	}

	public static final class DecodeError extends Exception {
		public final String kind;
		public final String field;

		DecodeError( String kind, String field, String msg ) {
			super( field == null || field.isEmpty() ? kind + ": " + msg : kind + " at " + field + ": " + msg );
			this.kind = kind;
			this.field = field == null ? "" : field;
		}

		//the ERROR/REJECT code a receiver answers with (§11)
		public String code() {
			if (KIND_UNSUPPORTED_VERSION.equals( kind )) return VERSION_MISMATCH;
			if (KIND_UNKNOWN_TYPE.equals( kind )) return UNKNOWN_TYPE;
			return BAD_MESSAGE;
		}
	}

	// ---- encode -------------------------------------------------------------------------

	public static String encode( Message m ) {
		Map<String, Object> root = new LinkedHashMap<>();
		root.put( "v", new Num( VERSION ) );
		root.put( "t", m.t );
		root.put( "seq", new Num( m.seq ) );
		if (m.ack != null) root.put( "ack", new Num( m.ack ) );
		if (m.from != null) root.put( "from", m.from );
		if (m.depth != null) root.put( "depth", new Num( m.depth ) );
		if (m.branch != null) root.put( "branch", new Num( m.branch ) );
		root.put( "p", m.p );
		return ProtocolJson.write( root );
	}

	// ---- decode -------------------------------------------------------------------------

	public static Message decode( Direction dir, String frame ) throws DecodeError {
		Object root;
		try {
			root = ProtocolJson.parse( frame );
		} catch (ProtocolJson.ParseException e) {
			throw new DecodeError( KIND_NOT_JSON, "", e.getMessage() );
		}
		if (!(root instanceof Map)) {
			throw new DecodeError( KIND_NOT_JSON, "", "frame is not a JSON object" );
		}
		Validator v = new Validator( dir );
		@SuppressWarnings("unchecked")
		Message m = v.envelope( (Map<String, Object>) root );
		if (v.err != null) throw v.err;
		m.ignored = v.ignored;
		return m;
	}

	// ---- schema -------------------------------------------------------------------------

	private interface Check {
		void apply( Validator v, String path, Object x );
	}

	private interface CrossCheck {
		void apply( Validator v, String path, Map<String, Object> obj );
	}

	private static final class Field {
		final String name;
		final boolean required;
		final Check check;

		Field( String name, boolean required, Check check ) {
			this.name = name;
			this.required = required;
			this.check = check;
		}
	}

	private static final class ObjSchema {
		final List<Field> fields;
		final CrossCheck check;

		ObjSchema( CrossCheck check, Field... fields ) {
			this.fields = Arrays.asList( fields );
			this.check = check;
		}
	}

	private enum Floor { FORBIDDEN, REQUIRED, OPTIONAL }

	private static final class TypeSpec {
		final boolean c2s, s2c;
		final Floor floor;
		final ObjSchema payload;

		TypeSpec( boolean c2s, boolean s2c, Floor floor, ObjSchema payload ) {
			this.c2s = c2s;
			this.s2c = s2c;
			this.floor = floor;
			this.payload = payload;
		}
	}

	private static final Pattern HEX_ID = Pattern.compile( "^[0-9a-f]{16}$" );
	private static final Pattern TOKEN = Pattern.compile( "^[A-Za-z0-9_-]{43}$" );
	private static final Pattern ROOM_CODE = Pattern.compile( "^[" + ROOM_CODE_ALPHABET + "]{6}$" );

	private static Check formatted( final Pattern re, final String what ) {
		return new Check() {
			@Override
			public void apply( Validator v, String path, Object x ) {
				String s = v.str( path, x );
				if (s != null) v.match( path, s, re, what );
			}
		};
	}

	private static final Check hexId = formatted( HEX_ID, "16 lowercase hex chars" );
	private static final Check token = formatted( TOKEN, "43 base64url chars" );
	private static final Check roomCode = formatted( ROOM_CODE, "6 chars from " + ROOM_CODE_ALPHABET );

	private static Check intRange( final long lo, final long hi ) {
		return new Check() {
			@Override
			public void apply( Validator v, String path, Object x ) {
				v.integer( path, x, lo, hi );
			}
		};
	}

	private static Check enumOf( final List<String> values ) {
		return new Check() {
			@Override
			public void apply( Validator v, String path, Object x ) {
				String s = v.str( path, x );
				if (s != null && !values.contains( s )) {
					v.fail( KIND_BAD_FORMAT, path, "must be one of " + values );
				}
			}
		};
	}

	private static Check text( final int maxChars ) {
		return new Check() {
			@Override
			public void apply( Validator v, String path, Object x ) {
				String s = v.str( path, x );
				if (s != null && s.codePointCount( 0, s.length() ) > maxChars) {
					v.fail( KIND_TOO_LONG, path, "at most " + maxChars + " chars" );
				}
			}
		};
	}

	private static final Check bool = new Check() {
		@Override
		public void apply( Validator v, String path, Object x ) {
			if (!(x instanceof Boolean)) v.fail( KIND_WRONG_TYPE, path, "must be a boolean" );
		}
	};

	private static Check object( final ObjSchema s ) {
		return new Check() {
			@Override
			@SuppressWarnings("unchecked")
			public void apply( Validator v, String path, Object x ) {
				if (!(x instanceof Map)) {
					v.fail( KIND_WRONG_TYPE, path, "must be an object" );
					return;
				}
				v.object( path, (Map<String, Object>) x, s );
			}
		};
	}

	private static Check array( final int minLen, final int maxLen, final Check elem ) {
		return new Check() {
			@Override
			public void apply( Validator v, String path, Object x ) {
				if (!(x instanceof List)) {
					v.fail( KIND_WRONG_TYPE, path, "must be an array" );
					return;
				}
				List<?> arr = (List<?>) x;
				if (arr.size() < minLen || arr.size() > maxLen) {
					v.fail( KIND_OUT_OF_RANGE, path, "must have " + minLen + ".." + maxLen + " elements, has " + arr.size() );
					return;
				}
				for (int i = 0; i < arr.size(); i++) {
					elem.apply( v, join( path, Integer.toString( i ) ), arr.get( i ) );
				}
			}
		};
	}

	//players' slots strictly ascending: distinct and sorted
	private static CrossCheck slotsAscending( final String name ) {
		return new CrossCheck() {
			@Override
			@SuppressWarnings("unchecked")
			public void apply( Validator v, String path, Map<String, Object> obj ) {
				List<Object> players = (List<Object>) obj.get( name );
				long prev = -1;
				for (int i = 0; i < players.size(); i++) {
					Map<String, Object> pm = (Map<String, Object>) players.get( i );
					long n = Long.parseLong( pm.get( "slot" ).toString() );
					if (n <= prev) {
						v.fail( KIND_BAD_FORMAT, join( join( path, name ), Integer.toString( i ) ) + ".slot",
								"slots must be distinct and ascending" );
						return;
					}
					prev = n;
				}
			}
		};
	}

	private static final CrossCheck epochNeedsToken = new CrossCheck() {
		@Override
		public void apply( Validator v, String path, Map<String, Object> obj ) {
			if (obj.containsKey( "epoch" ) && !obj.containsKey( "resumeToken" )) {
				v.fail( KIND_FORBIDDEN_FIELD, join( path, "epoch" ), "epoch is only sent together with resumeToken" );
			}
		}
	};

	private static final ObjSchema ROOM_PLAYER = new ObjSchema( null,
			new Field( "playerId", true, hexId ),
			new Field( "slot", true, intRange( 0, 1 ) ),
			new Field( "heroClass", false, enumOf( HERO_CLASSES ) ),
			new Field( "ready", true, bool ),
			new Field( "connected", true, bool ),
			new Field( "platform", true, enumOf( PLATFORMS ) ) );

	private static final ObjSchema ROOM_STATE_SCHEMA = new ObjSchema( slotsAscending( "players" ),
			new Field( "code", true, roomCode ),
			new Field( "phase", true, enumOf( PHASES ) ),
			new Field( "players", true, array( 1, 2, object( ROOM_PLAYER ) ) ) );

	private static final ObjSchema RUN_PLAYER = new ObjSchema( null,
			new Field( "playerId", true, hexId ),
			new Field( "slot", true, intRange( 0, 1 ) ),
			new Field( "heroClass", true, enumOf( HERO_CLASSES ) ),
			new Field( "platform", true, enumOf( PLATFORMS ) ) );

	private static final ObjSchema RUN_INFO = new ObjSchema( slotsAscending( "players" ),
			new Field( "runId", true, hexId ),
			new Field( "seed", true, intRange( 0, MAX_SEED_EXCLUSIVE - 1 ) ),
			new Field( "roleSeed", true, intRange( 0, MAX_SAFE_INT ) ),
			new Field( "players", true, array( 2, 2, object( RUN_PLAYER ) ) ) );

	private static final long INT32 = (1L << 31) - 1;

	private static final Map<String, TypeSpec> SPECS = new HashMap<>();
	static {
		SPECS.put( HELLO, new TypeSpec( true, false, Floor.FORBIDDEN, new ObjSchema( epochNeedsToken,
				new Field( "protocol", true, intRange( 0, INT32 ) ),
				new Field( "gameVersionCode", true, intRange( 1, INT32 ) ),
				new Field( "platform", true, enumOf( PLATFORMS ) ),
				new Field( "resumeToken", false, token ),
				new Field( "epoch", false, hexId ) ) ) );
		SPECS.put( WELCOME, new TypeSpec( false, true, Floor.FORBIDDEN, new ObjSchema( null,
				new Field( "playerId", true, hexId ),
				new Field( "resumeToken", true, token ),
				new Field( "epoch", true, hexId ),
				new Field( "serverTime", true, intRange( 0, MAX_SAFE_INT ) ),
				new Field( "resumed", true, bool ),
				new Field( "replayed", true, intRange( 0, MAX_SAFE_INT ) ),
				new Field( "complete", true, bool ),
				new Field( "room", false, object( ROOM_STATE_SCHEMA ) ),
				new Field( "run", false, object( RUN_INFO ) ) ) ) );
		SPECS.put( REJECT, new TypeSpec( false, true, Floor.FORBIDDEN, new ObjSchema( null,
				new Field( "code", true, enumOf( REJECT_CODES ) ),
				new Field( "msg", true, text( 256 ) ),
				new Field( "minGameVersionCode", false, intRange( 1, INT32 ) ) ) ) );
		SPECS.put( PING, new TypeSpec( true, false, Floor.FORBIDDEN, new ObjSchema( null,
				new Field( "t", true, intRange( 0, MAX_SAFE_INT ) ) ) ) );
		SPECS.put( PONG, new TypeSpec( false, true, Floor.FORBIDDEN, new ObjSchema( null,
				new Field( "t", true, intRange( 0, MAX_SAFE_INT ) ),
				new Field( "serverTime", true, intRange( 0, MAX_SAFE_INT ) ) ) ) );
		SPECS.put( ERROR, new TypeSpec( false, true, Floor.FORBIDDEN, new ObjSchema( null,
				new Field( "code", true, enumOf( ERROR_CODES ) ),
				new Field( "msg", true, text( 256 ) ),
				new Field( "ref_seq", false, intRange( 1, MAX_SAFE_INT ) ) ) ) );
		SPECS.put( ROOM_CREATE, new TypeSpec( true, false, Floor.FORBIDDEN, new ObjSchema( null ) ) );
		SPECS.put( ROOM_JOIN, new TypeSpec( true, false, Floor.FORBIDDEN, new ObjSchema( null,
				new Field( "code", true, roomCode ) ) ) );
		SPECS.put( ROOM_LEAVE, new TypeSpec( true, false, Floor.FORBIDDEN, new ObjSchema( null ) ) );
		SPECS.put( READY, new TypeSpec( true, false, Floor.FORBIDDEN, new ObjSchema( null,
				new Field( "heroClass", true, enumOf( HERO_CLASSES ) ),
				new Field( "ready", true, bool ) ) ) );
		SPECS.put( ROOM_STATE, new TypeSpec( false, true, Floor.FORBIDDEN, ROOM_STATE_SCHEMA ) );
		SPECS.put( RUN_START, new TypeSpec( false, true, Floor.FORBIDDEN, RUN_INFO ) );
		SPECS.put( ALLY_STATUS, new TypeSpec( false, true, Floor.FORBIDDEN, new ObjSchema( null,
				new Field( "playerId", true, hexId ),
				new Field( "connected", true, bool ),
				new Field( "paused", true, bool ),
				new Field( "lastSeen", true, intRange( 0, MAX_SAFE_INT ) ) ) ) );
		SPECS.put( GHOST, new TypeSpec( true, true, Floor.REQUIRED, new ObjSchema( null,
				new Field( "pos", true, intRange( 0, 65535 ) ),
				new Field( "anim", true, enumOf( ANIMS ) ),
				new Field( "hpPct", true, intRange( 0, 100 ) ),
				new Field( "heroClass", true, enumOf( HERO_CLASSES ) ),
				new Field( "tier", true, intRange( 0, 6 ) ) ) ) );
		SPECS.put( MARK, new TypeSpec( true, true, Floor.REQUIRED, new ObjSchema( null,
				new Field( "cell", true, intRange( 0, 65535 ) ),
				new Field( "kind", true, enumOf( MARK_KINDS ) ) ) ) );
		SPECS.put( QUICK, new TypeSpec( true, true, Floor.OPTIONAL, new ObjSchema( null,
				new Field( "phraseId", true, intRange( 0, 63 ) ) ) ) );
	}

	//every v1 type a decoder of this direction accepts
	public static List<String> types( Direction dir ) {
		List<String> out = new ArrayList<>();
		for (Map.Entry<String, TypeSpec> e : SPECS.entrySet()) {
			if (dir == Direction.C2S ? e.getValue().c2s : e.getValue().s2c) out.add( e.getKey() );
		}
		Collections.sort( out );
		return out;
	}

	private static final List<String> ENVELOPE_KEYS = Arrays.asList( "v", "t", "seq", "ack", "from", "depth", "branch", "p" );

	private static String join( String prefix, String name ) {
		return prefix.isEmpty() ? name : prefix + "." + name;
	}

	private static final class Validator {
		final Direction dir;
		DecodeError err;
		final List<String> ignored = new ArrayList<>();

		Validator( Direction dir ) {
			this.dir = dir;
		}

		void fail( String kind, String path, String msg ) {
			if (err == null) err = new DecodeError( kind, path, msg );
		}

		@SuppressWarnings("unchecked")
		Message envelope( Map<String, Object> root ) {
			Message m = new Message();

			//v first: an unsupported version is answered with VERSION_MISMATCH before anything else
			if (!root.containsKey( "v" )) {
				fail( KIND_MISSING_FIELD, "v", "required" );
				return m;
			}
			Long ver = integer( "v", root.get( "v" ), 0, MAX_SAFE_INT );
			if (ver == null) return m;
			if (ver != VERSION) {
				fail( KIND_UNSUPPORTED_VERSION, "v", "unsupported envelope version " + ver );
				return m;
			}

			if (!root.containsKey( "t" )) {
				fail( KIND_MISSING_FIELD, "t", "required" );
				return m;
			}
			if (!(root.get( "t" ) instanceof String)) {
				fail( KIND_WRONG_TYPE, "t", "must be a string" );
				return m;
			}
			String t = (String) root.get( "t" );
			TypeSpec spec = SPECS.get( t );
			if (spec == null || (dir == Direction.C2S && !spec.c2s) || (dir == Direction.S2C && !spec.s2c)) {
				fail( KIND_UNKNOWN_TYPE, "t", "\"" + t + "\" is not a v1 " + dir + " type" );
				return m;
			}
			m.t = t;

			unknownKeys( "", root, ENVELOPE_KEYS );

			if (!root.containsKey( "seq" )) {
				fail( KIND_MISSING_FIELD, "seq", "required" );
			} else {
				Long n = integer( "seq", root.get( "seq" ), 1, MAX_SAFE_INT );
				if (n != null) m.seq = n;
			}
			if (root.containsKey( "ack" )) {
				Long n = integer( "ack", root.get( "ack" ), 0, MAX_SAFE_INT );
				if (n != null) m.ack = n;
			}

			//from: ignored on c2s (but must be a string); on s2c required exactly on relayed presence
			boolean hasFrom = root.containsKey( "from" );
			Object rawFrom = root.get( "from" );
			if (dir == Direction.C2S && hasFrom) {
				if (rawFrom instanceof String) m.from = (String) rawFrom;
				else fail( KIND_WRONG_TYPE, "from", "must be a string" );
			} else if (dir == Direction.S2C && presence( t ) && !hasFrom) {
				fail( KIND_MISSING_FIELD, "from", "required on relayed " + t );
			} else if (dir == Direction.S2C && presence( t )) {
				String s = str( "from", rawFrom );
				if (s != null && match( "from", s, HEX_ID, "a 16-char lowercase hex playerId" )) m.from = s;
			} else if (dir == Direction.S2C && hasFrom) {
				fail( KIND_FORBIDDEN_FIELD, "from", "only relayed GHOST/MARK/QUICK carry from" );
			}

			floor( root, spec.floor, t, m );

			if (!root.containsKey( "p" )) {
				fail( KIND_MISSING_FIELD, "p", "required (use {} for an empty payload)" );
				return m;
			}
			if (!(root.get( "p" ) instanceof Map)) {
				fail( KIND_WRONG_TYPE, "p", "must be an object" );
				return m;
			}
			Map<String, Object> p = (Map<String, Object>) root.get( "p" );
			object( "p", p, spec.payload );
			m.p = p;
			return m;
		}

		void floor( Map<String, Object> root, Floor rule, String t, Message m ) {
			boolean hasD = root.containsKey( "depth" );
			boolean hasB = root.containsKey( "branch" );
			if (rule == Floor.FORBIDDEN && hasD) {
				fail( KIND_FORBIDDEN_FIELD, "depth", t + " does not carry depth/branch" );
				return;
			} else if (rule == Floor.FORBIDDEN && hasB) {
				fail( KIND_FORBIDDEN_FIELD, "branch", t + " does not carry depth/branch" );
				return;
			} else if (rule == Floor.REQUIRED && !hasD) {
				fail( KIND_MISSING_FIELD, "depth", t + " requires depth and branch" );
				return;
			} else if ((rule == Floor.REQUIRED && !hasB) || (rule == Floor.OPTIONAL && hasD && !hasB)) {
				fail( KIND_MISSING_FIELD, "branch", "depth and branch go together" );
				return;
			} else if (rule == Floor.OPTIONAL && hasB && !hasD) {
				fail( KIND_MISSING_FIELD, "depth", "depth and branch go together" );
				return;
			}
			if (hasD) {
				Long n = integer( "depth", root.get( "depth" ), 1, 30 );
				if (n != null) m.depth = n.intValue();
			}
			if (hasB) {
				Long n = integer( "branch", root.get( "branch" ), 0, 9 );
				if (n != null) m.branch = n.intValue();
			}
		}

		//rejects (c2s) or drops and records (s2c) keys outside known, in sorted order
		void unknownKeys( String prefix, Map<String, Object> obj, List<String> known ) {
			List<String> extra = new ArrayList<>();
			for (String k : obj.keySet()) {
				if (!known.contains( k )) extra.add( k );
			}
			Collections.sort( extra );
			for (String k : extra) {
				String path = join( prefix, k );
				if (dir == Direction.C2S) {
					fail( KIND_UNKNOWN_FIELD, path, "unknown field" );
					return;
				}
				ignored.add( path );
				obj.remove( k );
			}
		}

		void object( String path, Map<String, Object> obj, ObjSchema s ) {
			List<String> known = new ArrayList<>();
			for (Field f : s.fields) known.add( f.name );
			unknownKeys( path, obj, known );
			for (Field f : s.fields) {
				if (!obj.containsKey( f.name )) {
					if (f.required) fail( KIND_MISSING_FIELD, join( path, f.name ), "required" );
					continue;
				}
				f.check.apply( this, join( path, f.name ), obj.get( f.name ) );
			}
			if (s.check != null && err == null) s.check.apply( this, path, obj );
		}

		//a JSON number without fraction or exponent in [lo, hi]; null (and a failure) otherwise
		Long integer( String path, Object x, long lo, long hi ) {
			if (!(x instanceof Num) || !((Num) x).isInteger()) {
				fail( KIND_WRONG_TYPE, path, "must be an integer" );
				return null;
			}
			BigInteger n = new BigInteger( ((Num) x).text );
			if (n.compareTo( BigInteger.valueOf( lo ) ) < 0 || n.compareTo( BigInteger.valueOf( hi ) ) > 0) {
				fail( KIND_OUT_OF_RANGE, path, "must be in " + lo + ".." + hi + ", got " + x );
				return null;
			}
			return n.longValue();
		}

		String str( String path, Object x ) {
			if (!(x instanceof String)) {
				fail( KIND_WRONG_TYPE, path, "must be a string" );
				return null;
			}
			return (String) x;
		}

		boolean match( String path, String s, Pattern re, String what ) {
			if (!re.matcher( s ).matches()) {
				fail( KIND_BAD_FORMAT, path, "must be " + what );
				return false;
			}
			return true;
		}
	}
}
