package com.lilahcraft.lumen.skill;

import com.lilahcraft.lumen.entity.BlockMatcher;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a sentence like "harvest hops: right click the ripe hops vines and collect
 * what drops" into a {@link LumenSkill}. Pure Java, unit tested.
 *
 * <p>The block the player is looking at is passed in when known, so "right click
 * these when they're ripe" can be grounded on a real block id instead of the words
 * "these". Lumen cannot see colour; it can read a blockstate.
 */
public final class SkillTeacher {

    private SkillTeacher() {
    }

    private static final Pattern NAME_SPLIT = Pattern.compile("^(.*?)\\s*(?::|=|-\\s|\\bmeans\\b|\\bis\\b)\\s*(.+)$");
    private static final Pattern BREAK_WORDS = Pattern.compile(
            "\\b(break|mine|punch|left[- ]?click|chop|dig|smash|destroy|cut)\\b");
    private static final Pattern INTERACT_WORDS = Pattern.compile(
            "\\b(right[- ]?click|use|interact|pick|harvest|press|activate|tap|click)\\b");
    private static final Pattern COLLECT_WORDS = Pattern.compile(
            "\\b(collect|gather|pick\\s+up|grab|bring|take)\\b");
    private static final Pattern NO_COLLECT = Pattern.compile("\\b(don'?t|do\\s+not|without|leave)\\b.{0,20}\\b(collect|pick|grab|bring)");
    private static final Pattern DEICTIC = Pattern.compile(
            "\\b(these|those|this|that|it|them|the\\s+ones?|the\\s+block\\s+i'?m\\s+looking\\s+at|what\\s+i'?m\\s+looking\\s+at)\\b");
    private static final Pattern RADIUS = Pattern.compile("\\bwithin\\s+(\\d{1,3})\\b|\\b(\\d{1,3})\\s+blocks?\\b");
    private static final Pattern TARGET_LEAD = Pattern.compile(
            "^(?:on|at|the|all|every|each|of|any|some)\\s+");

    /**
     * @param text          what the player said after "teach" / "learn"
     * @param lookedAtBlock the block id the player is looking at, e.g. "minecraft:sweet_berry_bush", or null
     * @param lookedAtRipe  whether that block currently reads as ripe
     * @return a skill, or null when no name and no target could be found
     */
    @Nullable
    public static LumenSkill parse(@Nullable String text, @Nullable String lookedAtBlock, boolean lookedAtRipe) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String lower = text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        String name;
        String how;
        Matcher split = NAME_SPLIT.matcher(lower);
        if (split.matches() && !split.group(1).isBlank()) {
            name = split.group(1).trim();
            how = split.group(2).trim();
        } else {
            name = "";
            how = lower;
        }
        // The action: the first verb family that appears wins; "harvest" alone means right-click.
        String action = LumenSkill.INTERACT;
        int breakAt = firstIndex(BREAK_WORDS, how);
        int interactAt = firstIndex(INTERACT_WORDS, how);
        if (breakAt >= 0 && (interactAt < 0 || breakAt < interactAt)) {
            action = LumenSkill.BREAK;
        }
        boolean collect = !NO_COLLECT.matcher(how).find();
        int radius = 12;
        Matcher r = RADIUS.matcher(how);
        if (r.find()) {
            String n = r.group(1) != null ? r.group(1) : r.group(2);
            radius = Math.max(2, Math.min(32, Integer.parseInt(n)));
        }
        // The target: what is left once verbs, collecting and the radius are removed.
        String targetText = how;
        targetText = BREAK_WORDS.matcher(targetText).replaceAll(" ");
        targetText = INTERACT_WORDS.matcher(targetText).replaceAll(" ");
        targetText = COLLECT_WORDS.matcher(targetText).replaceAll(" ");
        targetText = RADIUS.matcher(targetText).replaceAll(" ");
        targetText = targetText.replaceAll("\\b(and|then|when|whenever|once|they'?re|they\\s+are|it'?s|it\\s+is|what|whatever|drops?|falls?|out|off|up|to|me|so|you|can|should|need|we|want|please|just|go|them)\\b", " ");
        targetText = targetText.replaceAll("[^a-z0-9:_*=<>\\- ]", " ").replaceAll("\\s+", " ").trim();
        boolean deictic = DEICTIC.matcher(how).find();
        targetText = DEICTIC.matcher(targetText).replaceAll(" ").replaceAll("\\s+", " ").trim();
        targetText = TARGET_LEAD.matcher(targetText).replaceAll("").trim();

        BlockMatcher.Spec spec = BlockMatcher.parse(targetText);
        String target;
        if ((spec.pattern.isEmpty() || deictic) && lookedAtBlock != null && !lookedAtBlock.isBlank()) {
            // Grounded on the real block: "right click these when ripe" -> ripe minecraft:sweet_berry_bush
            boolean ripe = spec.ripe || lookedAtRipe;
            target = (ripe ? "ripe " : "") + lookedAtBlock + propsSuffix(spec);
        } else if (spec.pattern.isEmpty()) {
            return null;
        } else {
            target = spec.describe();
        }
        if (name.isEmpty()) {
            name = (action.equals(LumenSkill.BREAK) ? "break " : "harvest ")
                    + (spec.pattern.isEmpty() && lookedAtBlock != null ? pathWords(lookedAtBlock) : spec.pattern);
        }
        LumenSkill skill = new LumenSkill();
        skill.name = cleanName(name);
        skill.target = target;
        skill.action = action;
        skill.collect = collect;
        skill.radius = radius;
        skill.example = lookedAtBlock == null ? "" : lookedAtBlock;
        List<String> aliases = new ArrayList<>();
        String bare = skill.name.replaceFirst("^(harvest|break|pick|collect|gather|mine)\\s+", "");
        if (!bare.equals(skill.name) && !bare.isBlank()) {
            aliases.add(bare);
        }
        skill.aliases = aliases;
        return skill;
    }

    private static String propsSuffix(BlockMatcher.Spec spec) {
        StringBuilder out = new StringBuilder();
        spec.props.forEach((k, v) -> out.append(' ').append(k)
                .append(v.startsWith(">=") || v.startsWith("<=") ? v : "=" + v));
        return out.toString();
    }

    /** "minecraft:sweet_berry_bush" -> "sweet berry bush". */
    static String pathWords(String blockId) {
        String path = blockId.contains(":") ? blockId.substring(blockId.indexOf(':') + 1) : blockId;
        return path.replace('_', ' ');
    }

    /** "the Hops!" -> "hops". Same treatment as place names. */
    public static String cleanName(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9' ]", " ").replaceAll("\\s+", " ").trim();
        text = text.replaceFirst("^(the|a|an|my|our|to|how\\s+to)\\s+", "");
        return text;
    }

    private static int firstIndex(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.start() : -1;
    }
}
