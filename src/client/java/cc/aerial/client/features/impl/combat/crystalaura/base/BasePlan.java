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

import cc.aerial.client.features.impl.combat.crystalaura.aim.LookPoint;
import cc.aerial.client.features.impl.combat.crystalaura.scan.PlaceCandidate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

/**
 * An obsidian base to place, with the look point that clicks it; the candidate is the crystal placement on that
 * block which justifies it.
 *
 * <p>Vanilla has TWO placement forms and this carries both, because the placement context picks between them by
 * itself: whether the clicked block can be replaced decides whether the placed position is the clicked one or its
 * neighbour on the hit face.
 *
 * <ul>
 *   <li>{@code face} non-null: the relative form. The click lands on the neighbour opposite that face, and the hit
 *       direction IS the placement — it must come back from the ray.</li>
 *   <li>{@code face} null: the replace-in-place form. The click lands on the block itself, the cell already holds
 *       a replaceable block, and vanilla ignores the hit direction entirely — so ANY face the ray produces is
 *       correct and the executor must not constrain it.</li>
 * </ul>
 */
public record BasePlan(
        BlockPos block,
        BlockPos clickPos,
        @Nullable Direction face,
        LookPoint look,
        PlaceCandidate candidate) {

    /** The replace-in-place form: the click lands on the block itself and the hit direction is free. */
    public boolean replaceClicked() {
        return face == null;
    }
}
