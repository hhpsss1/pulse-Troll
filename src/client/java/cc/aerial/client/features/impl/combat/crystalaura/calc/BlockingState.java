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

import java.util.List;

/**
 * Snapshot of the {@code LivingEntity.applyItemBlocking} inputs, captured only while the victim is
 * blocking — that is, {@code getItemBlockingWith()} is non-null: using an item with
 * {@code DataComponents.BLOCKS_ATTACKS} for at least {@code blocksAttacks.blockDelayTicks()} ticks.
 *
 * <ul>
 *   <li>{@code reductions}: {@code blocksAttacks.damageReductions()}.</li>
 *   <li>{@code bypassed}:
 *       {@code blocksAttacks.bypassedBy().map(t -> t.contains(source.typeHolder())).orElse(false)}.</li>
 *   <li>{@code viewVector}: {@code calculateViewVector(0.0F, getYHeadRot())}.</li>
 *   <li>{@code position}: {@code position()}. Vanilla reads it at explosion time, so the capture stores
 *       the same PREDICTED position the owning {@link VictimState#feet} carries — the arc and the
 *       distance must come from one position, or a victim predicted to run past the crystal is scored
 *       with the wrong shield side.</li>
 * </ul>
 */
public record BlockingState(List<BlockReduction> reductions, boolean bypassed, Vec3 viewVector, Vec3 position) {
}
