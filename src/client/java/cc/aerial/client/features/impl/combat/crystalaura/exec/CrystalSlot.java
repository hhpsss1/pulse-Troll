/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */

package cc.aerial.client.features.impl.combat.crystalaura.exec;

import cc.aerial.client.scaffold.SlotSpoof;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * A slot holding an item the aura wants to use: either a hotbar index or the offhand.
 *
 * <p>Aerial has no slot abstraction of its own — modules index the inventory directly — so this is the small
 * one the aura needs: which hand a use goes through, and whether the server already holds it.
 */
public record CrystalSlot(int hotbarIndex, boolean offHand) {
    public static CrystalSlot offhand() {
        return new CrystalSlot(-1, true);
    }

    public static CrystalSlot hotbar(int index) {
        return new CrystalSlot(index, false);
    }

    /** The hand a use with this slot goes through. */
    public InteractionHand useHand() {
        return offHand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    /** The stack in this slot, or empty when there is no player. */
    public ItemStack stack() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return ItemStack.EMPTY;
        }
        return offHand ? player.getOffhandItem() : player.getInventory().getItem(hotbarIndex);
    }

    /**
     * Whether the server already holds this slot. The offhand always is; a hotbar slot is when it is the one the
     * server has selected, which is the spoofed slot while a spoof is live.
     */
    public boolean isHeld() {
        if (offHand) {
            return true;
        }
        return serverSelectedSlot() == hotbarIndex;
    }

    /** The hotbar slot the server currently believes is selected. */
    public static int serverSelectedSlot() {
        if (SlotSpoof.isActive()) {
            return SlotSpoof.getSlot();
        }
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? -1 : player.getInventory().getSelectedSlot();
    }
}
