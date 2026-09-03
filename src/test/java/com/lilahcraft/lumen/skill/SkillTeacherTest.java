package com.lilahcraft.lumen.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sentences players use to teach a job, and the skill they should become. */
class SkillTeacherTest {

    @Test
    @DisplayName("a named right-click harvest")
    void parsesHopsLesson() {
        LumenSkill skill = SkillTeacher.parse(
                "harvest hops: right click the ripe hops vines and collect what drops", null, false);
        assertNotNull(skill);
        assertEquals("harvest hops", skill.name);
        assertEquals(LumenSkill.INTERACT, skill.action);
        assertTrue(skill.isInteract());
        assertTrue(skill.target.startsWith("ripe "), skill.target);
        assertTrue(skill.target.contains("hops"), skill.target);
        assertTrue(skill.collect);
        assertEquals(12, skill.radius);
        assertTrue(skill.aliases.contains("hops"), skill.aliases.toString());
    }

    @Test
    @DisplayName("\"these\" is grounded on the block the player is looking at")
    void groundsDeicticOnLookedAtBlock() {
        LumenSkill skill = SkillTeacher.parse(
                "pick berries: right click these when they're ripe", "minecraft:sweet_berry_bush", true);
        assertNotNull(skill);
        assertEquals("pick berries", skill.name);
        assertEquals("ripe minecraft:sweet_berry_bush", skill.target);
        assertEquals("minecraft:sweet_berry_bush", skill.example);
        assertTrue(skill.aliases.contains("berries"));
    }

    @Test
    @DisplayName("break verbs, a radius and no collecting")
    void parsesBreakLesson() {
        LumenSkill skill = SkillTeacher.parse("clear vines - break the vine blocks within 20", null, false);
        assertNotNull(skill);
        assertEquals("clear vines", skill.name);
        assertEquals(LumenSkill.BREAK, skill.action);
        assertFalse(skill.isInteract());
        assertTrue(skill.target.contains("vine"), skill.target);
        assertEquals(20, skill.radius);

        LumenSkill leave = SkillTeacher.parse("prune: break the leaves but don't collect anything", null, false);
        assertNotNull(leave);
        assertFalse(leave.collect);
    }

    @Test
    @DisplayName("an unnamed lesson is named after the target")
    void namesUnnamedLesson() {
        LumenSkill skill = SkillTeacher.parse("harvest cave vines", null, false);
        assertNotNull(skill);
        assertTrue(skill.name.startsWith("harvest cave"), skill.name);
        assertEquals(LumenSkill.INTERACT, skill.action);

        LumenSkill grounded = SkillTeacher.parse("right click these", "minecraft:cave_vines_plant", false);
        assertNotNull(grounded);
        assertEquals("harvest cave vines plant", grounded.name);
        assertEquals("minecraft:cave_vines_plant", grounded.target);
    }

    @Test
    @DisplayName("nothing to learn gives null")
    void rejectsEmptyLessons() {
        assertNull(SkillTeacher.parse(null, null, false));
        assertNull(SkillTeacher.parse("   ", null, false));
        assertNull(SkillTeacher.parse("right click these", null, false));
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
