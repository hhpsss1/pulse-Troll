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

package cc.aerial.client.features.impl.combat.crystalaura.base;

import cc.aerial.client.utility.InventoryUtility;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** The vanilla rules that decide whether and how a block can be placed at a cell. */
public final class BaseRules {
    private BaseRules() {
    }

    /**
     * Whether the cell may be clicked DIRECTLY so the placed block replaces what stands there — the
     * replace-clicked branch of the placement context, which is how a player puts a block into a snow layer or a
     * fire.
     *
     * <p>Four terms, and the first is the one the placement context itself reads:
     * <ul>
     *   <li>{@code replaceable}: whether the state can be replaced by the item in hand. This is the flag that
     *       decides WHICH branch vanilla takes; with obsidian in hand a snow layer answers yes only at one layer,
     *       so a deeper snow is refused here exactly as vanilla refuses it.</li>
     *   <li>Not air. Air is replaceable but has no outline shape, so no ray can pick it — the pick would walk on.
     *       An air cell is placed into with the RELATIVE form instead.</li>
     *   <li>Not interactable unless sneaking. Exactly the term the neighbour form applies: the clicked block
     *       consumes the click before the item is used at all, unless the player is sneaking with something in
     *       hand. Neither a snow layer nor a fire is interactable, so neither needs the sneak.</li>
     *   <li>A non-empty outline shape, so the pick can land on it at all.</li>
     * </ul>
     *
     * <p>Pure but for the block getter: everything else is passed in, which is what makes the rule testable on a
     * fake world.
     */
    public static boolean isReplaceInPlace(BlockGetter level, BlockPos pos, BlockState state,
                                           boolean replaceable, CollisionContext context, boolean sneaking) {
        if (!replaceable || state.isAir()) {
            return false;
        }
        if (isInteractable(state) && !sneaking) {
            return false;
        }
        return !state.getShape(level, pos, context).isEmpty();
    }

    /**
     * Whether the state would be replaced by placing the stack at this position, i.e. the replace-clicked test of
     * the placement context.
     *
     * <p>Air short-circuits: it is always replaceable and is never the item's own block, so the context need not
     * be built.
     */
    public static boolean canBeReplacedWith(BlockState state, BlockPos pos, ItemStack usedStack) {
        if (state.isAir()) {
            return true;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        BlockPlaceContext context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, usedStack,
                new BlockHitResult(Vec3.atLowerCornerOf(pos), Direction.UP, pos, false));
        return state.canBeReplaced(context);
    }

    /**
     * Whether clicking this block would be consumed by the block itself rather than using the held item.
     *
     * <p>Aerial's own list is reused rather than porting LiquidBounce's, so a block is one thing to every module
     * in the client. The two differ only where LiquidBounce inspects the block state — an empty cake, a full
     * composter, an empty jukebox — and Aerial's list is state-independent, so it treats those as interactable
     * unconditionally. That direction is strictly conservative: it can only refuse a click a player could have
     * made, never allow one they could not.
     */
    public static boolean isInteractable(BlockState state) {
        return InventoryUtility.isBlockInteractable(state.getBlock());
    }
}
