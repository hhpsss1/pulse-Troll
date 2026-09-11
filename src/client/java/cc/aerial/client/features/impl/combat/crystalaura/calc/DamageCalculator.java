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

import net.minecraft.world.phys.Vec3;

/**
 * Damage one end-crystal explosion deals to a victim: the per-entity part of
 * {@code ServerExplosion.hurtEntities} followed by the victim's {@code hurtServer} pipeline. No world
 * access of its own — the world enters through the {@link ShapeSource} and the frozen
 * {@link VictimState}.
 *
 * <p>Vanilla order per entity:
 *
 * <pre>{@code
 * double dist = Math.sqrt(entity.distanceToSqr(center)) / doubleRadius;
 * if (!(dist > 1.0)) {
 *     float exposure = getSeenPercent(center, entity);
 *     entity.hurtServer(level, damageSource,
 *             damageCalculator.getEntityDamageAmount(this, entity, exposure));
 * }
 * }</pre>
 *
 * <p>mapped onto {@link ExplosionMath#rawUpperBound} (the {@code dist > 1.0} skip),
 * {@link Exposure#seenPercent}, {@link ExplosionMath#rawDamage} and {@link VictimPipeline#apply}. The
 * explosion's {@code DamageSource.getSourcePosition()} is the position of its direct entity, the
 * crystal, which stands exactly at the explosion centre — hence {@code sourcePosition == center} for
 * the shield arc.
 *
 * <p>Deliberate deviations: the exposure rays are not cast for a victim that can never be hurt
 * ({@link VictimState#skip}; vanilla casts them and only then fails in {@code Player.hurtServer}) nor
 * for one outside the radius (vanilla does not cast them either). Both cases return a zero result
 * whose {@code exposure} field is therefore 0 rather than the never-computed value.
 * {@code respectIFrames} is forwarded unchanged; one instance per configuration value, shared by
 * every victim.
 */
public final class DamageCalculator {
    private final boolean respectIFrames;

    public DamageCalculator(boolean respectIFrames) {
        this.respectIFrames = respectIFrames;
    }

    /**
     * Full evaluation for a crystal exploding at {@code center}: exposure of the victim box through
     * {@code source}, raw damage at the victim's feet, then the victim pipeline with
     * {@code sourcePosition = center}.
     *
     * <p>The raycast is skipped when the victim can never be hurt or when
     * {@link ExplosionMath#rawUpperBound} is 0. The latter happens only through the
     * {@code dist > 1.0} branch — the formula otherwise yields at least 1 — where
     * {@link ExplosionMath#rawDamage} is 0 for every exposure and {@link VictimPipeline#apply}
     * returns a zero result anyway, so the shortcut changes nothing but the {@code exposure} field.
     */
    public DamageResult damage(Vec3 center, VictimState victim, ShapeSource source) {
        if (victim.skip() || ExplosionMath.rawUpperBound(victim.feet(), center) == 0f) {
            return DamageResult.ZERO;
        }

        float exposure = Exposure.seenPercent(center, victim.box(), source);
        float raw = ExplosionMath.rawDamage(victim.feet(), center, exposure);
        return VictimPipeline.apply(victim, raw, exposure, center, respectIFrames);
    }

    /**
     * {@link DamageResult#effective} of {@link #damage} assuming full exposure, without casting a
     * single ray.
     *
     * <p>This bounds the real value from above for every {@link ShapeSource} because every stage from
     * the raw damage to {@link DamageResult#effective} is monotone non-decreasing in its input:
     *
     * <ul>
     *   <li>{@code getEntityDamageAmount}: {@code (pow * pow + pow) / 2 * 7 * 12 + 1} with
     *       {@code pow = (1 - dist) * exposure >= 0} grows with the exposure;</li>
     *   <li>difficulty: 0, {@code min(d / 2 + 1, d)}, {@code d}, {@code d * 3 / 2};</li>
     *   <li>shield: {@code d - clamp(base + factor * d, 0, d)}, non-decreasing whenever
     *       {@code factor <= 1} or {@code base >= 0} — true for every vanilla shield, whose only
     *       reduction is {@code DamageReduction(90.0F, Optional.empty(), 0.0F, 1.0F)}. A data-pack
     *       reduction with {@code base < 0 && factor > 1} would break the bound; documented
     *       assumption;</li>
     *   <li>i-frames: 0 while {@code d <= lastHurt}, then {@code d - lastHurt};</li>
     *   <li>armor: {@code d * (1 - clamp(armor - d / toughness, 0.2 * armor, 20) / 25)} — a larger
     *       {@code d} shrinks the clamped armor, so both factors of the product are non-negative and
     *       non-decreasing;</li>
     *   <li>resistance and protection: linear with a non-negative slope ({@code max(v / 25, 0)} is a
     *       constant 0 for amplifier 4 and above).</li>
     * </ul>
     *
     * <p>Because {@code healthLoss <= effective}, the value also bounds
     * {@link DamageResult#healthLoss}, so a lethal-override check can be pruned with it as well. A
     * victim that is skipped or outside the radius yields 0, matching {@link #damage}.
     */
    public float upperBound(Vec3 center, VictimState victim) {
        return VictimPipeline.apply(victim,
                ExplosionMath.rawUpperBound(victim.feet(), center), 1f, center, respectIFrames)
                .effective();
    }
}
