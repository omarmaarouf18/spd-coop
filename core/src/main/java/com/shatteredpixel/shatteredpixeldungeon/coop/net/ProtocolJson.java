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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

//strict RFC 8259 JSON for the co-op protocol. libGDX's JsonReader is lenient (unquoted
// strings, comments) and drops a number's spelling, but PROTOCOL.md §1 must tell `1` from
// `1.0`/`1e0`, so numbers are kept as Num with their source text.
//Values: Map<String,Object> (insertion order), List<Object>, String, Boolean, Num, NULL.
public final class ProtocolJson {

	public static final Object NULL = new Object() {
		@Override
		public String toString() {
			return "null";
		}
	};

	public static final class Num {
		public final String text;

		public Num( String text ) {
			this.text = text;
		}

		public Num( long value ) {
			this.text = Long.toString( value );
		}

		public boolean isInteger() {
			return text.indexOf( '.' ) < 0 && text.indexOf( 'e' ) < 0 && text.indexOf( 'E' ) < 0;
		}

		@Override
		public boolean equals( Object o ) {
			if (!(o instanceof Num)) return false;
			Num other = (Num) o;
			if (isInteger() && other.isInteger()) {
				return new java.math.BigInteger( text ).equals( new java.math.BigInteger( other.text ) );
			}
			return new java.math.BigDecimal( text ).compareTo( new java.math.BigDecimal( other.text ) ) == 0;
		}

		@Override
		public int hashCode() {
			return new java.math.BigDecimal( text ).stripTrailingZeros().hashCode();
		}

		@Override
		public String toString() {
			return text;
		}
	}

	public static final class ParseException extends Exception {
		public ParseException( String msg ) {
			super( msg );
		}
	}

	private ProtocolJson() {}

	public static Object parse( String s ) throws ParseException {
		Parser p = new Parser( s );
		p.skipWhitespace();
		Object value = p.value();
		p.skipWhitespace();
		if (p.pos != s.length()) {
			throw new ParseException( "trailing data after the JSON value at " + p.pos );
		}
		return value;
	}

	private static final class Parser {
		private final String s;
		private int pos = 0;

		Parser( String s ) {
			this.s = s;
		}

		void skipWhitespace() {
			while (pos < s.length()) {
				char c = s.charAt( pos );
				if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
				else break;
			}
		}

		ParseException error( String what ) {
			return new ParseException( what + " at " + pos );
		}

		Object value() throws ParseException {
			if (pos >= s.length()) throw error( "unexpected end" );
			char c = s.charAt( pos );
			switch (c) {
				case '{': return object();
				case '[': return array();
				case '"': return string();
				case 't': literal( "true" ); return Boolean.TRUE;
				case 'f': literal( "false" ); return Boolean.FALSE;
				case 'n': literal( "null" ); return NULL;
				default:
					if (c == '-' || (c >= '0' && c <= '9')) return number();
					throw error( "unexpected character '" + c + "'" );
			}
		}

		void literal( String word ) throws ParseException {
			if (!s.startsWith( word, pos )) throw error( "bad literal" );
			pos += word.length();
		}

		Map<String, Object> object() throws ParseException {
			Map<String, Object> out = new LinkedHashMap<>();
			pos++;
			skipWhitespace();
			if (pos < s.length() && s.charAt( pos ) == '}') {
				pos++;
				return out;
			}
			while (true) {
				skipWhitespace();
				if (pos >= s.length() || s.charAt( pos ) != '"') throw error( "expected a key" );
				String key = string();
				skipWhitespace();
				if (pos >= s.length() || s.charAt( pos ) != ':') throw error( "expected ':'" );
				pos++;
				skipWhitespace();
				//like Go's encoding/json, a repeated key keeps the last value
				out.remove( key );
				out.put( key, value() );
				skipWhitespace();
				if (pos >= s.length()) throw error( "unexpected end" );
				char c = s.charAt( pos++ );
				if (c == '}') return out;
				if (c != ',') throw error( "expected ',' or '}'" );
			}
		}

		List<Object> array() throws ParseException {
			List<Object> out = new ArrayList<>();
			pos++;
			skipWhitespace();
			if (pos < s.length() && s.charAt( pos ) == ']') {
				pos++;
				return out;
			}
			while (true) {
				skipWhitespace();
				out.add( value() );
				skipWhitespace();
				if (pos >= s.length()) throw error( "unexpected end" );
				char c = s.charAt( pos++ );
				if (c == ']') return out;
				if (c != ',') throw error( "expected ',' or ']'" );
			}
		}

