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

package cc.aerial.client.features.impl.combat.crystalaura.render;

import cc.aerial.client.features.impl.combat.crystalaura.calc.VictimState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * The render snapshot of one decided plan, built once per plan tick and read by the render handlers between
 * ticks.
 *
 * <p>The placement wins over the attack — it is what the aura is aiming at — and the victim snapshot is the one
 * the winning candidate was scored against.
 */
public record AuraVisuals(
        @Nullable LivingEntity target,
        @Nullable VictimState targetState,
        float killZoneRadius,
        float bestDamage,
        float selfDamage,
        boolean lethal,
        @Nullable BlockPos placePos,
        @Nullable BlockPos basePos,
        @Nullable AABB attackBox) {

    public static final AuraVisuals EMPTY =
            new AuraVisuals(null, null, 0f, 0f, 0f, false, null, null, null);
}
