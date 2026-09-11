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

/**
 * Per-stage output of {@link VictimPipeline#apply}; every field is the value vanilla holds at that
 * point of {@code Player.hurtServer} to {@code LivingEntity.hurtServer} to {@code actuallyHurt}.
 *
 * <ul>
 *   <li>{@code exposure}: the {@code getSeenPercent} input. {@code raw}: {@code getEntityDamageAmount}.</li>
 *   <li>{@code afterDifficulty}: after the {@code Player.hurtServer} scaling; unchanged for non-players.</li>
 *   <li>{@code afterBlocking}: {@code damage -= damageBlocked} — what vanilla stores in {@code lastHurt}
 *       when the hit lands. When {@code iFrameGated} it is still reported, although vanilla returns
 *       early and leaves {@code lastHurt} untouched.</li>
 *   <li>{@code iFrameGated}: {@code invulnerableTime > 10 && damage <= lastHurt} — nothing is dealt.</li>
 *   <li>{@code dealt}: the argument of {@code actuallyHurt}: {@code damage - lastHurt} inside the
 *       window, else {@code damage}.</li>
 *   <li>{@code afterArmor}: {@code getDamageAfterArmorAbsorb}. {@code afterMagic}:
 *       {@code getDamageAfterMagicAbsorb} (resistance plus protection).</li>
 *   <li>{@code absorbed} / {@code healthLoss}: the absorption split of {@code actuallyHurt}.</li>
 * </ul>
 */
public record DamageResult(
        float exposure,
        float raw,
        float afterDifficulty,
        float afterBlocking,
        boolean iFrameGated,
        float dealt,
        float afterArmor,
        float afterMagic,
        float absorbed,
        float healthLoss) {

    /** All zeros, {@code iFrameGated} false. */
    public static final DamageResult ZERO =
            new DamageResult(0f, 0f, 0f, 0f, false, 0f, 0f, 0f, 0f, 0f);

    /**
     * No damage, but carrying the inputs that were already known when the pipeline bailed out, so a
     * caller can still see what exposure and raw damage the evaluation was working from.
     */
    public static DamageResult none(float exposure, float raw) {
        return new DamageResult(exposure, raw, 0f, 0f, false, 0f, 0f, 0f, 0f, 0f);
    }

    /** Damage that reaches absorption and health, i.e. {@link #afterMagic()}. */
    public float effective() {
        return afterMagic;
    }

    /** True when {@code setHealth(getHealth() - healthLoss)} would reach zero. */
    public boolean isLethalFor(VictimState victim) {
        return healthLoss >= victim.health();
    }
}
