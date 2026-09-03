package com.lilahcraft.lumen.entity;

import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * One way of reading and taking from a container, whatever it is underneath.
 *
 * <p>Two kinds of storage exist on a Fabric server. Vanilla chests, barrels and most
 * modded crates implement {@link Inventory}. Storage networks and the more elaborate
 * modded blocks - Tom's Storage's inventory connector, drawers, anything a pipe mod
 * can pull from - expose themselves through the Fabric transfer API instead, as a
 * {@code Storage<ItemVariant>}. Lumen used to read only the first kind, which is why
 * {@code /lumen containers} listed the inventory connector as not searchable and a
 * whole storage network with it.
 *
 * <p>The transfer API is preferred when a block offers both: for a double chest it
 * covers both halves, where the block entity alone is one half.
 */
public abstract class ContainerAccess {

    /** @return a way into whatever storage is at {@code pos}, or null if there is none */
    @Nullable
    public static ContainerAccess at(ServerWorld world, BlockPos pos) {
        if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
            return null;
        }
        Storage<ItemVariant> storage = null;
        try {
            storage = ItemStorage.SIDED.find(world, pos, null);
        } catch (RuntimeException e) {
            // A misbehaving modded block must not end the errand; fall through to the
            // plain inventory, if it has one.
        }
        if (storage != null && (storage.supportsExtraction() || storage.supportsInsertion())) {
            return new TransferApiAccess(storage);
        }
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity instanceof Inventory inventory) {
            return new InventoryAccess(inventory);
        }
        return null;
    }

    /** True when Lumen would be able to read the block entity, without building an access. */
    public static boolean isSearchable(ServerWorld world, BlockPos pos, @Nullable BlockEntity blockEntity) {
        if (blockEntity instanceof Inventory) {
            return true;
        }
        try {
            Storage<ItemVariant> storage = ItemStorage.SIDED.find(world, pos, null);
            return storage != null && storage.supportsExtraction();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A snapshot of what is inside: copies of every non-empty stack, one per slot or view. */
    public abstract List<ItemStack> contents();

    /**
     * Takes up to {@code max} items that are the same item (and NBT) as {@code sample},
     * and never more than one stack's worth in a single call - loop for more.
     *
     * @return what was taken, or an empty stack
     */
    public abstract ItemStack take(ItemStack sample, int max);

    /**
     * Puts as much of {@code stack} in as will fit.
     *
     * @return what did not fit, or an empty stack
     */
    public abstract ItemStack put(ItemStack stack);

    /** Called once after a round of taking, so the block saves and updates comparators. */
    public void finish() {
    }

    // ------------------------------------------------------------ Inventory

    private static final class InventoryAccess extends ContainerAccess {

        private final Inventory inventory;

        private InventoryAccess(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public List<ItemStack> contents() {
            List<ItemStack> out = new ArrayList<>();
            for (int slot = 0; slot < inventory.size(); slot++) {
                ItemStack stack = inventory.getStack(slot);
                if (!stack.isEmpty()) {
                    out.add(stack.copy());
                }
            }
            return out;
        }

        @Override
        public ItemStack take(ItemStack sample, int max) {
            ItemStack taken = ItemStack.EMPTY;
            // Never build a stack bigger than the item allows: 128 stone in one slot
            // is a glitch waiting to happen in every screen that shows it.
            int remaining = Math.min(max, sample.getMaxCount());
            for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
                ItemStack stack = inventory.getStack(slot);
                if (stack.isEmpty() || !ItemStack.canCombine(stack, sample)) {
                    continue;
                }
                ItemStack removed = inventory.removeStack(slot, Math.min(remaining, stack.getCount()));
                if (removed.isEmpty()) {
                    continue;
                }
                remaining -= removed.getCount();
                if (taken.isEmpty()) {
                    taken = removed;
                } else {
                    taken.increment(removed.getCount());
                }
            }
            return taken;
        }

        @Override
        public ItemStack put(ItemStack stack) {
            ItemStack rest = stack.copy();
            // Top up matching stacks first, then empty slots, the way a shift-click does.
            for (int pass = 0; pass < 2 && !rest.isEmpty(); pass++) {
                for (int slot = 0; slot < inventory.size() && !rest.isEmpty(); slot++) {
                    ItemStack present = inventory.getStack(slot);
                    if (pass == 0) {
                        if (present.isEmpty() || !ItemStack.canCombine(present, rest)) {
                            continue;
                        }
                        int room = Math.min(present.getMaxCount(), inventory.getMaxCountPerStack()) - present.getCount();
                        if (room <= 0) {
                            continue;
                        }
                        int moved = Math.min(room, rest.getCount());
                        present.increment(moved);
                        rest.decrement(moved);
                    } else {
                        if (!present.isEmpty() || !inventory.isValid(slot, rest)) {
                            continue;
                        }
                        int moved = Math.min(rest.getCount(), Math.min(rest.getMaxCount(), inventory.getMaxCountPerStack()));
                        inventory.setStack(slot, rest.copyWithCount(moved));
                        rest.decrement(moved);
                    }
                }
            }
            return rest;
        }

        @Override
        public void finish() {
            inventory.markDirty();
        }
    }

    // --------------------------------------------------------- transfer API

    private static final class TransferApiAccess extends ContainerAccess {

        private final Storage<ItemVariant> storage;

        private TransferApiAccess(Storage<ItemVariant> storage) {
            this.storage = storage;
        }

        @Override
        public List<ItemStack> contents() {
            List<ItemStack> out = new ArrayList<>();
            try {
                for (StorageView<ItemVariant> view : storage.nonEmptyViews()) {
                    long amount = view.getAmount();
                    ItemVariant resource = view.getResource();
                    if (amount <= 0 || resource.isBlank()) {
                        continue;
                    }
                    out.add(resource.toStack((int) Math.min(amount, Integer.MAX_VALUE)));
                }
            } catch (RuntimeException e) {
                // Iteration over a modded network can fail in ways vanilla never does;
                // report what was read so far rather than crashing the tick.
            }
            return out;
        }

        @Override
        public ItemStack put(ItemStack stack) {
            if (stack.isEmpty() || !storage.supportsInsertion()) {
                return stack;
            }
            ItemVariant variant = ItemVariant.of(stack);
            try (Transaction transaction = Transaction.openOuter()) {
                long inserted = storage.insert(variant, stack.getCount(), transaction);
                if (inserted <= 0) {
                    transaction.abort();
                    return stack;
                }
                transaction.commit();
                return stack.copyWithCount((int) (stack.getCount() - inserted));
            } catch (RuntimeException e) {
                return stack;
            }
        }

        @Override
        public ItemStack take(ItemStack sample, int max) {
            if (!storage.supportsExtraction()) {
                return ItemStack.EMPTY;
            }
            ItemVariant variant = ItemVariant.of(sample);
            try (Transaction transaction = Transaction.openOuter()) {
                long extracted = storage.extract(variant, Math.min(max, sample.getMaxCount()), transaction);
                if (extracted <= 0) {
                    transaction.abort();
                    return ItemStack.EMPTY;
                }
                transaction.commit();
                return variant.toStack((int) extracted);
            } catch (RuntimeException e) {
                return ItemStack.EMPTY;
            }
        }
    }
}
