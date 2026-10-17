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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON writer/parser for the golden files (TST-01).
 *
 * <p>The test source set has no JSON dependency, and the golden format is a
 * small, fixed document, so this covers exactly what the format needs:
 * objects, arrays, strings, longs, booleans and null. Map iteration order is
 * preserved when writing, and parsing returns insertion-ordered maps, so the
 * written document is byte-for-byte stable.
 */
final class Json {

	private Json() {
	}

	// ----- writing ---------------------------------------------------------

	static String write(Object value) {
		StringBuilder sb = new StringBuilder();
		writeValue(sb, value, 0);
		sb.append('\n');
		return sb.toString();
	}

	private static void writeValue(StringBuilder sb, Object value, int indent) {
		if (value == null) {
			sb.append("null");
		} else if (value instanceof Map) {
			writeObject(sb, (Map<?, ?>) value, indent);
		} else if (value instanceof List) {
			writeArray(sb, (List<?>) value, indent);
		} else if (value instanceof String) {
			writeString(sb, (String) value);
		} else if (value instanceof Boolean) {
			sb.append(value);
		} else if (value instanceof Long || value instanceof Integer) {
			sb.append(value);
		} else {
			throw new IllegalArgumentException("unsupported json value: " + value.getClass());
		}
	}

	private static void writeObject(StringBuilder sb, Map<?, ?> map, int indent) {
		if (map.isEmpty()) {
			sb.append("{}");
			return;
		}
		sb.append("{\n");
		int i = 0;
		for (Map.Entry<?, ?> e : map.entrySet()) {
			indent(sb, indent + 1);
			writeString(sb, String.valueOf(e.getKey()));
			sb.append(": ");
			writeValue(sb, e.getValue(), indent + 1);
			if (++i < map.size()) {
				sb.append(',');
			}
			sb.append('\n');
		}
		indent(sb, indent);
		sb.append('}');
	}

	private static void writeArray(StringBuilder sb, List<?> list, int indent) {
		if (list.isEmpty()) {
			sb.append("[]");
			return;
		}
		sb.append("[\n");
		for (int i = 0; i < list.size(); i++) {
			indent(sb, indent + 1);
			writeValue(sb, list.get(i), indent + 1);
			if (i < list.size() - 1) {
				sb.append(',');
			}
			sb.append('\n');
		}
		indent(sb, indent);
		sb.append(']');
	}

	private static void indent(StringBuilder sb, int levels) {
		for (int i = 0; i < levels; i++) {
			sb.append("  ");
		}
	}

	private static void writeString(StringBuilder sb, String s) {
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"': sb.append("\\\""); break;
				case '\\': sb.append("\\\\"); break;
				case '\n': sb.append("\\n"); break;
				case '\r': sb.append("\\r"); break;
				case '\t': sb.append("\\t"); break;
				case '\b': sb.append("\\b"); break;
				case '\f': sb.append("\\f"); break;
				default:
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
			}
		}
		sb.append('"');
	}

	// ----- parsing ---------------------------------------------------------

	static Object parse(String text) {
		Parser parser = new Parser(text);
		Object value = parser.parseValue();
		parser.skipWhitespace();
		if (!parser.atEnd()) {
			throw new IllegalArgumentException("trailing content at offset " + parser.pos);
		}
		return value;
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> parseObject(String text) {
		return (Map<String, Object>) parse(text);
	}

	private static final class Parser {

		private final String text;
		private int pos;

		Parser(String text) {
			this.text = text;
		}

		boolean atEnd() {
			return pos >= text.length();
		}

		void skipWhitespace() {
			while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
				pos++;
			}
		}

		Object parseValue() {
			skipWhitespace();
			if (atEnd()) {
				throw error("unexpected end of input");
			}
			char c = text.charAt(pos);
			switch (c) {
				case '{': return parseObjectValue();
				case '[': return parseArrayValue();
				case '"': return parseStringValue();
				case 't': expect("true"); return Boolean.TRUE;
				case 'f': expect("false"); return Boolean.FALSE;
				case 'n': expect("null"); return null;
				default:
					if (c == '-' || (c >= '0' && c <= '9')) {
						return parseNumberValue();
					}
					throw error("unexpected character '" + c + "'");
			}
		}

		private Map<String, Object> parseObjectValue() {
			expect("{");
			Map<String, Object> map = new LinkedHashMap<>();
			skipWhitespace();
			if (peek() == '}') {
				pos++;
				return map;
			}
			while (true) {
				skipWhitespace();
				String key = parseStringValue();
				skipWhitespace();
				expect(":");
				Object value = parseValue();
				if (map.put(key, value) != null) {
					throw error("duplicate key \"" + key + "\"");
				}
				skipWhitespace();
				char c = peek();
				if (c == ',') {
					pos++;
				} else if (c == '}') {
					pos++;
					return map;
				} else {
					throw error("expected ',' or '}'");
				}
			}
		}

		private List<Object> parseArrayValue() {
			expect("[");
			List<Object> list = new ArrayList<>();
			skipWhitespace();
			if (peek() == ']') {
				pos++;
				return list;
			}
			while (true) {
				list.add(parseValue());
				skipWhitespace();
				char c = peek();
				if (c == ',') {
					pos++;
				} else if (c == ']') {
					pos++;
					return list;
				} else {
					throw error("expected ',' or ']'");
				}
			}
		}

		private String parseStringValue() {
			skipWhitespace();
			expect("\"");
			StringBuilder sb = new StringBuilder();
			while (true) {
				if (atEnd()) {
					throw error("unterminated string");
				}
				char c = text.charAt(pos++);
				if (c == '"') {
					return sb.toString();
				}
				if (c != '\\') {
					sb.append(c);
					continue;
				}
				if (atEnd()) {
					throw error("unterminated escape");
				}
				char escape = text.charAt(pos++);
				switch (escape) {
					case '"': sb.append('"'); break;
					case '\\': sb.append('\\'); break;
					case '/': sb.append('/'); break;
					case 'n': sb.append('\n'); break;
					case 'r': sb.append('\r'); break;
					case 't': sb.append('\t'); break;
					case 'b': sb.append('\b'); break;
					case 'f': sb.append('\f'); break;
					case 'u':
						if (pos + 4 > text.length()) {
							throw error("truncated unicode escape");
						}
						sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
						pos += 4;
						break;
					default:
						throw error("invalid escape '\\" + escape + "'");
				}
			}
		}

		private Object parseNumberValue() {
			int start = pos;
			if (peek() == '-') {
				pos++;
			}
			while (!atEnd() && Character.isDigit(text.charAt(pos))) {
				pos++;
			}
			boolean isLong = true;
			if (!atEnd() && text.charAt(pos) == '.') {
				isLong = false;
				pos++;
				while (!atEnd() && Character.isDigit(text.charAt(pos))) {
					pos++;
				}
			}
			if (!atEnd() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
				isLong = false;
				pos++;
				if (!atEnd() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
					pos++;
				}
				while (!atEnd() && Character.isDigit(text.charAt(pos))) {
					pos++;
				}
			}
			String number = text.substring(start, pos);
			if (isLong) {
				return Long.parseLong(number);
			}
			return Double.parseDouble(number);
		}

		private char peek() {
			if (atEnd()) {
				throw error("unexpected end of input");
			}
			return text.charAt(pos);
		}

		private void expect(String literal) {
			if (!text.startsWith(literal, pos)) {
				throw error("expected \"" + literal + "\"");
			}
			pos += literal.length();
		}

		private IllegalArgumentException error(String message) {
			return new IllegalArgumentException("invalid json at offset " + pos + ": " + message);
		}
	}
}
