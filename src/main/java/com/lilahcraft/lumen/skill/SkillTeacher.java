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
 * what drops" or "restock: take 16 wheat from the storage chest, then put it in this
 * barrel" into a {@link LumenSkill}. Pure Java, unit tested.
 *
 * <p>The lesson is split into clauses on "then", ";" and ", and" before a verb, and
 * each clause becomes one {@link SkillStep}. "These", "this chest" and "here" are
 * grounded on what the player was looking at when they taught it, so a skill stores
 * a real block id or a real position rather than the word "this".
 */
public final class SkillTeacher {

    private SkillTeacher() {
    }

    /** What the teacher was looking at, so deictic words can be grounded. */
    public record LookedAt(@Nullable String blockId, boolean ripe, @Nullable int[] pos, boolean container) {
        public static final LookedAt NOTHING = new LookedAt(null, false, null, false);

        public boolean hasBlock() {
            return blockId != null && !blockId.isBlank();
        }
    }

    private static final Pattern NAME_SPLIT = Pattern.compile("^(.*?)\\s*(?::|=|-\\s|\\bmeans\\b|\\bis\\b)\\s*(.+)$");

    private static final String VERBS = "put|store|stash|deposit|unload|dump|take|grab|get|fetch|pull|withdraw|"
            + "open|walk|go|head|run|move|come|return|equip|hold|wield|switch|wait|pause|say|tell|announce|"
            + "collect|gather|pick|break|mine|punch|left|chop|dig|smash|destroy|cut|clear|right|use|interact|"
            + "harvest|press|activate|tap|click|flip|toggle|turn";
    private static final Pattern CLAUSE = Pattern.compile(
            "\\s*(?:[,;]\\s*)?(?:and\\s+)?then\\s+|\\s*;\\s*|\\s*,\\s*(?:and\\s+)?(?=(?:" + VERBS + ")\\b)"
                    + "|\\s+and\\s+(?:also\\s+)?(?=(?:" + VERBS + ")\\b)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PUT = Pattern.compile(
            "^(?:put|store|stash|deposit|unload|dump|drop\\s+off|leave|place)\\s+(?:back\\s+)?(.+?)\\s+"
                    + "(?:in|into|inside|in\\s+to|back\\s+in|back\\s+into)\\s+(.+)$");
    private static final Pattern TAKE = Pattern.compile(
            "^(?:take|grab|get|fetch|pull|withdraw|remove|collect)\\s+(.+?)\\s+(?:out\\s+of|from|out)\\s+(.+)$");
    private static final Pattern OPEN = Pattern.compile("^(?:open|check|look\\s+in)\\s+(.+)$");
    private static final Pattern WALK = Pattern.compile(
            "^(?:walk|go|head|run|move|come)(?:\\s+over|\\s+back)?(?:\\s+to|\\s+towards|\\s+into|\\s+over\\s+to)?\\s+(.+)$");
    private static final Pattern RETURN = Pattern.compile(
            "^(?:return|come\\s+back|go\\s+back|head\\s+back|walk\\s+back)(?:\\s+to\\s+me)?$|^(?:bring|carry)\\s+(?:it|them|everything|the\\s+drops|the\\s+loot)?\\s*(?:back\\s+)?(?:to\\s+me)?$");
    private static final Pattern EQUIP = Pattern.compile(
            "^(?:equip|hold|wield|switch\\s+to|take\\s+out|put\\s+on|get\\s+out)\\s+(?:the\\s+|a\\s+|an\\s+|my\\s+|your\\s+)?(.+)$");
    private static final Pattern WAIT = Pattern.compile(
            "^(?:wait|pause|sleep|rest|stand\\s+there|stay)(?:\\s+for)?\\s+(\\d{1,4})\\s*(seconds?|secs?|s|ticks?|minutes?|mins?|m)?\\b.*$");
    private static final Pattern SAY = Pattern.compile("^(?:say|tell\\s+(?:me|everyone|them|us)|announce|shout)\\s*[:\"']?\\s*(.+?)[\"']?$");
    private static final Pattern COLLECT = Pattern.compile(
            "^(?:collect|gather|pick\\s+up|grab|get)(?:\\s+(?:up|all|the|what|whatever|any|everything|them|it|drops|falls|dropped|drop|items|loot|stuff|that|which|of|comes|out|off|down))*\\s*$");
    private static final Pattern NO_COLLECT = Pattern.compile("\\b(don'?t|do\\s+not|without|leave)\\b.{0,20}\\b(collect|pick|grab|bring)");

