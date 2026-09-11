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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Pure arithmetic of an end-crystal explosion; no world access.
 *
 * <p>An attacked crystal explodes with power 6 at its own position ({@code EndCrystal.onDestroyedBy}:
 * {@code level.explode(this, damageSource, null, getX(), getY(), getZ(), 6.0F, false, ...)}), which
 * becomes {@code ServerExplosion.radius} / {@code center}. Per entity,
 * {@code ServerExplosion.hurtEntities} does
 *
 * <pre>{@code
 * float doubleRadius = this.radius * 2.0F;
 * double dist = Math.sqrt(entity.distanceToSqr(this.center)) / doubleRadius;
 * if (!(dist > 1.0)) {
 *     float exposure = ... getSeenPercent(this.center, entity);
 *     entity.hurtServer(level, damageSource,
 *             damageCalculator.getEntityDamageAmount(this, entity, exposure));
 * }
 * }</pre>
 *
 * and {@code ExplosionDamageCalculator.getEntityDamageAmount} is
 *
 * <pre>{@code
 * float doubleRadius = explosion.radius() * 2.0F;
 * Vec3 center = explosion.center();
 * double dist = Math.sqrt(entity.distanceToSqr(center)) / doubleRadius;
 * double pow = (1.0 - dist) * exposure;
 * return (float) ((pow * pow + pow) / 2.0 * 7.0 * doubleRadius + 1.0);
 * }</pre>
 *
 * <p>The exposure argument ({@code getSeenPercent}) is computed by {@link Exposure}; this class only
 * replicates the formula. Every step is done in the same width vanilla uses, so results are
 * bit-identical rather than merely close.</p>
 */
public final class ExplosionMath {
    private ExplosionMath() {
    }

    /** Explosion power of an end crystal: the {@code 6.0F} passed to {@code level.explode}. */
    public static final float POWER = 6.0f;

    /** {@code radius * 2.0F} for {@link #POWER}, as used by both vanilla call sites. */
    public static final float DOUBLE_RADIUS = 12.0f;

    /**
     * {@code EntityTypes.END_CRYSTAL} is {@code .sized(2.0F, 2.0F)} and
     * {@code EntityDimensions.makeBoundingBox} uses {@code float w = width / 2.0F; float h = height;}.
     */
    private static final float HALF_WIDTH = 1.0f;
    private static final float HEIGHT = 2.0f;

    /**
     * Explosion center / crystal position for a crystal standing on {@code obsidian}:
     * {@code (x + 0.5, y + 1, z + 0.5)}.
     *
     * <p>{@code EndCrystalItem.useOn}: {@code BlockPos above = pos.above(); ... new EndCrystal(level,
     * above.getX() + 0.5, above.getY(), above.getZ() + 0.5)}. The explosion center is that entity
     * position.</p>
     */
    public static Vec3 crystalCenter(BlockPos obsidian) {
        return new Vec3(obsidian.getX() + 0.5, obsidian.getY() + 1, obsidian.getZ() + 0.5);
    }

    /**
     * Hitbox of that crystal: {@code [x - 0.5, y + 1, z - 0.5] .. [x + 1.5, y + 3, z + 1.5]}.
     *
     * <p>{@code EntityDimensions.makeBoundingBox(x, y, z)} is
     * {@code new AABB(x - w, y, z - w, x + w, y + h, z + w)} with {@code width = height = 2.0F} and
     * {@code (x, y, z)} = {@link #crystalCenter}. The float half extents widen to double exactly as
     * in Java.</p>
     */
    public static AABB crystalBox(BlockPos obsidian) {
        Vec3 center = crystalCenter(obsidian);
        return new AABB(
                center.x - HALF_WIDTH, center.y, center.z - HALF_WIDTH,
                center.x + HALF_WIDTH, center.y + HEIGHT, center.z + HALF_WIDTH);
    }

    /**
     * {@code Math.sqrt(entity.distanceToSqr(center)) / doubleRadius}, with {@code feet} standing in
     * for the entity position.
     *
     * <p>{@code Entity.distanceToSqr(Vec3)} computes {@code xd = getX() - pos.x} while
     * {@code Vec3.distanceToSqr(Vec3)} computes {@code xd = vec.x - this.x}. The reversed subtraction
     * order does not matter: {@code a - b} and {@code b - a} are exact negations in IEEE 754 and
     * their squares are bit-identical. The float {@code doubleRadius} widens to double through
     * binary numeric promotion.</p>
     */
    public static double distanceFraction(Vec3 feet, Vec3 center) {
        return Math.sqrt(feet.distanceToSqr(center)) / (double) DOUBLE_RADIUS;
    }

    /**
     * {@code getEntityDamageAmount} replica with the {@code dist > 1.0} skip of
     * {@code ServerExplosion.hurtEntities} folded in: returns {@code 0} when the entity is not hurt
     * at all, because vanilla never calls {@code hurtServer} for it.
     *
     * <p>{@code exposure == 0} gives {@code pow == 0} and a damage of exactly {@code 1.0F} — vanilla
     * really does deal 1 to a fully covered entity inside the radius. The trailing cast is the only
     * narrowing. Range for an entity inside the radius: {@code [1, 85]}.</p>
     */
    public static float rawDamage(Vec3 feet, Vec3 center, float exposure) {
        double dist = distanceFraction(feet, center);
        if (dist > 1.0) {
            return 0.0f;
        }
        double pow = (1.0 - dist) * (double) exposure;
        return (float) ((pow * pow + pow) / 2.0 * 7.0 * (double) DOUBLE_RADIUS + 1.0);
    }

    /**
     * {@link #rawDamage} at full exposure. Every step of the formula is monotone in {@code pow >= 0},
     * so this bounds {@link #rawDamage} for any exposure in {@code [0, 1]} without tolerance.
     */
    public static float rawUpperBound(Vec3 feet, Vec3 center) {
        return rawDamage(feet, center, 1.0f);
    }
}
