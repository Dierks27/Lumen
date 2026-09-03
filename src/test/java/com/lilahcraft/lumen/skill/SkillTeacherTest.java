package com.lilahcraft.lumen.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sentences players use to teach a job, and the steps they should become. */
class SkillTeacherTest {

    private static final int[] CHEST = {10, 64, -3};
    private static final SkillTeacher.LookedAt AT_CHEST = new SkillTeacher.LookedAt("minecraft:chest", false, CHEST, true);
    private static final SkillTeacher.LookedAt AT_BUSH = new SkillTeacher.LookedAt("minecraft:sweet_berry_bush", true,
            new int[] {1, 2, 3}, false);

    @Test
    @DisplayName("a named right-click harvest with a collect step")
    void parsesHopsLesson() {
        LumenSkill skill = SkillTeacher.parse(
                "harvest hops: right click the ripe hops vines and collect what drops", null, false);
        assertNotNull(skill);
        assertEquals("harvest hops", skill.name);
        List<SkillStep> steps = skill.steps();
        assertEquals(2, steps.size(), skill.describe());
        assertEquals(SkillStep.RIGHT_CLICK, steps.get(0).kind);
        assertTrue(steps.get(0).target.startsWith("ripe "), steps.get(0).target);
        assertTrue(steps.get(0).target.contains("hops"), steps.get(0).target);
        assertEquals(SkillStep.COLLECT, steps.get(1).kind);
        assertTrue(skill.isInteract());
        assertEquals(12, skill.radius);
        assertTrue(skill.aliases.contains("hops"), skill.aliases.toString());
    }

    @Test
    @DisplayName("\"these\" is grounded on the block the player is looking at")
    void groundsDeicticOnLookedAtBlock() {
        LumenSkill skill = SkillTeacher.parse("pick berries: right click these when they're ripe", AT_BUSH);
        assertNotNull(skill);
        assertEquals("pick berries", skill.name);
        assertEquals("ripe minecraft:sweet_berry_bush", skill.steps().get(0).target);
        assertEquals("minecraft:sweet_berry_bush", skill.example);
        assertTrue(skill.aliases.contains("berries"));
    }

    @Test
    @DisplayName("break verbs, a radius, a count and no collecting")
    void parsesBreakLesson() {
        LumenSkill skill = SkillTeacher.parse("clear vines - break the vine blocks within 20", null, false);
        assertNotNull(skill);
        assertEquals("clear vines", skill.name);
        SkillStep first = skill.steps().get(0);
        assertEquals(SkillStep.BREAK, first.kind);
        assertFalse(skill.isInteract());
        assertTrue(first.target.contains("vine"), first.target);
        assertEquals(20, skill.radius);

        LumenSkill leave = SkillTeacher.parse("prune: break the leaves but don't collect anything", null, false);
        assertNotNull(leave);
        assertEquals(1, leave.steps().size(), leave.describe());

        LumenSkill counted = SkillTeacher.parse("cobwebs: break 10 cobwebs", null, false);
        assertNotNull(counted);
        assertEquals(10, counted.steps().get(0).count);
        assertTrue(counted.steps().get(0).target.contains("cobweb"), counted.steps().get(0).target);
    }

    @Test
    @DisplayName("any block can be right-clicked: a lever needs no ripeness")
    void clicksAnyBlock() {
        LumenSkill skill = SkillTeacher.parse("start the mill: flip the lever", null, false);
        assertNotNull(skill);
        SkillStep step = skill.steps().get(0);
        assertEquals(SkillStep.RIGHT_CLICK, step.kind);
        assertEquals("lever", step.target);

        LumenSkill grounded = SkillTeacher.parse("turn it on: right click this",
                new SkillTeacher.LookedAt("create:hand_crank", false, new int[] {0, 0, 0}, false));
        assertNotNull(grounded);
        assertEquals("create:hand_crank", grounded.steps().get(0).target);
    }

    @Test
    @DisplayName("container steps: take from and put into, grounded on the chest in view")
    void parsesContainerSteps() {
        LumenSkill skill = SkillTeacher.parse(
                "restock: take 16 wheat from the storage chest, then put it in this barrel", AT_CHEST);
        assertNotNull(skill);
        assertEquals("restock", skill.name);
        List<SkillStep> steps = skill.steps();
        assertEquals(2, steps.size(), skill.describe());
        SkillStep take = steps.get(0);
        assertEquals(SkillStep.TAKE, take.kind);
        assertEquals("wheat", take.item);
        assertEquals(16, take.count);
        assertEquals("storage chest", take.target);
        assertNull(take.pos);
        SkillStep put = steps.get(1);
        assertEquals(SkillStep.PUT, put.kind);
        assertEquals("", put.item); // "it" - whatever was taken
        assertArrayEquals(CHEST, put.pos);

        LumenSkill iron = SkillTeacher.parse("put iron away: put the iron in this chest", AT_CHEST);
        assertNotNull(iron);
        assertEquals("iron", iron.steps().get(0).item);
        assertArrayEquals(CHEST, iron.steps().get(0).pos);

        LumenSkill nearest = SkillTeacher.parse("dump: put everything in the nearest chest", null, false);
        assertNotNull(nearest);
        assertEquals(SkillStep.NEAREST, nearest.steps().get(0).target);
        assertEquals("", nearest.steps().get(0).item);

        LumenSkill with = SkillTeacher.parse("tools: grab a pickaxe from the chest with tools", null, false);
        assertNotNull(with);
        assertEquals(SkillStep.TAKE, with.steps().get(0).kind);
        assertEquals("pickaxe", with.steps().get(0).item);
        assertEquals(SkillStep.WITH + "tools", with.steps().get(0).target);
    }

