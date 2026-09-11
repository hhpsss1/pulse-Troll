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
import cc.aerial.client.property.ModeProperty;
import cc.aerial.client.property.NumberProperty;

/**
 * Rotation settings: what the aim does ({@link #mode}, {@link #grid}), how it behaves on the wire
 * ({@link #movementCorrection}, {@link #holdTicks}), and — one node deeper — the
 * {@link RotationMode#SMOOTH} speed envelope. The smooth values are re-exposed as accessors so
 * callers do not care where they are registered.
 */
public final class RotationSettings {
    /** Snap onto the look point, or turn towards it with limited speed. */
    public final ModeProperty<RotationMode> mode = new ModeProperty<>("Mode", RotationMode.SNAP);

    /** Allowed deviation of a look point from the exact aim, in mouse-grid steps. */
    public final ModeProperty<GridMode> grid = new ModeProperty<>("Grid", GridMode.DITHER);

    /** Ticks the rotation is kept after the last action. */
    public final NumberProperty holdTicks = new NumberProperty("HoldTicks", 5, 1, 20, 1);

    /** Movement correction applied while the aim is overridden. */
    public final ModeProperty<MovementCorrectionMode> movementCorrection =
            new ModeProperty<>("MovementCorrection", MovementCorrectionMode.SILENT);

    /** The {@link RotationMode#SMOOTH} speed envelope. */
    public final SmoothRotationSettings smooth = new SmoothRotationSettings();

    private final GroupProperty group;

    public RotationSettings() {
        smooth.get().hideIf(() -> mode.getValue() != RotationMode.SMOOTH);
        this.group = new GroupProperty("Rotation", mode, grid, holdTicks, movementCorrection, smooth.get());
    }

    public float maxSpeed() {
        return smooth.maxSpeed.getValue().floatValue();
    }

    public float minSpeed() {
        return smooth.minSpeed.getValue().floatValue();
    }

    public float accel() {
        return smooth.accel.getValue().floatValue();
    }

    public float returnSpeed() {
        return smooth.returnSpeed.getValue().floatValue();
    }

    public float resetThreshold() {
        return smooth.resetThreshold.getValue().floatValue();
    }

    public GroupProperty get() {
        return group;
    }
}
