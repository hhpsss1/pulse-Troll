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

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Assumes the subject keeps moving at the velocity of its last tick.
 *
 * <p>The velocity is taken as {@code position() - oldPosition()} rather than
 * {@code getDeltaMovement()}: for a remote entity the delta movement is whatever the last velocity
 * packet said, while the position difference is what the entity actually did.
 */
public final class LinearPositionExtrapolation implements PositionExtrapolation {
    private final Vec3 basePosition;
    private final Vec3 velocity;

    public LinearPositionExtrapolation(Vec3 basePosition, Vec3 velocity) {
        this.basePosition = basePosition;
        this.velocity = velocity;
    }

    public LinearPositionExtrapolation(Entity entity) {
        this(entity.position(), entity.position().subtract(entity.oldPosition()));
    }

    @Override
    public Vec3 getPositionInTicks(double ticks) {
        return new Vec3(
                Math.fma(ticks, velocity.x, basePosition.x),
                Math.fma(ticks, velocity.y, basePosition.y),
                Math.fma(ticks, velocity.z, basePosition.z));
    }
}