    @Test
    @DisplayName("walk, hold, wait, say and come back")
    void parsesOtherPrimitives() {
        LumenSkill skill = SkillTeacher.parse(
                "night shift: go to the tower, then hold the sword, then wait 5 seconds, then say all clear, then come back",
                null, false);
        assertNotNull(skill);
        List<SkillStep> steps = skill.steps();
        assertEquals(5, steps.size(), skill.describe());
        assertEquals(SkillStep.WALK_TO, steps.get(0).kind);
        assertEquals("tower", steps.get(0).target);
        assertEquals(SkillStep.EQUIP, steps.get(1).kind);
        assertEquals("sword", steps.get(1).item);
        assertEquals(SkillStep.WAIT, steps.get(2).kind);
        assertEquals(5, steps.get(2).count);
        assertEquals(SkillStep.SAY, steps.get(3).kind);
        assertEquals("all clear", steps.get(3).item);
        assertEquals(SkillStep.RETURN, steps.get(4).kind);
    }

    @Test
    @DisplayName("a lesson that mixes a harvest with a chest run")
    void parsesMixedLesson() {
        LumenSkill skill = SkillTeacher.parse(
                "brew run: harvest the ripe hops, then collect the drops, then put the hops in the brewery barrel",
                null, false);
        assertNotNull(skill);
        List<SkillStep> steps = skill.steps();
        assertEquals(3, steps.size(), skill.describe());
        assertEquals(SkillStep.RIGHT_CLICK, steps.get(0).kind);
        assertEquals(SkillStep.COLLECT, steps.get(1).kind);
        assertEquals(SkillStep.PUT, steps.get(2).kind);
        assertEquals("hops", steps.get(2).item);
        assertEquals("brewery barrel", steps.get(2).target);
    }

    @Test
    @DisplayName("an unnamed lesson is named after its first step")
    void namesUnnamedLesson() {
        LumenSkill skill = SkillTeacher.parse("harvest cave vines", null, false);
        assertNotNull(skill);
        assertTrue(skill.name.startsWith("harvest cave"), skill.name);

        LumenSkill grounded = SkillTeacher.parse("right click these", "minecraft:cave_vines_plant", false);
        assertNotNull(grounded);
        assertEquals("harvest cave vines plant", grounded.name);
        assertEquals("minecraft:cave_vines_plant", grounded.steps().get(0).target);

        LumenSkill store = SkillTeacher.parse("put the wheat in the nearest chest", null, false);
        assertNotNull(store);
        assertEquals("store wheat", store.name);
    }

    @Test
    @DisplayName("nothing to learn gives null")
    void rejectsEmptyLessons() {
        assertNull(SkillTeacher.parse(null, null, false));
        assertNull(SkillTeacher.parse("   ", null, false));
        assertNull(SkillTeacher.parse("right click these", null, false));
        assertNull(SkillTeacher.parse("nice weather today", null, false));
    }

    @Test
    @DisplayName("a v0.8.0 skill file migrates into steps")
    void migratesLegacySkill() {
        LumenSkill old = new LumenSkill();
        old.name = "harvest hops";
        old.target = "ripe hops vine";
        old.action = LumenSkill.INTERACT;
        old.collect = true;
        List<SkillStep> steps = old.steps();
        assertEquals(2, steps.size());
        assertEquals(SkillStep.RIGHT_CLICK, steps.get(0).kind);
        assertEquals("ripe hops vine", steps.get(0).target);
        assertEquals(SkillStep.COLLECT, steps.get(1).kind);
        assertEquals("", old.target);
        assertTrue(old.describe().startsWith("harvest hops: right-click ripe hops vine, then pick up the drops"), old.describe());
    }

    @Test
    @DisplayName("names are cleaned like place names")
    void cleansNames() {
        assertEquals("hops", SkillTeacher.cleanName("the Hops!"));
        assertEquals("harvest", SkillTeacher.cleanName("How to Harvest"));
        assertEquals("", SkillTeacher.cleanName(null));
        assertEquals("sweet berry bush", SkillTeacher.pathWords("minecraft:sweet_berry_bush"));
    }
}