    private static final Pattern BREAK_WORDS = Pattern.compile(
            "\\b(break|mine|punch|left[- ]?click|chop|dig|smash|destroy|cut|clear)\\b");
    private static final Pattern INTERACT_WORDS = Pattern.compile(
            "\\b(right[- ]?click|use|interact|pick|harvest|press|activate|tap|click|flip|toggle|turn\\s+on|turn\\s+off|pull)\\b");
    private static final Pattern COLLECT_WORDS = Pattern.compile(
            "\\b(collect|gather|pick\\s+up|grab|bring|take)\\b");
    private static final Pattern DEICTIC = Pattern.compile(
            "\\b(these|those|this|that|it|them|here|the\\s+ones?|the\\s+block\\s+i'?m\\s+looking\\s+at|what\\s+i'?m\\s+looking\\s+at)\\b");
    private static final Pattern DEICTIC_CONTAINER = Pattern.compile(
            "^(?:this|that|the|it|here)(?:\\s+(?:one|chest|barrel|box|crate|drawer|container|storage|thing|block))?$"
                    + "|^(?:the\\s+)?(?:chest|barrel|box|crate|drawer|container)\\s+(?:i'?m\\s+)?(?:looking\\s+at|in\\s+front\\s+of\\s+me|here)$");
    private static final Pattern NEAREST_CONTAINER = Pattern.compile(
            "^(?:the\\s+)?(?:nearest|closest|any|a|some)\\s+(?:chest|barrel|box|crate|drawer|container|storage)$");
    private static final Pattern CONTAINER_WITH = Pattern.compile(
            "^(?:the\\s+|a\\s+)?(?:chest|barrel|box|crate|drawer|container|storage)\\s+(?:with|holding|containing|that\\s+has|full\\s+of)\\s+(?:the\\s+)?(.+)$");
    private static final Pattern RADIUS = Pattern.compile("\\bwithin\\s+(\\d{1,3})\\b|\\b(\\d{1,3})\\s+blocks?\\b");
    private static final Pattern COUNT = Pattern.compile("\\b(\\d{1,4})\\b");
    private static final Pattern TARGET_LEAD = Pattern.compile("^(?:on|at|the|all|every|each|of|any|some|a|an)\\s+");
    private static final Pattern ITEM_LEAD = Pattern.compile(
            "^(?:all\\s+(?:of\\s+)?(?:the\\s+|my\\s+)?|the\\s+|some\\s+|any\\s+|my\\s+|a\\s+|an\\s+)+");

    /** The v0.8.0 entry point, kept for callers and tests that only know the block. */
    @Nullable
    public static LumenSkill parse(@Nullable String text, @Nullable String lookedAtBlock, boolean lookedAtRipe) {
        return parse(text, new LookedAt(lookedAtBlock, lookedAtRipe, null, false));
    }

    /**
     * @param text what the player said after "teach" / "learn"
     * @param seen what they were looking at, for grounding "these" and "this chest"
     * @return a skill, or null when no step could be made of it
     */
    @Nullable
    public static LumenSkill parse(@Nullable String text, @Nullable LookedAt seen) {
        if (text == null || text.isBlank()) {
            return null;
        }
        LookedAt looked = seen == null ? LookedAt.NOTHING : seen;
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
        int radius = 12;
        Matcher r = RADIUS.matcher(how);
        if (r.find()) {
            String n = r.group(1) != null ? r.group(1) : r.group(2);
            radius = Math.max(2, Math.min(32, Integer.parseInt(n)));
        }
        boolean noCollect = NO_COLLECT.matcher(how).find();
        List<SkillStep> steps = new ArrayList<>();
        for (String clause : CLAUSE.split(how)) {
            String c = clause.trim().replaceAll("^[,;.]+|[,;.!]+$", "").trim();
            if (c.isEmpty()) {
                continue;
            }
            SkillStep step = parseClause(c, looked);
            if (step != null) {
                if (SkillStep.COLLECT.equals(step.kind) && noCollect) {
                    continue;
                }
                steps.add(step);
            }
        }
        // "right click these when ripe and collect what drops": the collect clause is
        // folded into the click by the old parser; keep that when it appears alone.
        if (steps.isEmpty()) {
            return null;
        }
        LumenSkill skill = new LumenSkill();
        skill.steps = steps;
        skill.radius = radius;
        skill.example = looked.hasBlock() ? looked.blockId() : "";
        if (name.isEmpty()) {
            name = nameFor(steps, looked);
        }
        skill.name = cleanName(name);
        if (skill.name.isEmpty()) {
            return null;
        }
        List<String> aliases = new ArrayList<>();
        String bare = skill.name.replaceFirst("^(harvest|break|pick|collect|gather|mine|clear|restock|fill|empty|store|fetch)\\s+", "");
        if (!bare.equals(skill.name) && !bare.isBlank()) {
            aliases.add(bare);
        }
        skill.aliases = aliases;
        return skill;
    }

