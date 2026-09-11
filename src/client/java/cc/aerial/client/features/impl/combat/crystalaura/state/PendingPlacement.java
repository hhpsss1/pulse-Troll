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

package cc.aerial.client.features.impl.combat.crystalaura.state;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * A placement we sent whose crystal is expected to appear at {@code above} — the block above the
 * clicked obsidian — sent on client tick {@code tick}.
 *
 * <p>{@code predictedId} is the ledger's id prediction at send time, or null when no prediction was
 * possible.
 */
public record PendingPlacement(BlockPos above, int tick, @Nullable Integer predictedId) {
}
