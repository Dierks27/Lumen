package com.lilahcraft.lumen.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The matcher a taught skill stores, evaluated on plain strings. */
class BlockMatcherTest {

    private static final Map<String, String> BUSH_RIPE = Map.of("age", "3");
    private static final Map<String, String> BUSH_YOUNG = Map.of("age", "1");
    private static final Map<String, Integer> BUSH_MAX = Map.of("age", 3);

    @Test
    @DisplayName("ripe words are pulled out, the rest names the block")
    void parsesRipeAndPattern() {
        BlockMatcher.Spec spec = BlockMatcher.parse("the ripe hops vines");
        assertTrue(spec.ripe);
        assertEquals("hops vines", spec.pattern);
        assertEquals("ripe hops vines", spec.describe());
    }

    @Test
    @DisplayName("property constraints")
    void parsesProps() {
        BlockMatcher.Spec spec = BlockMatcher.parse("sweet berry bush age>=2 berries=true");
        assertEquals("sweet berry bush", spec.pattern);
        assertEquals(">=2", spec.props.get("age"));
        assertEquals("true", spec.props.get("berries"));
        assertFalse(spec.ripe);
    }

    @Test
    @DisplayName("names match in tiers: id, words, substring, glob")
    void nameTiers() {
        assertTrue(BlockMatcher.nameMatches("minecraft:sweet_berry_bush", "minecraft:sweet_berry_bush", "Sweet Berry Bush"));
        assertTrue(BlockMatcher.nameMatches("sweet berry bush", "minecraft:sweet_berry_bush", "Sweet Berry Bush"));
        assertTrue(BlockMatcher.nameMatches("berry bush", "minecraft:sweet_berry_bush", "Sweet Berry Bush"));
        assertTrue(BlockMatcher.nameMatches("hops", "brewinandchewin:hops_crop", "Hops"));
        assertTrue(BlockMatcher.nameMatches("*vine*", "farmersdelight:tomato_vine", "Tomato Vine"));
        assertFalse(BlockMatcher.nameMatches("stone", "minecraft:cobblestone", "Cobblestone"));
        assertTrue(BlockMatcher.nameMatches("", "anything", "Anything"));
    }

    @Test
    @DisplayName("ripe: the block's own growth check wins, then age at max")
    void ripeness() {
        assertTrue(BlockMatcher.isRipe(BUSH_RIPE, BUSH_MAX, false));
        assertFalse(BlockMatcher.isRipe(BUSH_RIPE, BUSH_MAX, true));
        assertTrue(BlockMatcher.isRipe(BUSH_RIPE, BUSH_MAX, null));
        assertFalse(BlockMatcher.isRipe(BUSH_YOUNG, BUSH_MAX, null));
        assertFalse(BlockMatcher.isRipe(Map.of("facing", "north"), Map.of(), null));
    }

    @Test
    @DisplayName("a spec against a real-looking state")
    void matchesState() {
        BlockMatcher.Spec ripeBush = BlockMatcher.parse("ripe sweet berry bush");
        assertTrue(BlockMatcher.matches(ripeBush, "minecraft:sweet_berry_bush", "Sweet Berry Bush", BUSH_RIPE, BUSH_MAX, null));
        assertFalse(BlockMatcher.matches(ripeBush, "minecraft:sweet_berry_bush", "Sweet Berry Bush", BUSH_YOUNG, BUSH_MAX, null));
        assertFalse(BlockMatcher.matches(ripeBush, "minecraft:oak_log", "Oak Log", Map.of(), Map.of(), null));
        BlockMatcher.Spec maxAge = BlockMatcher.parse("berry bush age=max");
        assertTrue(BlockMatcher.matches(maxAge, "minecraft:sweet_berry_bush", "Sweet Berry Bush", BUSH_RIPE, BUSH_MAX, null));
        BlockMatcher.Spec atLeast = BlockMatcher.parse("berry bush age>=2");
        assertTrue(BlockMatcher.matches(atLeast, "minecraft:sweet_berry_bush", "Sweet Berry Bush", Map.of("age", "2"), BUSH_MAX, null));
        assertFalse(BlockMatcher.matches(atLeast, "minecraft:sweet_berry_bush", "Sweet Berry Bush", BUSH_YOUNG, BUSH_MAX, null));
    }
}
