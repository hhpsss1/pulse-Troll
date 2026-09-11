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
 * One {@code BlocksAttacks.DamageReduction} of the item the victim is blocking with:
 * {@code record DamageReduction(float horizontalBlockingAngle, Optional<HolderSet<DamageType>> type,
 * float base, float factor)}.
 *
 * <p>{@code typeMatches} pre-resolves the registry-backed half of {@code DamageReduction.resolve}: it is
 * false exactly when {@code type.isPresent() && !type.get().contains(source.typeHolder())} for the
 * crystal's {@code player_explosion} damage type. Vanilla shields carry the default reduction
 * {@code new DamageReduction(90.0F, Optional.empty(), 0.0F, 1.0F)}.</p>
 */
public record BlockReduction(float horizontalBlockingAngle, boolean typeMatches, float base, float factor) {
}
