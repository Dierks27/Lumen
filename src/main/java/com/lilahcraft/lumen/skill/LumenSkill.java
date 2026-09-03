package com.lilahcraft.lumen.skill;

import java.util.ArrayList;
import java.util.List;

/**
 * Something a player taught Lumen to do. A mutable bean rather than a record so Gson
 * writes and reads it without adapters.
 *
 * <p>A skill is a target and an action: "the ripe hops vines" and "right-click them".
 * Every farming, harvesting and machine-operating job a player has described so far
 * fits that shape, and it can be executed deterministically without asking the model
 * anything, which is what makes it reliable with a small local LLM.
 */
public final class LumenSkill {

    public static final String INTERACT = "interact";
    public static final String BREAK = "break";

    /** The name it was taught under: "harvest hops". */
    public String name = "";
    /** Other ways the player refers to it: "hops", "pick the hops". */
    public List<String> aliases = new ArrayList<>();
    /** A {@code BlockMatcher} spec: "ripe hops vine", "sweet berry bush age=3". */
    public String target = "";
    /** {@link #INTERACT} (right-click) or {@link #BREAK} (mine it). */
    public String action = INTERACT;
    /** How far from where Lumen starts to look for targets. */
    public int radius = 12;
    /** Pick up what drops and bring it back. */
    public boolean collect = true;
    /** The exact block the player was looking at when teaching, for the record. */
    public String example = "";
    public String taughtBy = "";
    public long created;
    public int uses;

    public boolean isInteract() {
        return !BREAK.equalsIgnoreCase(action);
    }

    /** One line for /lumen skills and the prompt. */
    public String describe() {
        return name + ": " + (isInteract() ? "right-click " : "break ") + target
                + (collect ? ", collect the drops" : "") + " within " + radius + " blocks";
    }
}
