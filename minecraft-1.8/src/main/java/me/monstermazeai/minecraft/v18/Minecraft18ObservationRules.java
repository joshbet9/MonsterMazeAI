package me.monstermazeai.minecraft.v18;

import me.monstermazeai.kit.Kit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Pure 1.8 observation parsing rules. No Minecraft classes are referenced so
 * the rules can be unit-tested without launching the client.
 */
public final class Minecraft18ObservationRules {
    private Minecraft18ObservationRules() {
    }

    public static ScoreboardData parseScoreboard(String title, List<String> lines) {
        String cleanTitle = stripFormatting(title);
        List<String> cleanLines = new ArrayList<String>();
        for (String line : lines) {
            cleanLines.add(stripFormatting(line));
        }

        int safePadSeconds = 0;
        int stage = 1;
        boolean completed = false;
        for (int i = 0; i < cleanLines.size(); i++) {
            String line = cleanLines.get(i);
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("complete") || lower.contains("victory") || lower.contains("winner")) {
                completed = true;
            }

            if (lower.contains("safe pad")) {
                Integer value = firstInteger(line);
                if (value == null && i + 1 < cleanLines.size()) {
                    value = firstInteger(cleanLines.get(i + 1));
                }
                if (value != null) {
                    safePadSeconds = value;
                }
            }

            if (lower.equals("stage") || lower.startsWith("stage ")) {
                Integer value = firstInteger(line);
                if (value == null && i + 1 < cleanLines.size()) {
                    value = firstInteger(cleanLines.get(i + 1));
                }
                if (value != null) {
                    stage = Math.max(1, value);
                }
            }
        }

        return new ScoreboardData(cleanTitle, safePadSeconds, stage, completed, cleanLines);
    }

    public static Kit detectKit(List<String> displayNames) {
        for (String displayName : displayNames) {
            if (displayName == null) {
                continue;
            }
            String name = stripFormatting(displayName).toLowerCase(Locale.ROOT);
            if (name.contains("jumps remaining")) return Kit.JUMPER;
            if (name.contains("repulse")) return Kit.REPULSOR;
            if (name.contains("cryo") || name.contains("slowball")) return Kit.SLOWBALLER;
            if (name.contains("body rush") || name.contains("body builder")) return Kit.BODY_BUILDER;
            if (name.contains("maverick")) return Kit.MAVERICK;
        }
        return Kit.JUMPER;
    }

    public static int detectJumpCharges(List<String> displayNames, Kit kit, List<Integer> stackSizes) {
        if (kit != Kit.JUMPER) {
            return 0;
        }
        int count = Math.min(displayNames.size(), stackSizes.size());
        for (int i = 0; i < count; i++) {
            String displayName = displayNames.get(i);
            if (displayName != null
                    && stripFormatting(displayName).toLowerCase(Locale.ROOT).contains("jumps remaining")) {
                return Math.max(0, stackSizes.get(i));
            }
        }
        return 0;
    }

    public static boolean looksLikeMonsterMaze(ScoreboardData scoreboard) {
        if (scoreboard.title.toLowerCase(Locale.ROOT).contains("monster maze")) {
            return true;
        }
        for (String line : scoreboard.lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("safe pad") || lower.equals("stage") || lower.startsWith("stage ")) {
                return true;
            }
        }
        return false;
    }

    private static Integer timeSeconds(String text) {
        if (text == null) return null;
        int colon = text.indexOf(':');
        if (colon >= 0) {
            int left = firstInteger(text.substring(0, colon)) == null
                    ? 0 : firstInteger(text.substring(0, colon));
            String rightText = text.substring(colon + 1);
            StringBuilder rightDigits = new StringBuilder();
            for (int i = 0; i < rightText.length(); i++) {
                char ch = rightText.charAt(i);
                if (!Character.isDigit(ch)) break;
                rightDigits.append(ch);
            }
            if (rightDigits.length() > 0) {
                try {
                    return left * 60 + Integer.parseInt(rightDigits.toString());
                } catch (NumberFormatException ignored) {
                    // Fall through to ordinary integer parsing.
                }
            }
        }
        return firstInteger(text);
    }

    private static Integer firstInteger(String text) {
        StringBuilder digits = new StringBuilder();
        boolean started = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isDigit(ch)) {
                digits.append(ch);
                started = true;
            } else if (started) {
                break;
            }
        }
        if (digits.length() == 0) {
            return null;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String stripFormatting(String text) {
        return text == null ? "" : text.replaceAll("\\u00a7.", "");
    }

    public static final class ScoreboardData {
        public final String title;
        public final int safePadSeconds;
        public final int stage;
        public final boolean completed;
        public final List<String> lines;

        private ScoreboardData(String title, int safePadSeconds, int stage, boolean completed, List<String> lines) {
            this.title = title;
            this.safePadSeconds = safePadSeconds;
            this.stage = stage;
            this.completed = completed;
            this.lines = Collections.unmodifiableList(new ArrayList<String>(lines));
        }
    }
}
