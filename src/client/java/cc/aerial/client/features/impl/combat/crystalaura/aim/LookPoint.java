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

package cc.aerial.client.features.impl.combat.crystalaura.aim;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * A rotation to request and the point that rotation hits, for one crystal-aura action.
 *
 * <ul>
 *   <li>{@code point}: the hit location of the centre ray — for a block look point the location the executor puts
 *       into the block hit it clicks with, for a crystal the entity hit location, for a phantom box the geometric
 *       entry point.</li>
 *   <li>{@code rotation}: the raw rotation that produced the point; the wire carries its normalised form.</li>
 *   <li>{@code face} and {@code blockPos}: non-null for block look points only — the face as reported by the pick
 *       and the block itself.</li>
 *   <li>{@code angle}: the angle in degrees from the rotation currently on the wire.</li>
 *   <li>{@code servesAttack}: the same rotation also picks the crystal to attack. Always true for crystal and
 *       phantom look points, always false for a face-only look point.</li>
 * </ul>
 */
public record LookPoint(
        Vec3 point,
        Rotation rotation,
        @Nullable Direction face,
        @Nullable BlockPos blockPos,
        float angle,
        boolean servesAttack) {
}
