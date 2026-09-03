package com.lilahcraft.lumen.skill;

import org.jetbrains.annotations.Nullable;

/**
 * One primitive action in a taught skill. A mutable bean so Gson reads and writes it
 * without adapters, and so a server owner can fix one by hand in skills.json.
 *
 * <p>The primitives are the things a player does with their hands: walk somewhere,
 * right-click a block, break a block, take from or put into a container, hold a tool,
 * wait, say something, pick up drops, come back. Everything Lumen has been asked to
 * learn so far is a short sequence of those.
 */
public final class SkillStep {

    public static final String WALK_TO = "walk_to";
    public static final String RIGHT_CLICK = "right_click";
    public static final String BREAK = "break";
    public static final String TAKE = "take";
    public static final String PUT = "put";
    public static final String EQUIP = "equip";
    public static final String WAIT = "wait";
    public static final String SAY = "say";
    public static final String COLLECT = "collect";
    public static final String RETURN = "return";

    /** A container reference meaning "whichever is closest". */
    public static final String NEAREST = "nearest";
    /** A container reference prefix meaning "one that holds these items": "with iron". */
    public static final String WITH = "with ";

    public String kind = RIGHT_CLICK;

    /**
     * What the step acts on. For clicks and breaks a {@code BlockMatcher} spec ("ripe
     * hops vine", "lever", "cobweb"). For walk_to a place name. For take and put a
     * container reference: a place name, {@link #NEAREST}, or {@link #WITH} + item words.
     * Empty when {@link #pos} says exactly where.
     */
    public String target = "";

    /** Item words for take, put and equip ("wheat", "iron ingot"); the line to say for say. */
    public String item = "";

    /**
     * For take and put: how many items (0 = everything for put, the default amount for
     * take). For clicks and breaks: how many blocks (0 = all in range). For wait: seconds.
     */
    public int count;

    /** A fixed spot learned from what the player was looking at while teaching, or null. */
    @Nullable
    public int[] pos;

    public SkillStep() {
    }

    public SkillStep(String kind, String target, String item, int count) {
        this.kind = kind;
        this.target = target == null ? "" : target;
        this.item = item == null ? "" : item;
        this.count = count;
    }

    public static SkillStep click(String target, int count) {
        return new SkillStep(RIGHT_CLICK, target, "", count);
    }

    public static SkillStep breakBlocks(String target, int count) {
        return new SkillStep(BREAK, target, "", count);
    }

    public static SkillStep collect() {
        return new SkillStep(COLLECT, "", "", 0);
    }

    public boolean isBlockAction() {
        return RIGHT_CLICK.equals(kind) || BREAK.equals(kind);
    }

    public boolean hasPos() {
        return pos != null && pos.length == 3;
    }

    /** One short phrase, for /lumen skills and the prompt. */
    public String describe() {
        String k = kind == null ? "" : kind;
        switch (k) {
            case WALK_TO:
                return "walk to " + (hasPos() ? posText() : "the " + target);
            case RIGHT_CLICK:
                return "right-click " + target + (count > 0 ? " (" + count + ")" : "");
            case BREAK:
                return "break " + target + (count > 0 ? " (" + count + ")" : "");
            case TAKE:
                return "take " + (count > 0 ? count + " " : "") + (item.isEmpty() ? "things" : item)
                        + " from " + containerText();
            case PUT:
                return "put " + (count > 0 ? count + " " : "") + (item.isEmpty() ? "everything" : item)
                        + " into " + containerText();
            case EQUIP:
                return "hold " + (item.isEmpty() ? "a tool" : item);
            case WAIT:
                return "wait " + Math.max(1, count) + "s";
            case SAY:
                return "say \"" + item + "\"";
            case COLLECT:
                return "pick up the drops";
            case RETURN:
                return "come back";
            default:
                return k + " " + target;
        }
    }

    /** "the container at 10, 64, -3", "the nearest container", "a container with iron", "the tool chest". */
    public String containerText() {
        if (hasPos()) {
            return "the container at " + posText();
        }
        if (target == null || target.isEmpty() || NEAREST.equals(target)) {
            return "the nearest container";
        }
        if (target.startsWith(WITH)) {
            return "a container with " + target.substring(WITH.length());
        }
        return "the " + target;
    }

    private String posText() {
        return pos[0] + ", " + pos[1] + ", " + pos[2];
    }
}
