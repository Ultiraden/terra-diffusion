package com.github.xandergos.terraindiffusionmc.catalog;

import java.math.BigDecimal;
import java.util.*;

/** Strict JSON with duplicate-key detection before a map can discard an alias. */
final class StrictJson {
    private final String text;
    private int at;
    private StrictJson(String text) { this.text = Objects.requireNonNull(text); }
    static Object parse(String text) {
        StrictJson p = new StrictJson(text);
        Object value = p.value(0, "");
        p.space();
        if (p.at != text.length()) throw p.error("", "trailing input");
        return value;
    }
    private IllegalArgumentException error(String pointer, String message) {
        return new IllegalArgumentException((pointer.isEmpty() ? "/" : pointer) + ": " + message + " at character " + at);
    }
    private void space() { while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++; }
    private boolean take(char c) { space(); if (at < text.length() && text.charAt(at) == c) { at++; return true; } return false; }
    private void need(char c, String p) { if (!take(c)) throw error(p, "expected '" + c + "'"); }
    private Object value(int depth, String pointer) {
        if (depth > 64) throw error(pointer, "nesting exceeds 64");
        space();
        if (at >= text.length()) throw error(pointer, "missing value");
        char c = text.charAt(at);
        if (c == '{') {
            at++;
            Map<String,Object> map = new LinkedHashMap<>();
            if (take('}')) return map;
            do {
                space();
                if (at >= text.length() || text.charAt(at) != '"') throw error(pointer, "expected object key");
                String key = string(pointer);
                String child = pointer + "/" + key.replace("~", "~0").replace("/", "~1");
                if (map.containsKey(key)) throw error(child, "duplicate JSON key");
                need(':', child);
                map.put(key, value(depth + 1, child));
                if (take('}')) return map;
                need(',', pointer);
            } while (true);
        }
        if (c == '[') {
            at++;
            List<Object> list = new ArrayList<>();
            if (take(']')) return list;
            do {
                list.add(value(depth + 1, pointer + "/" + list.size()));
                if (take(']')) return list;
                need(',', pointer);
            } while (true);
        }
        if (c == '"') return string(pointer);
        for (String literal : List.of("true", "false", "null")) {
            if (text.startsWith(literal, at)) {
                at += literal.length();
                return literal.equals("null") ? null : literal.equals("true");
            }
        }
        int start = at;
        if (c == '-') at++;
        if (at < text.length() && text.charAt(at) == '0') at++;
        else {
            int digits = at;
            while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') at++;
            if (digits == at) throw error(pointer, "expected JSON value");
        }
        if (at < text.length() && text.charAt(at) == '.') { at++; digits(pointer); }
        if (at < text.length() && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
            at++;
            if (at < text.length() && (text.charAt(at) == '+' || text.charAt(at) == '-')) at++;
            digits(pointer);
        }
        try { return new BigDecimal(text.substring(start, at)); }
        catch (NumberFormatException e) { throw error(pointer, "invalid number"); }
    }
    private void digits(String p) {
        int start = at;
        while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') at++;
        if (at == start) throw error(p, "expected digits");
    }
    private String string(String p) {
        need('"', p);
        StringBuilder out = new StringBuilder();
        while (at < text.length()) {
            char c = text.charAt(at++);
            if (c == '"') return out.toString();
            if (c < 0x20) throw error(p, "unescaped control character");
            if (c != '\\') { out.append(c); continue; }
            if (at >= text.length()) throw error(p, "unfinished escape");
            char escape = text.charAt(at++);
            switch (escape) {
                case '"', '\\', '/' -> out.append(escape);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (at + 4 > text.length()) throw error(p, "unfinished unicode escape");
                    for (int i = at; i < at + 4; i++) {
                        char digit = text.charAt(i);
                        if (!(digit >= '0' && digit <= '9' || digit >= 'a' && digit <= 'f' || digit >= 'A' && digit <= 'F'))
                            throw error(p, "invalid unicode escape");
                    }
                    try { out.append((char) Integer.parseInt(text.substring(at, at + 4), 16)); }
                    catch (NumberFormatException e) { throw error(p, "invalid unicode escape"); }
                    at += 4;
                }
                default -> throw error(p, "invalid escape");
            }
        }
        throw error(p, "unterminated string");
    }
}
