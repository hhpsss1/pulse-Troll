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

import cc.aerial.client.features.impl.combat.crystalaura.scan.PlaceCandidate;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The immutable plan for one mined base: break {@code cells}, then a crystal standing on {@code base} is worth
 * {@code candidate}.
 *
 * <p>{@code cells} is ordered TOP-DOWN — {@code base.above()} before {@code base} when both are in it — because
 * a cell is far easier to aim at once the block above it is gone, and because a dig must never leave an
 * unsupported column above the pocket, which is why the search refuses a plan whose top cell carries a falling
 * block. It holds one or two entries: the crystal item inspects exactly the base cell and the cell above it, so
 * nothing beyond those two ever has to go.
 *
 * <p>{@code click} is null exactly when {@code base} already is obsidian or bedrock and therefore survives the
 * dig as a base — the cheapest kind of target, one dig and no placement at all. In every other case
 * {@code base} ends up air or replaceable and the obsidian has to be clicked in through {@code click}.
 *
 * <p>Nothing here is re-validated after construction: the world moves on, so the consumer must check every cell
 * again on the tick it acts.
 */
public record MineTarget(
        BlockPos base,
        List<BlockPos> cells,
        @Nullable BaseClick click,
        PlaceCandidate candidate) {
}
