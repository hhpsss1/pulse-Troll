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
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

/**
 * Where the obsidian of a {@link MineTarget} goes in — the same pair the direct base plan carries in its clicked
 * position and its face, produced by the same reading of vanilla's block placement context, and like it in BOTH
 * of vanilla's forms:
 *
 * <ul>
 *   <li>{@code face} non-null: click that face of the NEIGHBOUR {@code pos}, {@code pos.relative(face)} being
 *       the base cell — a non-replaceable clicked block sends the obsidian to the neighbour on the hit
 *       face.</li>
 *   <li>{@code face} null: {@code pos} IS the base cell and the obsidian replaces what stands in it — the
 *       replace-clicked branch makes the context answer the clicked position itself, and the hit direction is
 *       read by nobody. This is the only form available for a base cell every one of whose neighbours is itself
 *       replaceable (a snow layer or a fire on open ground), and it exists here so such a cell is not wrongly
 *       judged unreachable.</li>
 * </ul>
 *
 * <p>{@code pos} is guaranteed NOT to be one of the cells the plan breaks. That is the one hard rule the mined
 * variant adds: an anticheat resyncs a placement made against a block it believes is air at cancel VL <b>0</b>,
 * and a block we just finished breaking IS air in its world. Its only escape hatch excuses a clicked position
 * whose recorded modification is younger than two ticks AND caused by a start-digging or a netty sync — an
 * INSTANT break, which a multi-tick dig never is. For the replace-in-place form that same rule is what forbids
 * it on a plan that clears the base cell: the cell would be air by the time the obsidian went in, so the search
 * only offers it when the base cell is not on the clear list.
 *
 * <p>Nothing here is ever re-clicked. It is the search's PROOF that the obsidian has a way in; the placement
 * itself runs on a later tick through the base-place search, which finds its own click target against the world
 * as it is then.
 */
public record BaseClick(BlockPos pos, @Nullable Direction face) {
}
