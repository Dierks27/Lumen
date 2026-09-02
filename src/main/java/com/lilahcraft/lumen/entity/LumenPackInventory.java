package com.lilahcraft.lumen.entity;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;

/**
 * Lumen's pack plus what it is wearing and holding, as one inventory the vanilla
 * chest screen can show.
 *
 * <p>The pack fills the top rows. The last row is equipment: main hand, off hand,
 * helmet, chestplate, leggings, boots, in that order, followed by three slots that
 * accept nothing. Before this the sword or pickaxe a player handed over vanished from
 * the screen the moment Lumen equipped it, and there was no way to take it back short
 * of {@code /lumen drop} - which put it on the floor for Lumen to pick straight back up.
 */
public final class LumenPackInventory implements Inventory {

    /** The equipment row, left to right. */
    public static final EquipmentSlot[] EQUIPMENT = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD,
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    public static final int COLUMNS = 9;

    private final LumenEntity lumen;
    private final SimpleInventory pack;

    public LumenPackInventory(LumenEntity lumen, SimpleInventory pack) {
        this.lumen = lumen;
        this.pack = pack;
    }

    /** Rows the screen needs: the pack's rows plus one for equipment. */
    public int rows() {
        return pack.size() / COLUMNS + 1;
    }

    @Override
    public int size() {
        return pack.size() + COLUMNS;
    }

    private EquipmentSlot equipmentAt(int slot) {
        int index = slot - pack.size();
        return index >= 0 && index < EQUIPMENT.length ? EQUIPMENT[index] : null;
    }

    @Override
    public boolean isEmpty() {
        if (!pack.isEmpty()) {
            return false;
        }
        for (EquipmentSlot slot : EQUIPMENT) {
            if (!lumen.getEquippedStack(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getStack(int slot) {
        if (slot < pack.size()) {
            return pack.getStack(slot);
        }
        EquipmentSlot equipment = equipmentAt(slot);
        return equipment == null ? ItemStack.EMPTY : lumen.getEquippedStack(equipment);
    }

    @Override
    public ItemStack removeStack(int slot, int amount) {
        if (slot < pack.size()) {
            return pack.removeStack(slot, amount);
        }
        EquipmentSlot equipment = equipmentAt(slot);
        if (equipment == null) {
            return ItemStack.EMPTY;
        }
        ItemStack worn = lumen.getEquippedStack(equipment);
        if (worn.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack taken = worn.split(amount);
        if (worn.isEmpty()) {
            lumen.equipStack(equipment, ItemStack.EMPTY);
        }
        return taken;
    }

    @Override
    public ItemStack removeStack(int slot) {
        if (slot < pack.size()) {
            return pack.removeStack(slot);
        }
        EquipmentSlot equipment = equipmentAt(slot);
        if (equipment == null) {
            return ItemStack.EMPTY;
        }
        ItemStack worn = lumen.getEquippedStack(equipment);
        lumen.equipStack(equipment, ItemStack.EMPTY);
        return worn;
    }

    @Override
    public void setStack(int slot, ItemStack stack) {
        if (slot < pack.size()) {
            pack.setStack(slot, stack);
            return;
        }
        EquipmentSlot equipment = equipmentAt(slot);
        if (equipment != null) {
            lumen.equipStack(equipment, stack == null ? ItemStack.EMPTY : stack);
        }
    }

    @Override
    public boolean isValid(int slot, ItemStack stack) {
        if (slot < pack.size()) {
            return pack.isValid(slot, stack);
        }
        EquipmentSlot equipment = equipmentAt(slot);
        if (equipment == null) {
            return false; // the three spare slots take nothing
        }
        if (equipment == EquipmentSlot.MAINHAND || equipment == EquipmentSlot.OFFHAND) {
            return true;
        }
        return LivingEntity.getPreferredEquipmentSlot(stack) == equipment;
    }

    @Override
    public void markDirty() {
        pack.markDirty();
    }

    @Override
    public boolean canPlayerUse(PlayerEntity player) {
        return lumen.isAlive() && player.squaredDistanceTo(lumen) <= 64.0D;
    }

    @Override
    public void clear() {
        pack.clear();
        for (EquipmentSlot slot : EQUIPMENT) {
            lumen.equipStack(slot, ItemStack.EMPTY);
        }
    }
}
