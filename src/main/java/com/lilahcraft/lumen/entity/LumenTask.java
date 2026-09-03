package com.lilahcraft.lumen.entity;

import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * One thing Lumen has been asked to do, as data.
 *
 * <p>Before v0.7.0 an errand lived only as a pile of fields on the entity, so a second
 * request could only cancel the first. A task is a small immutable record that can sit
 * in a queue, be started when its turn comes, be paused for a "come here", and be
 * described to the player. The entity's per-errand fields are the projection of
 * whichever task is running right now.
 */
public sealed interface LumenTask {

    /** Who asked, so the goods come back to them. Null for a bare "go there". */
    @Nullable
    UUID requester();

    /** One line for the queue listing and the world snapshot. */
    String describe();

    /** Fetch something from containers, searched around {@code anchor} when given. */
    record Fetch(UUID requester, ChestFinder.Request request, @Nullable BlockPos anchor,
                 @Nullable String anchorName) implements LumenTask {
        @Override
        public String describe() {
            return "fetch " + ChestFinder.describeRequest(request)
                    + (anchorName == null ? "" : " from the " + anchorName);
        }
    }

    /** Break blocks of a kind, searched around {@code anchor} when given. */
    record Mine(UUID requester, String query, @Nullable BlockPos anchor,
                @Nullable String anchorName) implements LumenTask {
        @Override
        public String describe() {
            return "mine " + query + (anchorName == null ? "" : " near the " + anchorName);
        }
    }

    /** Walk to a spot. {@code placeName} is set when it came from a named place. */
    record GoTo(@Nullable UUID requester, BlockPos pos, @Nullable String placeName) implements LumenTask {
        @Override
        public String describe() {
            return placeName != null ? "go to the " + placeName
                    : "go to " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
        }
    }

    /** Walk back to the requester. What "then come back" queues. */
    record Return(UUID requester) implements LumenTask {
        @Override
        public String describe() {
            return "come back";
        }
    }

    /** Hand items over once beside the requester. Null query means everything. */
    record Handover(UUID requester, @Nullable String query) implements LumenTask {
        @Override
        public String describe() {
            return "hand over " + (query == null ? "everything" : "the " + query);
        }
    }

    /**
     * Run a taught skill, step by step, around {@code anchor} (or Lumen). {@code count}
     * caps the first block step or take step: "harvest 10 hops". 0 means all.
     */
    record Harvest(UUID requester, String skillName, @Nullable BlockPos anchor, @Nullable String anchorName,
                   int count) implements LumenTask {
        public Harvest(UUID requester, String skillName, @Nullable BlockPos anchor, @Nullable String anchorName) {
            this(requester, skillName, anchor, anchorName, 0);
        }

        @Override
        public String describe() {
            return (count > 0 ? skillName + " (" + count + ")" : skillName)
                    + (anchorName == null ? "" : " at the " + anchorName);
        }
    }

    /**
     * Put items from the pack into a container: the one at {@code container} when given,
     * else one found by {@code containerRef} ("nearest", "with iron", or a place name).
     * Empty query means everything Lumen carries; count 0 means all of it.
     */
    record Deposit(UUID requester, String query, int count, @Nullable BlockPos container,
                   @Nullable String containerRef) implements LumenTask {
        @Override
        public String describe() {
            return "put " + (query.isEmpty() ? "everything" : (count > 0 ? count + " " : "") + query)
                    + " into " + (container != null ? "the container at " + container.toShortString()
                            : containerRef == null || containerRef.isEmpty() ? "a container" : "the " + containerRef);
        }
    }

    /** Dig a staircase down (or walk up) to stand with feet at {@code targetY}. */
    record Descend(UUID requester, int targetY) implements LumenTask {
        @Override
        public String describe() {
            return "go down to y " + targetY;
        }
    }

    /** Stand still for a while - a step in a taught skill. */
    record Wait(@Nullable UUID requester, int ticks) implements LumenTask {
        @Override
        public String describe() {
            return "wait " + Math.max(1, ticks / 20) + "s";
        }
    }

    /** Dig out a region, top layer first. */
    record Quarry(UUID requester, QuarryPlanner.Region region, String label) implements LumenTask {
        @Override
        public String describe() {
            return "mine out " + label;
        }
    }

    /** Craft something from the pack, walking to a crafting table if the recipe needs one. */
    record Craft(UUID requester, String query, int count) implements LumenTask {
        @Override
        public String describe() {
            return "craft " + (count > 1 ? count + " " : "") + query;
        }
    }

    /** Pick up what a job dropped around {@code center} before heading back. */
    record Collect(UUID requester, BlockPos center, double radius) implements LumenTask {
        @Override
        public String describe() {
            return "pick up the drops";
        }
    }
}
