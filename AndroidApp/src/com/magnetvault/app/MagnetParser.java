package com.magnetvault.app;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared parsing for magnet and ED2K file links; stored URLs are never rewritten. */
public final class MagnetParser {
    private static final String ED2K_CANDIDATE =
            "ed2k://\\|file\\|[^|\\r\\n]*\\|[^|\\r\\n]*\\|[^|\\r\\n]*\\|"
                    + "(?:(?!/)[^|\\r\\n]+\\|)*/(?:\\|sources,[^|\\r\\n]+\\|/)?";
    private static final Pattern LINKS =
            Pattern.compile(ED2K_CANDIDATE + "|magnet:\\?[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern ED2K_FILE =
            Pattern.compile(
                    "^ed2k://\\|file\\|([^|\\r\\n]+)\\|([0-9]+)\\|([0-9a-f]{32})\\|"
                            + "((?:(?!/)[^|\\r\\n]+\\|)*)/(?:\\|sources,[^|\\r\\n]+\\|/)?$",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern BTIH =
            Pattern.compile(
                    "[?&]xt=urn:btih:([A-Za-z0-9]{32,40})(?=[&#]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEX40 = Pattern.compile("^[0-9a-fA-F]{40}$");
    private static final Pattern XL =
            Pattern.compile("[?&]xl=(\\d+)(?=[&#]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DN = Pattern.compile("[?&]dn=([^&#]*)", Pattern.CASE_INSENSITIVE);
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private MagnetParser() {}

    public static List<String> extractAll(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Matcher m = LINKS.matcher(text);
        while (m.find()) {
            String link = m.group().trim();
            if (!isEd2k(link)) {
                while (link.length() > 7) {
                    char last = link.charAt(link.length() - 1);
                    if (last == ','
                            || last == '.'
                            || last == ')'
                            || last == '>'
                            || last == ']'
                            || last == '，'
                            || last == '。'
                            || last == '、') {
                        link = link.substring(0, link.length() - 1);
                    } else break;
                }
            }
            // Keep case-sensitive names, parameters and optional source fields intact.
            if (seen.add(link)) out.add(link);
        }
        return out;
    }

    public static boolean isEd2k(String link) {
        return link != null && link.trim().regionMatches(true, 0, "ed2k://", 0, 7);
    }

    public static boolean isValid(String link) {
        if (link == null) return false;
        String s = link.trim();
        if (hasControl(s)) return false;
        if (isEd2k(s)) return ed2k(s) != null;
        return s.regionMatches(true, 0, "magnet:?", 0, 8) && btih(s) != null;
    }

    /** BTIH = 40 hex digits; ED2K = 32 hex digits (MD4). Invalid links return null. */
    public static String infoHash(String link) {
        if (link == null) return null;
        String s = link.trim();
        if (hasControl(s)) return null;
        if (isEd2k(s)) {
            Ed2k file = ed2k(s);
            return file == null ? null : file.hash;
        }
        return s.regionMatches(true, 0, "magnet:?", 0, 8) ? btih(s) : null;
    }

    private static String btih(String link) {
        Matcher m = BTIH.matcher(link);
        if (!m.find()) return null;
        String raw = m.group(1).toUpperCase(Locale.US);
        if (raw.length() == 40)
            return HEX40.matcher(raw).matches() ? raw.toLowerCase(Locale.US) : null;
        if (raw.length() == 32) {
            byte[] bytes = base32Decode(raw);
            return bytes == null || bytes.length != 20 ? null : toHex(bytes);
        }
        return null;
    }

    /** Content identity: BTIH, or ED2K MD4 plus exact size; filename is not an identity. */
    public static String normalize(String link) {
        if (link == null) return "";
        String s = link.trim();
        if (isEd2k(s)) {
            Ed2k file = ed2k(s);
            if (file != null) return "ed2k:" + file.hash + ":" + file.size;
        } else {
            String hash = infoHash(s);
            if (hash != null) return "btih:" + hash;
        }
        return s.toLowerCase(Locale.US);
    }

    /** Exact bytes from ED2K's size field or magnet's xl parameter. */
    public static long xlSize(String link) {
        if (link == null || link.isEmpty()) return -1;
        if (isEd2k(link)) {
            Ed2k file = ed2k(link.trim());
            return file == null ? -1 : file.size;
        }
        if (!link.trim().regionMatches(true, 0, "magnet:?", 0, 8)) return -1;
        Matcher m = XL.matcher(link);
        if (!m.find()) return -1;
        try {
            long v = Long.parseLong(m.group(1));
            return v > 0 ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** ED2K filename or magnet dn; callers preserve an explicit user title. */
    public static String titleFromLink(String link) {
        if (!isValid(link)) return "";
        if (isEd2k(link)) return ed2k(link.trim()).name;
        Matcher m = DN.matcher(link);
        return m.find() ? decode(m.group(1), false).trim() : "";
    }

    private static Ed2k ed2k(String link) {
        if (hasControl(link)) return null;
        Matcher m = ED2K_FILE.matcher(link);
        if (!m.matches()) return null;
        String decoded = decode(m.group(1), true);
        if (hasControl(decoded)) return null;
        String name = decoded.trim();
        if (name.isEmpty()) return null;
        try {
            long size = Long.parseLong(m.group(2));
            if (size <= 0) return null;
            String extra = m.group(4);
            if (!extra.isEmpty()) {
                for (String token : extra.split("\\|")) {
                    if (token.length() < 3 || token.charAt(1) != '=') return null;
                    char kind = Character.toLowerCase(token.charAt(0));
                    if (kind != 'h' && kind != 'p' && kind != 's') return null;
                }
            }
            return new Ed2k(name, size, m.group(3).toLowerCase(Locale.US));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String decode(String value, boolean literalPlus) {
        try {
            return URLDecoder.decode(literalPlus ? value.replace("+", "%2B") : value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++)
            if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }

    private static final class Ed2k {
        final String name, hash;
        final long size;

        Ed2k(String name, long size, String hash) {
            this.name = name;
            this.size = size;
            this.hash = hash;
        }
    }

    private static byte[] base32Decode(String s) {
        int buffer = 0, bitsLeft = 0, pos = 0;
        byte[] out = new byte[20];
        for (int i = 0; i < s.length(); i++) {
            int val = BASE32.indexOf(s.charAt(i));
            if (val < 0) return null;
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                if (pos >= out.length) return null;
                out[pos++] = (byte) ((buffer >> bitsLeft) & 0xFF);
            }
        }
        return pos == out.length ? out : null;
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
