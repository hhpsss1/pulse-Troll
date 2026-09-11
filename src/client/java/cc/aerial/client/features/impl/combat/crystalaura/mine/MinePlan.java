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

package cc.aerial.client.features.impl.combat.crystalaura.mine;

import cc.aerial.client.features.impl.combat.crystalaura.aim.LookPoint;
import cc.aerial.client.features.impl.combat.crystalaura.exec.CrystalSlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * What the mining job decided for this tick, consumed once by its execute step.
 *
 * <p>{@code look} is the rotation to request (null: none was reachable this tick, the caller holds the current
 * one) and {@code slot} the mining tool to bring into the hand at the tick HEAD — both are filed by the hub,
 * exactly as the aura's own plan files its look and its slot, so that the game emits the carried-item change
 * before anything is clicked.
 *
 * <p>{@code entity} is the victim the mined base was scored against, passed to the rotation request as its
 * reset-detection entity so that a smooth step towards the cell is not restarted every tick.
 */
public record MinePlan(
        MineMove move,
        @Nullable BlockPos pos,
        @Nullable Direction face,
        @Nullable LookPoint look,
        @Nullable CrystalSlot slot,
        @Nullable Entity entity) {
}