    /** One clause to one step, or null when it is only chatter. Also used for a direct "put" command. */
    @Nullable
    public static SkillStep parseClause(String clause, @Nullable LookedAt looked) {
        if (looked == null) {
            looked = LookedAt.NOTHING;
        }
        String c = clause.trim();
        Matcher m;
        if (RETURN.matcher(c).matches()) {
            return new SkillStep(SkillStep.RETURN, "", "", 0);
        }
        if ((m = WAIT.matcher(c)).matches()) {
            int n = Integer.parseInt(m.group(1));
            String unit = m.group(2) == null ? "s" : m.group(2);
            int seconds = unit.startsWith("t") ? Math.max(1, n / 20) : unit.startsWith("m") ? n * 60 : n;
            return new SkillStep(SkillStep.WAIT, "", "", Math.max(1, Math.min(600, seconds)));
        }
        if ((m = SAY.matcher(c)).matches()) {
            return new SkillStep(SkillStep.SAY, "", m.group(1).trim(), 0);
        }
        if ((m = PUT.matcher(c)).matches()) {
            SkillStep step = new SkillStep(SkillStep.PUT, "", "", 0);
            fillItem(step, m.group(1), true);
            fillContainer(step, m.group(2), looked);
            return step;
        }
        if ((m = TAKE.matcher(c)).matches()) {
            SkillStep step = new SkillStep(SkillStep.TAKE, "", "", 0);
            fillItem(step, m.group(1), false);
            fillContainer(step, m.group(2), looked);
            return step;
        }
        if (COLLECT.matcher(c).matches()) {
            return SkillStep.collect();
        }
        if ((m = EQUIP.matcher(c)).matches()) {
            return new SkillStep(SkillStep.EQUIP, "", m.group(1).trim(), 0);
        }
        if ((m = OPEN.matcher(c)).matches()) {
            SkillStep step = new SkillStep(SkillStep.WALK_TO, "", "", 0);
            fillContainer(step, m.group(1), looked);
            return step;
        }
        if ((m = WALK.matcher(c)).matches()) {
            String where = m.group(1).trim().replaceFirst("^(?:the|my|our)\\s+", "");
            SkillStep step = new SkillStep(SkillStep.WALK_TO, where, "", 0);
            if (DEICTIC_CONTAINER.matcher(where).matches() || where.equals("here") || where.equals("this spot")) {
                if (looked.pos() != null) {
                    step.pos = looked.pos().clone();
                    step.target = "";
                } else {
                    return null;
                }
            }
            return step;
        }
        return parseBlockAction(c, looked);
    }

