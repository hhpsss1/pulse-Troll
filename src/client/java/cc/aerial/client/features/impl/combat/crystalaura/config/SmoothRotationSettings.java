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

package cc.aerial.client.features.impl.combat.crystalaura.config;

import cc.aerial.client.property.GroupProperty;
import cc.aerial.client.property.NumberProperty;

/**
 * Speed envelope of {@link RotationMode#SMOOTH}: how fast the aim turns towards a look point, how
 * fast it hands the view back to the player, and when the hand-back counts as finished. All of it is
 * dead weight in {@link RotationMode#SNAP}, which is why it sits in its own node.
 */
public final class SmoothRotationSettings {
    /** Upper turn speed per tick, in degrees. */
    public final NumberProperty maxSpeed = new NumberProperty("MaxSpeed", 60, 1, 180, 1);

    /** Lower turn speed per tick, in degrees. */
    public final NumberProperty minSpeed = new NumberProperty("MinSpeed", 15, 1, 180, 1);

    /** Fraction of the remaining angle covered per tick. */
    public final NumberProperty accel = new NumberProperty("Accel", 0.6, 0.05, 1, 0.05);

    /** Turn speed per tick when returning to the player's own look direction, in degrees. */
    public final NumberProperty returnSpeed = new NumberProperty("ReturnSpeed", 30, 1, 180, 1);

    /** Angle below which the aim counts as reset to the player's own look direction, in degrees. */
    public final NumberProperty resetThreshold = new NumberProperty("ResetThreshold", 1, 0.1, 5, 0.1);

    private final GroupProperty group =
            new GroupProperty("Smooth", maxSpeed, minSpeed, accel, returnSpeed, resetThreshold);

    public GroupProperty get() {
        return group;
    }
}
