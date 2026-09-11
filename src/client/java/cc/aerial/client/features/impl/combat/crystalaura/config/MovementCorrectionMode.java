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

/** How the player's movement is reconciled with a rotation that differs from the camera. */
public enum MovementCorrectionMode {
    /**
     * No correction. This feels the best, since it changes neither the movement nor sprinting, but it
     * is the most detectable: the server sees a look direction the movement does not agree with.
     */
    OFF("Off"),

    /** Corrects movement by using the sent yaw when the movement is updated. */
    STRICT("Strict"),

    /**
     * Uses the sent yaw for the movement update and also tweaks the keyboard input so the walk
     * direction is not thrown around aggressively.
     */
    SILENT("Silent"),

    /** Corrects movement by turning the player's actual camera. */
    CHANGE_LOOK("ChangeLook");

    private final String label;

    MovementCorrectionMode(String label) {
        this.label = label;
    }

    /** Whether this mode drives the visible camera instead of silently overriding the sent rotation. */
    public boolean usesVisibleCamera() {
        return this == CHANGE_LOOK;
    }

    @Override
    public String toString() {
        return label;
    }
}