    /** "right click the ripe hops vines", "break 10 cobwebs", "flip the lever". */
    @Nullable
    private static SkillStep parseBlockAction(String c, LookedAt looked) {
        String action = SkillStep.RIGHT_CLICK;
        int breakAt = firstIndex(BREAK_WORDS, c);
        int interactAt = firstIndex(INTERACT_WORDS, c);
        if (breakAt < 0 && interactAt < 0 && !DEICTIC.matcher(c).find()) {
            // No verb and nothing pointed at: chatter, not a step.
            return null;
        }
        if (breakAt >= 0 && (interactAt < 0 || breakAt < interactAt)) {
            action = SkillStep.BREAK;
        }
        int count = 0;
        String targetText = c;
        Matcher n = COUNT.matcher(RADIUS.matcher(targetText).replaceAll(" "));
        if (n.find()) {
            count = Math.max(0, Math.min(999, Integer.parseInt(n.group(1))));
        }
        targetText = BREAK_WORDS.matcher(targetText).replaceAll(" ");
        targetText = INTERACT_WORDS.matcher(targetText).replaceAll(" ");
        targetText = COLLECT_WORDS.matcher(targetText).replaceAll(" ");
        targetText = RADIUS.matcher(targetText).replaceAll(" ");
        targetText = COUNT.matcher(targetText).replaceAll(" ");
        targetText = targetText.replaceAll("\\b(and|then|when|whenever|once|they'?re|they\\s+are|it'?s|it\\s+is|what|whatever|drops?|falls?|out|off|up|to|me|so|you|can|should|need|we|want|please|just|go|them|on|only|if|are|is)\\b", " ");
        targetText = targetText.replaceAll("[^a-z0-9:_*=<>\\- ]", " ").replaceAll("\\s+", " ").trim();
        boolean deictic = DEICTIC.matcher(c).find();
        targetText = DEICTIC.matcher(targetText).replaceAll(" ").replaceAll("\\s+", " ").trim();
        targetText = TARGET_LEAD.matcher(targetText).replaceAll("").trim();

        BlockMatcher.Spec spec = BlockMatcher.parse(targetText);
        String target;
        if ((spec.pattern.isEmpty() || deictic) && looked.hasBlock()) {
            // Grounded on the real block: "right click these when ripe" -> ripe minecraft:sweet_berry_bush
            boolean ripe = spec.ripe || looked.ripe();
            target = (ripe ? "ripe " : "") + looked.blockId() + propsSuffix(spec);
        } else if (spec.pattern.isEmpty()) {
            return null;
        } else {
            target = spec.describe();
        }
        return new SkillStep(action, target, "", count);
    }

    /** "16 wheat" -> item "wheat", count 16; "everything" / "it all" -> empty item (put) or "*" (take). */
    private static void fillItem(SkillStep step, String words, boolean put) {
        String w = words.trim();
        Matcher n = COUNT.matcher(w);
        if (n.find()) {
            step.count = Math.max(1, Math.min(9999, Integer.parseInt(n.group(1))));
            w = COUNT.matcher(w).replaceAll(" ");
        }
        w = w.replaceAll("\\b(x|pieces?|items?|of|stacks?)\\b", " ").replaceAll("\\s+", " ").trim();
        w = ITEM_LEAD.matcher(w).replaceAll("").trim();
        if (w.matches("everything|it\\s+all|all|the\\s+lot|what\\s+i\\s+have|what\\s+i'?m\\s+carrying|my\\s+stuff|"
                + "it|them|that|those|these|the\\s+drops|the\\s+loot|the\\s+haul|what\\s+i\\s+collected|what\\s+i\\s+got")) {
            step.item = put ? "" : "*";
            return;
        }
        step.item = w;
    }

    /** "this chest" -> the looked-at position; "the tool chest" -> a name; "a chest with iron" -> with iron. */
    private static void fillContainer(SkillStep step, String words, LookedAt looked) {
        String w = words.trim().replaceAll("[.!]+$", "");
        Matcher with = CONTAINER_WITH.matcher(w);
        if (DEICTIC_CONTAINER.matcher(w).matches()) {
            if (looked.pos() != null && looked.container()) {
                step.pos = looked.pos().clone();
                step.target = "";
            } else {
                step.target = SkillStep.NEAREST;
            }
        } else if (NEAREST_CONTAINER.matcher(w).matches()) {
            step.target = SkillStep.NEAREST;
        } else if (with.matches()) {
            step.target = SkillStep.WITH + with.group(1).trim();
        } else {
            step.target = w.replaceFirst("^(?:the|my|our|your)\\s+", "").trim();
        }
    }

    private static String nameFor(List<SkillStep> steps, LookedAt looked) {
        for (SkillStep step : steps) {
            if (step.isBlockAction()) {
                String what = step.target.replaceFirst("^ripe\\s+", "").replaceAll("\\s+\\w+[=<>].*$", "");
                if (what.contains(":")) {
                    what = pathWords(what);
                }
                return (SkillStep.BREAK.equals(step.kind) ? "break " : "harvest ") + what;
            }
        }
        for (SkillStep step : steps) {
            if (SkillStep.PUT.equals(step.kind)) {
                return "store " + (step.item.isEmpty() ? "everything" : step.item);
            }
            if (SkillStep.TAKE.equals(step.kind)) {
                return "fetch " + ("*".equals(step.item) ? "everything" : step.item);
            }
        }
        return steps.get(0).describe();
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
