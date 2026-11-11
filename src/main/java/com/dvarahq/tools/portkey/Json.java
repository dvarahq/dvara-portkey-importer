/*
 * Copyright 2026 DVARA Labs, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dvarahq.tools.portkey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, strict JSON reader: objects become ordered maps, arrays lists, numbers {@code Long} or
 * {@code Double}. Enough for a Portkey config, and no dependency to ship.
 *
 * <p>An error names the line and column, never the text around it, since a config can hold a key.
 */
final class Json {

    /** Input that is not JSON. */
    static final class ParseException extends RuntimeException {
        ParseException(String message) {
            super(message);
        }
    }

    /** Deep enough for any real config, shallow enough that a hostile file cannot overflow the stack. */
    private static final int MAX_DEPTH = 64;

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        Json p = new Json(text.startsWith("﻿") ? text.substring(1) : text);
        p.skipSpace();
        Object v = p.value(0);
        p.skipSpace();
        if (p.i != p.s.length()) {
            throw p.error("unexpected text after the end of the JSON value");
        }
        return v;
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("nested more than " + MAX_DEPTH + " levels deep");
        }
        if (i >= s.length()) {
            throw error("unexpected end of input");
        }
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield number();
                }
                throw error("unexpected character");
            }
        };
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        i++;
        skipSpace();
        if (peek() == '}') {
            i++;
            return map;
        }
        while (true) {
            skipSpace();
            if (peek() != '"') {
                throw error("expected a quoted key");
            }
            String key = string();
            skipSpace();
            expect(':');
            skipSpace();
            // A repeated key keeps the last value, as JavaScript's JSON.parse does in Portkey.
            map.remove(key);
            map.put(key, value(depth + 1));
            skipSpace();
            char c = next();
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw error("expected ',' or '}'");
            }
        }
    }

    private List<Object> array(int depth) {
        List<Object> list = new ArrayList<>();
        i++;
        skipSpace();
        if (peek() == ']') {
            i++;
            return list;
        }
        while (true) {
            skipSpace();
            list.add(value(depth + 1));
            skipSpace();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw error("expected ',' or ']'");
            }
        }
    }

    private String string() {
        i++;
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) {
                throw error("unterminated string");
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return b.toString();
            }
            if (c < 0x20) {
                throw error("control character in a string");
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = next();
            switch (e) {
                case '"' -> b.append('"');
                case '\\' -> b.append('\\');
                case '/' -> b.append('/');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) {
                        throw error("bad \\u escape");
                    }
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("bad \\u escape");
                    }
                    i += 4;
                }
                default -> throw error("bad escape");
            }
        }
    }

    private Object number() {
        int start = i;
        if (peek() == '-') {
            i++;
        }
        boolean fraction = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                i++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                fraction = true;
                i++;
            } else {
                break;
            }
        }
        String n = s.substring(start, i);
        try {
            if (!fraction) {
                try {
                    return Long.parseLong(n);
                } catch (NumberFormatException tooBig) {
                    return Double.parseDouble(n);
                }
            }
            return Double.parseDouble(n);
        } catch (NumberFormatException e) {
            throw error("bad number");
        }
    }

    private Object literal(String word, Object v) {
        if (!s.startsWith(word, i)) {
            throw error("unexpected character");
        }
        i += word.length();
        return v;
    }

    private void skipSpace() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
            } else {
                break;
            }
        }
    }

    private char peek() {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private char next() {
        if (i >= s.length()) {
            throw error("unexpected end of input");
        }
        return s.charAt(i++);
    }

    private void expect(char c) {
        if (next() != c) {
            i--;
            throw error("expected '" + c + "'");
        }
    }

    private ParseException error(String what) {
        int line = 1;
        int col = 1;
        for (int k = 0; k < Math.min(i, s.length()); k++) {
            if (s.charAt(k) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new ParseException(what + " at line " + line + ", column " + col);
    }
}
