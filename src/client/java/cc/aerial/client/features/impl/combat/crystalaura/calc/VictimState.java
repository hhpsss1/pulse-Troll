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

import net.minecraft.world.Difficulty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

/**
 * Everything the damage pipeline needs about one victim, frozen for one evaluation.
 *
 * <ul>
 *   <li>{@code feet}: position used for the distance ({@code Entity.distanceToSqr}), predicted when
 *       applicable.</li>
 *   <li>{@code box}: bounding box at that position, for the exposure sampling.</li>
 *   <li>{@code isPlayer}: difficulty scaling lives in {@code Player.hurtServer} only.</li>
 *   <li>{@code skip}: creative / spectator / dead / invulnerable — never damaged, matching the
 *       {@code return false} guards of {@code Player.hurtServer}.</li>
 *   <li>{@code armor}: {@code getArmorValue()}, i.e. {@code Mth.floor(getAttributeValue(ARMOR))}.</li>
 *   <li>{@code toughness}: {@code (float) getAttributeValue(ARMOR_TOUGHNESS)}.</li>
 *   <li>{@code resistanceAmplifier}: {@code getEffect(RESISTANCE).getAmplifier()}, or -1 when absent.</li>
 *   <li>{@code protectionPoints}: client-side replica of {@code EnchantmentHelper.getDamageProtection};
 *       see {@link EnchantProtection}.</li>
 *   <li>{@code absorption}: {@code getAbsorptionAmount()}.</li>
 *   <li>{@code health}: current health, compared by {@link DamageResult#isLethalFor}.</li>
 *   <li>{@code blocking}: null when not blocking, or when the block delay has not elapsed.</li>
 *   <li>{@code invulnerableTime}: {@code Entity.invulnerableTime}, already compensated for latency by
 *       the caller.</li>
 *   <li>{@code lastHurt}: ledger estimate of {@code LivingEntity.lastHurt}; null means unknown, which
 *       is treated as 0.</li>
 *   <li>{@code difficulty}: {@code level.getDifficulty()}.</li>
 * </ul>
 */
public record VictimState(
        Vec3 feet,
        AABB box,
        boolean isPlayer,
        boolean skip,
        int armor,
        float toughness,
        int resistanceAmplifier,
        float protectionPoints,
        float absorption,
        float health,
        @Nullable BlockingState blocking,
        int invulnerableTime,
        @Nullable Float lastHurt,
        Difficulty difficulty) {
}
