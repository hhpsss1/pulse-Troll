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

package cc.aerial.client.features.impl.combat.crystalaura.world;

import cc.aerial.client.features.impl.combat.crystalaura.calc.ShapeSource;
import cc.aerial.client.features.impl.combat.crystalaura.calc.VictimState;
import net.minecraft.world.entity.LivingEntity;

/**
 * One tracked victim of a {@link WorldView}: the live entity plus two frozen {@link VictimState}
 * snapshots — {@code placeState} at the position predicted for a crystal placed now,
 * {@code breakState} at the position predicted for a crystal attacked now (0 ticks means the current
 * position) — and the {@link ShapeSource} the exposure rays of that victim read.
 *
 * <p>Each snapshot carries its own predicted position everywhere the position is used: feet, box and
 * the shield-arc origin all agree, as they do in vanilla.
 *
 * <p>{@code source} is a level shape source with {@code CollisionContext.of(entity)}, the very
 * context {@code ServerExplosion.getSeenPercent} clips with. One instance per (tick, victim): its
 * memo must not outlive the tick.
 */
public record TargetEntry(
        LivingEntity entity,
        VictimState placeState,
        VictimState breakState,
        ShapeSource source) {
}
