package com.magnetvault.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Display-only parser that highlights media IDs and bracketed metadata without modifying stored data. */
public final class TitleFormat {

    public static final class Parsed {
        public final String raw;
        public final String code;
        public final List<String> badges;
        public final String summary;

        Parsed(String raw, String code, List<String> badges, String summary) {
            this.raw = raw;
            this.code = code;
            this.badges = badges;
            this.summary = summary;
        }

        public boolean hasFocus() {
            return !code.isEmpty() || !badges.isEmpty();
        }
    }

    private static final Pattern BRACKET = Pattern.compile("(?:\\[([^\\[\\]\\r\\n]{1,20})\\]|【([^【】\\r\\n]{1,20})】)");
    private static final Pattern CODE =
            Pattern.compile(
                    "(?<![A-Za-z0-9-])("
                            + "(?:FC2-?PPV-?|FC2-?)[0-9]{5,8}"
                            + "|(?:HEYZO-?|TOKYO-?HOT-?[a-z]?)[0-9]{3,6}"
                            + "|(?:1PONDO|CARIBBEANCOM|PACOPACOMAMA)[_-]?[0-9]{6}[_-][0-9]{2,4}"
                            + "|(?:[0-9]{2,4}[A-Za-z]{2,6}|[A-Za-z][0-9]{2}|[A-Za-z]{2,6})-[0-9]{2,6}(?:-[A-Za-z0-9]{1,3})?"
                            + "|S[0-9]{1,2}E[0-9]{1,3}"
                            + ")(?![A-Za-z0-9-])",
                    Pattern.CASE_INSENSITIVE);
    private static final Set<String> NON_CODE_PREFIXES =
            new HashSet<>(
                    Arrays.asList(
                            "SHA", "AES", "RSA", "DES", "MD5", "UTF", "ISO", "WIN", "MAC", "IOS",
                            "X86", "X64", "H264", "H265", "X264", "X265", "AVC", "HEVC", "MP3",
                            "MP4", "MKV", "AAC", "DTS", "HDR", "SDR", "DDP", "DVD", "BLU", "BIT",
                            "RGB", "YUV", "FPS", "KBPS", "MBPS", "API", "SDK", "HTTP", "HTTPS",
                            "USB", "HDMI", "PCI", "SATA", "BDRIP", "WEBRIP", "HDTV"));

    private TitleFormat() {}

    public static Parsed parse(String rawTitle) {
        String raw = rawTitle == null ? "" : rawTitle.trim();
        if (raw.isEmpty()) return new Parsed("", "", Collections.emptyList(), "");

        // 1. Find primary media ID / episode marker
        String code = "";
        int codeStart = -1;
        int codeEnd = -1;
        Matcher cm = CODE.matcher(raw);
        while (cm.find()) {
            String candidate = cm.group(1);
            if (!isFalsePositiveCode(candidate)) {
                code = normalizeCode(candidate);
                codeStart = cm.start(1);
                codeEnd = cm.end(1);
                break;
            }
        }

        // 2. Collect up to 3 concise bracketed badges, recording spans to strip from summary
        List<int[]> stripSpans = new ArrayList<>();
        if (codeStart >= 0) stripSpans.add(new int[] {codeStart, codeEnd});

        List<String> badges = new ArrayList<>();
        Set<String> seenBadges = new HashSet<>();
        Matcher bm = BRACKET.matcher(raw);
        while (bm.find()) {
            int group = bm.group(1) != null ? 1 : 2;
            String rawInner = bm.group(group);
            if (rawInner == null) continue;
            String inner = rawInner.trim();
            if (!isCleanBadge(inner)) continue;

            // If bracket wraps the detected code (e.g. "[IPX-419]" or "[IPX-419 高清]"), avoid duplicating code inside badges
            if (!code.isEmpty() && bm.start() <= codeStart && bm.end() >= codeEnd) {
                stripSpans.add(new int[] {bm.start(), bm.end()});
                int gStart = bm.start(group);
                int relStart = Math.max(0, Math.min(rawInner.length(), codeStart - gStart));
                int relEnd = Math.max(relStart, Math.min(rawInner.length(), codeEnd - gStart));
                String remainder =
                        cleanSummary(rawInner.substring(0, relStart) + " " + rawInner.substring(relEnd));
                if (!remainder.isEmpty() && isCleanBadge(remainder) && badges.size() < 3) {
                    String key = remainder.toLowerCase(Locale.ROOT);
                    if (seenBadges.add(key)) {
                        badges.add(remainder);
                    }
                }
                continue;
            }

            if (badges.size() < 3) {
                String key = inner.toLowerCase(Locale.ROOT);
                if (seenBadges.add(key)) {
                    badges.add(inner);
                }
                stripSpans.add(new int[] {bm.start(), bm.end()});
            }
        }

        if (code.isEmpty() && badges.isEmpty()) {
            return new Parsed(raw, "", Collections.emptyList(), raw);
        }

        // 3. Build cleaned summary without stripped spans
        stripSpans.sort((a, b) -> Integer.compare(a[0], b[0]));
        StringBuilder sb = new StringBuilder(raw.length());
        int cursor = 0;
        for (int[] span : stripSpans) {
            if (span[1] <= cursor) continue;
            if (span[0] > cursor) sb.append(raw, cursor, span[0]);
            cursor = Math.max(cursor, span[1]);
        }
        if (cursor < raw.length()) sb.append(raw, cursor, raw.length());

        String summary = cleanSummary(sb.toString());
        if (summary.isEmpty()) {
            // Never leave an empty card line when no code exists
            if (code.isEmpty()) {
                return new Parsed(raw, "", Collections.emptyList(), raw);
            }
        }
        return new Parsed(raw, code, Collections.unmodifiableList(badges), summary);
    }

    private static boolean isFalsePositiveCode(String token) {
        int dash = token.indexOf('-');
        if (dash <= 0) return false;
        String prefix = token.substring(0, dash).toUpperCase(Locale.US);
        return NON_CODE_PREFIXES.contains(prefix);
    }

    private static String normalizeCode(String raw) {
        return raw.trim().toUpperCase(Locale.US);
    }

    private static boolean isCleanBadge(String inner) {
        if (inner.isEmpty() || inner.length() > 16) return false;
        String lower = inner.toLowerCase(Locale.ROOT);
        if (lower.contains("http://")
                || lower.contains("https://")
                || lower.startsWith("magnet:")
                || lower.startsWith("ed2k:")) {
            return false;
        }
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?' || c == ';' || c == '；') {
                return false;
            }
        }
        return true;
    }

    private static String cleanSummary(String s) {
        String collapsed = s.replaceAll("[ \\t\\u00A0]{2,}", " ").trim();
        int start = 0;
        int end = collapsed.length();
        while (start < end && isTrimPunct(collapsed.charAt(start))) start++;
        while (end > start && isTrimPunct(collapsed.charAt(end - 1))) end--;
        return collapsed.substring(start, end).trim();
    }

    private static boolean isTrimPunct(char c) {
        return c == ' '
                || c == '-'
                || c == '_'
                || c == '|'
                || c == '/'
                || c == '·'
                || c == ':'
                || c == '：'
                || c == ','
                || c == '，';
    }
}
