package com.lilahcraft.lumen.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The words people use to ask for an area, and the order it gets dug in. */
class QuarryPlannerTest {

    @Test
    @DisplayName("WxLxH and a level")
    void parsesSizes() {
        QuarryPlanner.Spec spec = QuarryPlanner.parse("go down to level 2 and mine out a 20x20x2 and bring me back all the loot");
        assertNotNull(spec);
        assertEquals(20, spec.sizeX());
        assertEquals(20, spec.sizeZ());
        assertEquals(2, spec.height());
        assertEquals(2, spec.targetY());
        QuarryPlanner.Spec two = QuarryPlanner.parse("a 10 by 10 area 3 blocks deep");
        assertNotNull(two);
        assertEquals(10, two.sizeX());
        assertEquals(3, two.height());
        assertNull(two.targetY());
    }

    @Test
    @DisplayName("named levels and the selection")
    void parsesLevels() {
        assertEquals(QuarryPlanner.BEDROCK_LEVEL, QuarryPlanner.parse("mine out a 5x5x1 down at bedrock").targetY());
        assertEquals(QuarryPlanner.DIAMOND_LEVEL, QuarryPlanner.parse("a 8x8x3 at diamond level").targetY());
        assertEquals(-20, QuarryPlanner.parse("quarry 6x6x2 at y -20").targetY());
        QuarryPlanner.Spec selection = QuarryPlanner.parse("mine out the selection");
        assertNotNull(selection);
        assertTrue(selection.selection());
        assertNull(QuarryPlanner.parse("copper"));
        assertNull(QuarryPlanner.parse("some iron ore"));
    }

    @Test
    @DisplayName("the region sits under the feet and is centred")
    void regionAround() {
        QuarryPlanner.Spec spec = QuarryPlanner.parse("4x4x2");
        QuarryPlanner.Region r = QuarryPlanner.regionAround(100, 64, 200, spec);
        assertEquals(63, r.maxY());
        assertEquals(62, r.minY());
        assertEquals(4, r.sizeX());
        assertEquals(4, r.sizeZ());
        assertEquals(32, r.volume());
        assertTrue(r.minX() <= 100 && r.maxX() >= 100);
    }

    @Test
    @DisplayName("digging order: top layer first, serpentine rows")
    void order() {
        QuarryPlanner.Region r = QuarryPlanner.Region.of(0, 10, 0, 2, 11, 1);
        List<int[]> order = QuarryPlanner.order(r, 0, 0);
        assertEquals(12, order.size());
        assertArrayEquals(new int[] {0, 11, 0}, order.get(0));
        assertArrayEquals(new int[] {2, 11, 0}, order.get(2));
        assertArrayEquals(new int[] {2, 11, 1}, order.get(3)); // next row starts where the last ended
        assertEquals(10, order.get(6)[1]); // second layer after the first
    }

    @Test
    @DisplayName("a staircase clears head then feet, one step down per block")
    void staircase() {
        List<int[]> steps = QuarryPlanner.staircase(0, 70, 0, 67, 1, 0);
        assertEquals(6, steps.size());
        assertArrayEquals(new int[] {1, 70, 0}, steps.get(0));
        assertArrayEquals(new int[] {1, 69, 0}, steps.get(1));
        assertArrayEquals(new int[] {3, 67, 0}, steps.get(5));
        assertEquals(2, QuarryPlanner.descent(70, QuarryPlanner.Region.of(0, 60, 0, 3, 67, 3)));
    }
}
