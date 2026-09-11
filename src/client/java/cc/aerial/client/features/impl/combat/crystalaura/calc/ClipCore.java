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

package cc.aerial.client.features.impl.combat.crystalaura.calc;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Block-only line-of-sight test with vanilla's exact ray marching, without a level.
 *
 * <p>{@link #isBlocked} answers
 * {@code level.clip(new ClipContext(from, to, COLLIDER, Fluid.NONE, entity)).getType() != MISS} by
 * replicating {@code BlockGetter.traverseBlocks} together with the per-cell consumer of
 * {@code BlockGetter.clip}:</p>
 *
 * <ul>
 *   <li>{@code from.equals(to)} is a miss.</li>
 *   <li>Both endpoints are moved with {@code Mth.lerp(-1.0E-7, a, b)}: the marched segment is
 *       EXTENDED by 1e-7 of its length beyond both ends. The first cell is {@code Mth.floor} of the
 *       extended start.</li>
 *   <li>The consumer runs on the first cell, then once after every axis step; the step is picked by
 *       the {@code tX < tY}, {@code tX < tZ}, {@code tY < tZ} comparisons in that order,
 *       {@code tDelta*} is {@code Double.MAX_VALUE} for a zero component, the initial {@code t*} use
 *       {@code 1.0 - Mth.frac} for a positive step and {@code Mth.frac} otherwise, and the loop runs
 *       while any {@code t* <= 1.0}.</li>
 *   <li>Per cell, with {@code COLLIDER}, the shape is
 *       {@code state.getCollisionShape(level, pos, collisionContext)} and
 *       {@code blockShape.clip(from, to, pos)} runs with the ORIGINAL endpoints.
 *       {@code clipWithInteractionOverride} only rewrites the direction of a non-null result, and the
 *       {@code Fluid.NONE} fluid shape is {@code Shapes.empty()} whose clip is null — so a cell hits
 *       exactly when {@code blockShape.clip(from, to, pos) != null}.</li>
 * </ul>
 *
 * <p>Why "the march visited a full cube, therefore hit" would be WRONG: the endpoint extension lets
 * the march enter the cell containing {@code to}, and even one past it, but {@code VoxelShape.clip}
 * only accepts a face with {@code 0.0 < s && s < 1.0} along the ORIGINAL segment. A crystal centre
 * lying exactly on the top face of its obsidian base yields {@code s == 1.0}: the obsidian cell is
 * visited and is NOT a hit. Hence every visited cell goes through the real {@code VoxelShape.clip};
 * the only shortcut is vanilla's own {@code shape.isEmpty() -> null}.</p>
 *
 * <p>Deliberate deviations: none in the arithmetic. Only the boolean outcome is produced (no
 * {@code BlockHitResult}), and the shapes come from a {@link ShapeSource} instead of
 * {@code BlockGetter.getBlockState}.</p>
 */
public final class ClipCore {
    private ClipCore() {
    }

    /** True iff a {@code COLLIDER} / {@code Fluid.NONE} clip from {@code from} to {@code to} would not miss. */
    public static boolean isBlocked(Vec3 from, Vec3 to, ShapeSource source) {
        return isBlocked(from, to, source, new BlockPos.MutableBlockPos());
    }

    /**
     * Same as {@link #isBlocked(Vec3, Vec3, ShapeSource)} but marches with the caller's scratch
     * position, so batches of rays — the 45 exposure samples of one victim — allocate nothing per
     * ray. Per step nothing is allocated except what {@code VoxelShape.clip} itself allocates for a
     * hit.
     */
    public static boolean isBlocked(Vec3 from, Vec3 to, ShapeSource source, BlockPos.MutableBlockPos pos) {
        if (from.equals(to)) {
            return false;
        }

        double toX = Mth.lerp(-1.0E-7, to.x, from.x);
        double toY = Mth.lerp(-1.0E-7, to.y, from.y);
        double toZ = Mth.lerp(-1.0E-7, to.z, from.z);
        double fromX = Mth.lerp(-1.0E-7, from.x, to.x);
        double fromY = Mth.lerp(-1.0E-7, from.y, to.y);
        double fromZ = Mth.lerp(-1.0E-7, from.z, to.z);
        int x = Mth.floor(fromX);
        int y = Mth.floor(fromY);
        int z = Mth.floor(fromZ);
        if (hits(from, to, source, pos.set(x, y, z))) {
            return true;
        }

        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        int signX = Mth.sign(dx);
        int signY = Mth.sign(dy);
        int signZ = Mth.sign(dz);
        double tDeltaX = tDelta(signX, dx);
        double tDeltaY = tDelta(signY, dy);
        double tDeltaZ = tDelta(signZ, dz);
        double tX = tStart(signX, tDeltaX, fromX);
        double tY = tStart(signY, tDeltaY, fromY);
        double tZ = tStart(signZ, tDeltaZ, fromZ);

        while (tX <= 1.0 || tY <= 1.0 || tZ <= 1.0) {
            if (tX < tY) {
                if (tX < tZ) {
                    x += signX;
                    tX += tDeltaX;
                } else {
                    z += signZ;
                    tZ += tDeltaZ;
                }
            } else if (tY < tZ) {
                y += signY;
                tY += tDeltaY;
            } else {
                z += signZ;
                tZ += tDeltaZ;
            }

            if (hits(from, to, source, pos.set(x, y, z))) {
                return true;
            }
        }

        return false;
    }

    /** {@code sign == 0 ? Double.MAX_VALUE : sign / delta}, with the int widened as in Java. */
    private static double tDelta(int sign, double delta) {
        return sign == 0 ? Double.MAX_VALUE : sign / delta;
    }

    /** {@code tDelta * (sign > 0 ? 1.0 - Mth.frac(from) : Mth.frac(from))}. */
    private static double tStart(int sign, double tDelta, double from) {
        return tDelta * (sign > 0 ? 1.0 - Mth.frac(from) : Mth.frac(from));
    }

    /**
     * The {@code COLLIDER} / {@code Fluid.NONE} cell consumer of {@code BlockGetter.clip} reduced to
     * its boolean outcome: {@code shape.isEmpty()} short-circuits like {@code VoxelShape.clip}
     * itself, otherwise the real {@code VoxelShape.clip(from, to, pos)} with the ORIGINAL endpoints
     * decides.
     */
    private static boolean hits(Vec3 from, Vec3 to, ShapeSource source, BlockPos.MutableBlockPos pos) {
        VoxelShape shape = source.collisionShape(pos.getX(), pos.getY(), pos.getZ());
        return !shape.isEmpty() && shape.clip(from, to, pos) != null;
    }
}
