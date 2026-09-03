package com.lilahcraft.lumen.craft;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The words an ingredient list shares, for "i'd need any planks". */
class CraftPlannerTest {

    @Test
    @DisplayName("the shared tail of a tag's names")
    void commonTail() {
        assertEquals("planks", CraftPlanner.commonTail(List.of("oak planks", "birch planks", "crimson planks")));
        assertEquals("oak log", CraftPlanner.commonTail(List.of("oak log", "stripped oak log")));
        assertEquals("", CraftPlanner.commonTail(List.of("coal", "charcoal")));
        assertEquals("", CraftPlanner.commonTail(List.of()));
    }
}
