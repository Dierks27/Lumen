package com.lilahcraft.lumen.entity;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Describes which blocks a skill applies to, as data a player can read back.
 *
 * <p>Pure Java: the matcher is parsed from words and evaluated against plain strings
 * and maps, so it is unit tested here without a game. The Minecraft side of it - how
 * to get those strings out of a BlockState - lives in {@link BlockStates}.
 *
 * <p>The grammar is small on purpose: some words naming the block, an optional
 * ripeness word, and optional {@code property=value} constraints.
 * <pre>
 *   ripe hops vine
 *   sweet berry bush age=3
 *   minecraft:cave_vines berries=true
 *   *tomato* age=max
 * </pre>
 */
public final class BlockMatcher {

    private BlockMatcher() {
    }

    /** Words that mean "fully grown" when a player says them. */
    private static final Pattern RIPE_WORDS =
            Pattern.compile("\\b(ripe|ripened|mature|matured|grown|fully\\s+grown|ready|harvestable|done)\\b");

    /** Filler that carries no meaning for matching. */
    private static final Pattern FILLER = Pattern.compile(
            "\\b(the|a|an|some|any|those|these|that|this|of|on|in|blocks?|ones?|plants?|crops?|stuff|it|them)\\b");

    /** Properties that carry growth, in the order checked. */
    public static final List<String> GROWTH_PROPERTIES = List.of("age", "stage", "growth", "maturity", "ripeness", "level");

    /** A parsed matcher. */
    public static final class Spec {
        /** Lowercase words naming the block, possibly with ':' for a full id or '*' globs. */
        public final String pattern;
        public final boolean ripe;
        /** property -> "3", "max", ">=2", "true". */
        public final Map<String, String> props;

        public Spec(String pattern, boolean ripe, Map<String, String> props) {
            this.pattern = pattern;
            this.ripe = ripe;
            this.props = props;
        }

        /** Readable form, and also re-parseable: what /lumen skill shows. */
        public String describe() {
            StringBuilder out = new StringBuilder();
            if (ripe) {
                out.append("ripe ");
            }
            out.append(pattern.isEmpty() ? "anything" : pattern);
            for (Map.Entry<String, String> e : props.entrySet()) {
                out.append(' ').append(e.getKey()).append(e.getValue().startsWith(">=") || e.getValue().startsWith("<=")
                        ? e.getValue() : "=" + e.getValue());
            }
            return out.toString().trim();
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    /** Parses "ripe sweet berry bush age>=2" and the like. Never throws; empty text gives a match-nothing spec. */
    public static Spec parse(@Nullable String text) {
        String lower = text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        boolean ripe = RIPE_WORDS.matcher(lower).find();
        lower = RIPE_WORDS.matcher(lower).replaceAll(" ");
        Map<String, String> props = new LinkedHashMap<>();
        List<String> words = new ArrayList<>();
        for (String token : lower.split(" ")) {
            if (token.isEmpty()) {
                continue;
            }
            int ge = token.indexOf(">=");
            int le = token.indexOf("<=");
            int eq = token.indexOf('=');
            if (ge > 0) {
                props.put(token.substring(0, ge), ">=" + token.substring(ge + 2));
            } else if (le > 0) {
                props.put(token.substring(0, le), "<=" + token.substring(le + 2));
            } else if (eq > 0) {
                props.put(token.substring(0, eq), token.substring(eq + 1));
            } else {
                words.add(token);
            }
        }
        String pattern = FILLER.matcher(String.join(" ", words)).replaceAll(" ")
                .replaceAll("[^a-z0-9:_*\\- ]", " ").replaceAll("\\s+", " ").trim();
        return new Spec(pattern, ripe, props);
    }

    /**
     * Whether the block named {@code blockId} (e.g. "minecraft:sweet_berry_bush") with
     * display name {@code displayName} answers to the pattern. Same tiers as items:
     * exact id or path, then every word, then substring or glob.
     */
    public static boolean nameMatches(String pattern, String blockId, @Nullable String displayName) {
        if (pattern == null || pattern.isEmpty()) {
            return true;
        }
        String id = blockId.toLowerCase(Locale.ROOT);
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        String name = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
        String p = pattern.trim();
        if (p.contains("*")) {
            String regex = "^" + Pattern.quote(p.replace(' ', '_')).replace("*", "\\E.*\\Q") + "$";
            return path.matches(regex) || id.matches(regex);
        }
        String underscored = p.replace(' ', '_');
        if (underscored.equals(id) || underscored.equals(path) || p.equals(name)) {
            return true;
        }
        if (ChestFinder.wordsMatch(underscored, path) || ChestFinder.wordsMatch(p, name)) {
            return true;
        }
        // Last resort: a partial word ("berr" for berry), never a word buried inside another
        // ("stone" must not claim cobblestone).
        for (String word : (path + " " + name).split("[^a-z0-9]+")) {
            if (!word.isEmpty() && word.startsWith(underscored)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fully grown? The generic test, mod-agnostic:
     * <ol>
     *   <li>If the block can say whether it still grows (vanilla {@code Fertilizable},
     *       which most farming mods implement for bone meal), "cannot grow further" means ripe.</li>
     *   <li>Otherwise an integer property named like age or stage at its maximum.</li>
     *   <li>A block with no growth signal at all is never "ripe".</li>
     * </ol>
     *
     * @param stillGrowing true/false from the block's own growth check, or null when it has none
     */
    public static boolean isRipe(Map<String, String> props, Map<String, Integer> intMax, @Nullable Boolean stillGrowing) {
        if (stillGrowing != null) {
            return !stillGrowing;
        }
        for (String key : GROWTH_PROPERTIES) {
            if (props.containsKey(key) && intMax.containsKey(key)) {
                return parseInt(props.get(key), -1) >= intMax.get(key);
            }
        }
        return false;
    }

    /** Whether a block described by these strings and maps satisfies the spec. */
    public static boolean matches(Spec spec, String blockId, @Nullable String displayName,
                                  Map<String, String> props, Map<String, Integer> intMax,
                                  @Nullable Boolean stillGrowing) {
        if (!nameMatches(spec.pattern, blockId, displayName)) {
            return false;
        }
        if (spec.ripe && !isRipe(props, intMax, stillGrowing)) {
            return false;
        }
        for (Map.Entry<String, String> constraint : spec.props.entrySet()) {
            String actual = props.get(constraint.getKey());
            if (actual == null) {
                return false;
            }
            String wanted = constraint.getValue();
            if (wanted.equals("max")) {
                Integer max = intMax.get(constraint.getKey());
                if (max == null || parseInt(actual, -1) < max) {
                    return false;
                }
            } else if (wanted.startsWith(">=")) {
                if (parseInt(actual, Integer.MIN_VALUE) < parseInt(wanted.substring(2), Integer.MAX_VALUE)) {
                    return false;
                }
            } else if (wanted.startsWith("<=")) {
                if (parseInt(actual, Integer.MAX_VALUE) > parseInt(wanted.substring(2), Integer.MIN_VALUE)) {
                    return false;
                }
            } else if (!actual.equalsIgnoreCase(wanted)) {
                return false;
            }
        }
        return true;
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
