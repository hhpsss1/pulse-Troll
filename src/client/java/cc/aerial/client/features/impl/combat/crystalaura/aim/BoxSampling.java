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

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Sampling helpers for the look point search. */
final class BoxSampling {
    private BoxSampling() {
    }

    /** The point at the given proportions along each axis of the box. */
    static Vec3 pointAtProportion(AABB box, double px, double py, double pz) {
        return new Vec3(
                Math.fma(box.getXsize(), px, box.minX),
                Math.fma(box.getYsize(), py, box.minY),
                Math.fma(box.getZsize(), pz, box.minZ));
    }

    /** The point at proportions {@code a} and {@code b} across one face of the box. */
    static Vec3 samplePointOnSide(AABB box, Direction side, double a, double b) {
        return switch (side) {
            case DOWN -> pointAtProportion(box, a, 0.0, b);
            case UP -> pointAtProportion(box, a, 1.0, b);
            case NORTH -> pointAtProportion(box, a, b, 0.0);
            case SOUTH -> pointAtProportion(box, a, b, 1.0);
            case WEST -> pointAtProportion(box, 0.0, a, b);
            case EAST -> pointAtProportion(box, 1.0, a, b);
        };
    }

    /** The point of the box nearest to a position; the position itself when the box contains it. */
    static Vec3 nearestPoint(AABB box, Vec3 from) {
        return new Vec3(
                Math.clamp(from.x, box.minX, box.maxX),
                Math.clamp(from.y, box.minY, box.maxY),
                Math.clamp(from.z, box.minZ, box.maxZ));
    }
}