		String string() throws ParseException {
			StringBuilder b = new StringBuilder();
			pos++;
			while (true) {
				if (pos >= s.length()) throw error( "unterminated string" );
				char c = s.charAt( pos++ );
				if (c == '"') return b.toString();
				if (c < 0x20) throw error( "control character in string" );
				if (c != '\\') {
					b.append( c );
					continue;
				}
				if (pos >= s.length()) throw error( "unterminated escape" );
				char e = s.charAt( pos++ );
				switch (e) {
					case '"': b.append( '"' ); break;
					case '\\': b.append( '\\' ); break;
					case '/': b.append( '/' ); break;
					case 'b': b.append( '\b' ); break;
					case 'f': b.append( '\f' ); break;
					case 'n': b.append( '\n' ); break;
					case 'r': b.append( '\r' ); break;
					case 't': b.append( '\t' ); break;
					case 'u':
						if (pos + 4 > s.length()) throw error( "short \\u escape" );
						try {
							b.append( (char) Integer.parseInt( s.substring( pos, pos + 4 ), 16 ) );
						} catch (NumberFormatException ex) {
							throw error( "bad \\u escape" );
						}
						pos += 4;
						break;
					default:
						throw error( "bad escape" );
				}
			}
		}

		Num number() throws ParseException {
			int start = pos;
			if (s.charAt( pos ) == '-') pos++;
			if (pos >= s.length()) throw error( "bad number" );
			if (s.charAt( pos ) == '0') {
				pos++;
			} else if (s.charAt( pos ) >= '1' && s.charAt( pos ) <= '9') {
				while (pos < s.length() && Character.isDigit( s.charAt( pos ) ) && s.charAt( pos ) < 128) pos++;
			} else {
				throw error( "bad number" );
			}
			if (pos < s.length() && s.charAt( pos ) == '.') {
				pos++;
				int digits = pos;
				while (pos < s.length() && s.charAt( pos ) >= '0' && s.charAt( pos ) <= '9') pos++;
				if (pos == digits) throw error( "bad fraction" );
			}
			if (pos < s.length() && (s.charAt( pos ) == 'e' || s.charAt( pos ) == 'E')) {
				pos++;
				if (pos < s.length() && (s.charAt( pos ) == '+' || s.charAt( pos ) == '-')) pos++;
				int digits = pos;
				while (pos < s.length() && s.charAt( pos ) >= '0' && s.charAt( pos ) <= '9') pos++;
				if (pos == digits) throw error( "bad exponent" );
			}
			return new Num( s.substring( start, pos ) );
		}
	}

	//compact JSON, keys in map order
	public static String write( Object value ) {
		StringBuilder b = new StringBuilder();
		write( b, value );
		return b.toString();
	}

	@SuppressWarnings("unchecked")
	private static void write( StringBuilder b, Object v ) {
		if (v == null || v == NULL) {
			b.append( "null" );
		} else if (v instanceof Map) {
			b.append( '{' );
			boolean first = true;
			for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
				if (!first) b.append( ',' );
				first = false;
				writeString( b, e.getKey() );
				b.append( ':' );
				write( b, e.getValue() );
			}
			b.append( '}' );
		} else if (v instanceof List) {
			b.append( '[' );
			boolean first = true;
			for (Object e : (List<Object>) v) {
				if (!first) b.append( ',' );
				first = false;
				write( b, e );
			}
			b.append( ']' );
		} else if (v instanceof String) {
			writeString( b, (String) v );
		} else if (v instanceof Boolean || v instanceof Num) {
			b.append( v.toString() );
		} else if (v instanceof Integer || v instanceof Long) {
			b.append( v.toString() );
		} else {
			throw new IllegalArgumentException( "not a JSON value: " + v.getClass() );
		}
	}

	private static void writeString( StringBuilder b, String s ) {
		b.append( '"' );
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt( i );
			switch (c) {
				case '"': b.append( "\\\"" ); break;
				case '\\': b.append( "\\\\" ); break;
				case '\n': b.append( "\\n" ); break;
				case '\r': b.append( "\\r" ); break;
				case '\t': b.append( "\\t" ); break;
				default:
					if (c < 0x20) {
						//not String.format: its output depends on the device locale
						String hex = Integer.toHexString( c );
						b.append( "\\u" );
						for (int pad = hex.length(); pad < 4; pad++) b.append( '0' );
						b.append( hex );
					} else {
						b.append( c );
					}
			}
		}
		b.append( '"' );
	}
}
