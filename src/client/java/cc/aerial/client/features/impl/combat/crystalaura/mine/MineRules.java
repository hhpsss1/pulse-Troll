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

package cc.aerial.client.features.impl.combat.crystalaura.mine;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.BiPredicate;

/**
 * The pure state rules of {@link MineSearch}: which cells a plan has to clear, and what may be cleared at all.
 *
 * <p>Everything here reads only its arguments, which is what makes the rules of the mining search testable
 * without a client. The sibling of {@code BaseRules} for the mined variant.
 */
public final class MineRules {
    private MineRules() {
    }

    /** Clear mask: nothing has to be broken — the cell is a base cell already, so it is the base search's job. */
    public static final int CLEAR_NONE = 0;

    /** Clear mask: the base cell itself must be broken before the obsidian can replace it. */
    public static final int CLEAR_BASE = 1;

    /** Clear mask: the cell above the base must be broken, because the crystal needs TRUE AIR there. */
    public static final int CLEAR_ABOVE = 2;

    /** Clear mask: no sequence of breaks turns this cell into a base — undiggable, a fluid, or a falling column. */
    public static final int CLEAR_IMPOSSIBLE = -1;

    /**
     * Which cells have to be broken to turn {@code base} into a cell a crystal can stand on: a bit set of
     * {@link #CLEAR_BASE} and {@link #CLEAR_ABOVE}, {@link #CLEAR_NONE} when nothing has to go, or
     * {@link #CLEAR_IMPOSSIBLE}.
     *
     * <p>Pure, and the reason the rules of {@link MineSearch} are testable: everything it reads comes from
     * {@code level} and the two predicates. {@code replaceable} answers whether the placement context would let
     * the obsidian replace what stands in a cell (the live search passes the real replace-clicked test),
     * {@code clearable} answers "may we break this" — range, the block under our feet, {@link #isDiggable}.
     * {@code scratch} is written twice and holds nothing afterwards.
     *
     * <p>The two cells follow the crystal item's own placement rule: the cell above the base must end as TRUE
     * AIR, and the base itself must end as obsidian or bedrock, which it does either by already being one or by
     * taking the obsidian we place into it. The final test is the falling-block hazard on the single cell above
     * the topmost cell we clear.
     */
    public static int clearMask(BlockGetter level, BlockPos base, BlockPos.MutableBlockPos scratch,
                                BiPredicate<BlockPos, BlockState> replaceable,
                                BiPredicate<BlockPos, BlockState> clearable) {
        scratch.set(base.getX(), base.getY() + 1, base.getZ());
        BlockState aboveState = level.getBlockState(scratch);
        int mask = CLEAR_NONE;
        if (!aboveState.isAir()) {
            if (!clearable.test(scratch, aboveState)) {
                return CLEAR_IMPOSSIBLE;
            }
            mask = CLEAR_ABOVE;
        }
        BlockState baseState = level.getBlockState(base);
        if (!isCrystalBase(baseState) && !replaceable.test(base, baseState)) {
            if (!clearable.test(base, baseState)) {
                return CLEAR_IMPOSSIBLE;
            }
            mask |= CLEAR_BASE;
        }
        if (mask == CLEAR_NONE) {
            return CLEAR_NONE;
        }
        scratch.set(base.getX(), base.getY() + ((mask & CLEAR_ABOVE) != 0 ? 2 : 1), base.getZ());
        return fallsWhenUnsupported(level.getBlockState(scratch)) ? CLEAR_IMPOSSIBLE : mask;
    }

    /** The block half of the crystal item's own base test: the clicked state is obsidian or bedrock. */
    public static boolean isCrystalBase(BlockState state) {
        return state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK);
    }

    /**
     * Whether the block at a position can be mined away into AIR.
     *
     * <p>Three refusals, all of them load-bearing:
     * <ul>
     *   <li>air — nothing to dig, and an anticheat flags a dig packet naming an air block;</li>
     *   <li>a non-empty fluid state — the break would write {@code fluidState.createLegacyBlock()}, i.e. water
     *       instead of air, both client-side and on the server, and only an empty fluid legacy-blocks to air.
     *       This is the waterlogging hazard and, at the same time, the water / lava / bubble-column half of the
     *       anticheat's own undiggable list, since those blocks carry their own fluid;</li>
     *   <li>hardness {@code -1} — the destroy progress is then exactly {@code 0.0F} and the dig never completes:
     *       bedrock, barrier, light, moving piston. An anticheat treats a finished-digging on one of them as
     *       invalid.</li>
     * </ul>
     *
     * <p>The wrong tool is deliberately NOT a refusal: it is a 3.33x penalty (the 30 versus 100 divisor of the
     * destroy-progress formula), which the MaxTicks setting prices instead. Creative mode is not modelled
     * either — the aura exists for survival play, where a hardness {@code -1} block is simply out of reach of
     * the dig loop.
     */
    public static boolean isDiggable(BlockGetter level, BlockPos pos, BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        return state.getDestroySpeed(level, pos) >= 0f;
    }

    /**
     * Whether a state falls once the block below it is gone, i.e. whether its block is a {@link FallingBlock} —
     * the class whose tick spawns a falling-block entity as soon as the cell below is free, scheduled two ticks
     * out by the shape update. That tick is server-only, so the client never predicts it and the pocket
     * silently refills.
     *
     * <p>In 26.2 that is the anvils, gravel and the sands, the sixteen concrete powders and the dragon egg.
     * Suspicious sand and suspicious gravel are brushable blocks, not falling blocks, and do NOT fall.
     */
    public static boolean fallsWhenUnsupported(BlockState state) {
        return state.getBlock() instanceof FallingBlock;
    }
}
