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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Explosion exposure of a bounding box: {@code ServerExplosion.getSeenPercent(center, entity)} for an
 * arbitrary {@link AABB} over a {@link ShapeSource}.
 *
 * <p>Bit-for-bit replica of the sampling: steps {@code 1.0 / (size * 2.0 + 1.0)} per axis, x/z
 * offsets {@code (1.0 - Math.floor(1.0 / step) * step) / 2.0} (there is NO y offset), samples from
 * three nested {@code for (xx = 0.0; xx <= 1.0; xx += xs)} loops that accumulate the step in
 * doubles, positions via {@code Mth.lerp(xx, min, max)}, one {@code COLLIDER} / {@code Fluid.NONE}
 * clip FROM the sample TO the centre whose {@code MISS} counts as a hit, and finally
 * {@code (float) hits / count}. A negative step returns {@code 0.0F} — unreachable through the
 * {@link AABB} constructor, which orders min/max.</p>
 *
 * <p>Deviation: the per-ray clip is {@link ClipCore#isBlocked} over the {@link ShapeSource} instead
 * of {@code entity.level().clip(...)} with the entity's own collision context; the caller chooses
 * the context when building the source.</p>
 */
public final class Exposure {
    private Exposure() {
    }

    /** Receives one ray origin per sample. */
    @FunctionalInterface
    private interface Sampler {
        void accept(double x, double y, double z);
    }

    /**
     * Fraction (0..1) of the sample rays from {@code box} to {@code center} that reach it without
     * touching a collision shape.
     */
    public static float seenPercent(Vec3 center, AABB box, ShapeSource source) {
        // [0] = rays that got through, [1] = rays cast. A holder because a lambda cannot write locals.
        int[] tally = new int[2];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean valid = forEachSample(box, (x, y, z) -> {
            if (!ClipCore.isBlocked(new Vec3(x, y, z), center, source, pos)) {
                tally[0]++;
            }
            tally[1]++;
        });
        return valid ? (float) tally[0] / (float) tally[1] : 0.0f;
    }

    /** Number of rays {@link #seenPercent} casts for {@code box} — 45 for a 0.6 x 1.8 x 0.6 player box. */
    static int sampleCount(AABB box) {
        int[] count = new int[1];
        boolean valid = forEachSample(box, (x, y, z) -> count[0]++);
        return valid ? count[0] : 0;
    }

    /**
     * The sampling loops of {@code getSeenPercent}, calling {@code sampler} with each ray origin.
     *
     * <p>Returns false without sampling when a step is negative — the negation of vanilla's
     * {@code !(xs < 0.0) && !(ys < 0.0) && !(zs < 0.0)} guard, equivalent for NaN as well.</p>
     */
    private static boolean forEachSample(AABB box, Sampler sampler) {
        double xs = 1.0 / ((box.maxX - box.minX) * 2.0 + 1.0);
        double ys = 1.0 / ((box.maxY - box.minY) * 2.0 + 1.0);
        double zs = 1.0 / ((box.maxZ - box.minZ) * 2.0 + 1.0);
        double xOffset = (1.0 - Math.floor(1.0 / xs) * xs) / 2.0;
        double zOffset = (1.0 - Math.floor(1.0 / zs) * zs) / 2.0;
        if (xs < 0.0 || ys < 0.0 || zs < 0.0) {
            return false;
        }

        for (double xx = 0.0; xx <= 1.0; xx += xs) {
            for (double yy = 0.0; yy <= 1.0; yy += ys) {
                for (double zz = 0.0; zz <= 1.0; zz += zs) {
                    double x = Mth.lerp(xx, box.minX, box.maxX);
                    double y = Mth.lerp(yy, box.minY, box.maxY);
                    double z = Mth.lerp(zz, box.minZ, box.maxZ);
                    sampler.accept(x + xOffset, y, z + zOffset);
                }
            }
        }
        return true;
    }
}
