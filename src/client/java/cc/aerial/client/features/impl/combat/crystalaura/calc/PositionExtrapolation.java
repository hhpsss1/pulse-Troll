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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Predicts where something will be in a given number of ticks. */
@FunctionalInterface
public interface PositionExtrapolation {
    Vec3 getPositionInTicks(double ticks);

    /**
     * The best predictor available for this entity: players are run through the movement simulation,
     * everything else is extrapolated linearly from its last step.
     */
    static PositionExtrapolation getBestForEntity(Entity target) {
        if (target instanceof Player player) {
            PlayerSimulationExtrapolation simulated = PlayerSimulationExtrapolation.of(player);
            if (simulated != null) {
                return simulated;
            }
        }
        return new LinearPositionExtrapolation(target);
    }

    /** A predictor that always answers the same position — for a target that is not moving, or unknown. */
    static PositionExtrapolation constant(Vec3 pos) {
        return ticks -> pos;
    }
}
