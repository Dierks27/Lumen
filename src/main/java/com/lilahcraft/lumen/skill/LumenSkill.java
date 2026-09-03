package com.lilahcraft.lumen.skill;

import java.util.ArrayList;
import java.util.List;

/**
 * Something a player taught Lumen to do: a name and a short list of {@link SkillStep}s.
 * A mutable bean rather than a record so Gson writes and reads it without adapters.
 *
 * <p>Steps run in order and can be executed deterministically without asking the
 * model anything, which is what makes a skill reliable with a small local LLM.
 */
public final class LumenSkill {

    public static final String INTERACT = "interact";
    public static final String BREAK = "break";

    /** The name it was taught under: "harvest hops". */
    public String name = "";
    /** Other ways the player refers to it: "hops", "pick the hops". */
    public List<String> aliases = new ArrayList<>();
    /** What to do, in order. */
    public List<SkillStep> steps = new ArrayList<>();
    /** How far from where Lumen starts to look for blocks a step names. */
    public int radius = 12;
    /** The exact block the player was looking at when teaching, for the record. */
    public String example = "";
    public String taughtBy = "";
    public long created;
    public int uses;

    // v0.8.0 shape, kept so an old skills.json still loads. migrate() turns these into steps.
    public String target = "";
    public String action = INTERACT;
    public boolean collect = true;

    /** Steps, building them from the v0.8.0 fields when the file predates steps. */
    public List<SkillStep> steps() {
        migrate();
        return steps;
    }

    /** Turns a v0.8.0 target/action/collect skill into steps. Harmless on a new one. */
    public void migrate() {
        if (steps == null) {
            steps = new ArrayList<>();
        }
        if (!steps.isEmpty() || target == null || target.isEmpty()) {
            return;
        }
        steps.add(BREAK.equalsIgnoreCase(action) ? SkillStep.breakBlocks(target, 0) : SkillStep.click(target, 0));
        if (collect) {
            steps.add(SkillStep.collect());
        }
        target = "";
    }

    /** The first click or break step, which is what a count ("harvest 10 hops") applies to. */
    public SkillStep firstBlockStep() {
        for (SkillStep step : steps()) {
            if (step.isBlockAction()) {
                return step;
            }
        }
        return null;
    }

    /** True when the skill is one right-click job, the v0.8.0 shape. */
    public boolean isInteract() {
        SkillStep first = firstBlockStep();
        return first == null || SkillStep.RIGHT_CLICK.equals(first.kind);
    }

    /** One line for /lumen skills and the prompt. */
    public String describe() {
        List<String> parts = new ArrayList<>();
        for (SkillStep step : steps()) {
            parts.add(step.describe());
        }
        return name + ": " + String.join(", then ", parts) + " (within " + radius + " blocks)";
    }
}
