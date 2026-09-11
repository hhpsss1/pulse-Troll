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

package cc.aerial.client.features.impl.combat.crystalaura.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Distance helpers the scans use thousands of times a tick, written so nothing is allocated. */
public final class ScanGeometry {
    private ScanGeometry() {
    }

    /**
     * Squared distance from a point to the unit cube of a block: the arithmetic of
     * {@code AABB.distanceToSqr} without allocating the box.
     */
    public static double blockDistanceSq(int x, int y, int z, Vec3 point) {
        double dx = Math.max(Math.max(x - point.x, point.x - (x + 1)), 0.0);
        double dy = Math.max(Math.max(y - point.y, point.y - (y + 1)), 0.0);
        double dz = Math.max(Math.max(z - point.z, point.z - (z + 1)), 0.0);
        return Mth.lengthSquared(dx, dy, dz);
    }

    /**
     * Squared distance from a point to the hitbox of a crystal standing on the given block —
     * {@code [x - 0.5, y + 1, z - 0.5] .. [x + 1.5, y + 3, z + 1.5]}, since the crystal is sized
     * 2 x 2 — with the same arithmetic and no allocation.
     */
    public static double crystalDistanceSq(int x, int y, int z, Vec3 point) {
        double dx = Math.max(Math.max(x - 0.5 - point.x, point.x - (x + 1.5)), 0.0);
        double dy = Math.max(Math.max(y + 1.0 - point.y, point.y - (y + 3.0)), 0.0);
        double dz = Math.max(Math.max(z - 0.5 - point.z, point.z - (z + 1.5)), 0.0);
        return Mth.lengthSquared(dx, dy, dz);
    }

    /**
     * Every block position in the axis-aligned cube of the given radius around a point.
     *
     * <p>The iterator reuses one mutable position, so an accepted cell must be copied with
     * {@code immutable()} before it is kept.
     */
    public static Iterable<BlockPos> blocksInCuboid(Vec3 center, float radius) {
        return BlockPos.betweenClosed(
                (int) Math.floor(center.x - radius),
                (int) Math.floor(center.y - radius),
                (int) Math.floor(center.z - radius),
                (int) Math.ceil(center.x + radius),
                (int) Math.ceil(center.y + radius),
                (int) Math.ceil(center.z + radius));
    }
}
