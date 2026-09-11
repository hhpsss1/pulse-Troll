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

/** How far ahead target positions are extrapolated for the damage evaluation. */
public enum PredictMode {
    /** Use the current position. */
    OFF("Off"),
    /** Extrapolate by a tick count derived from the latency. */
    AUTO("Auto"),
    /** Extrapolate by the configured tick count. */
    FIXED("Fixed");

    private final String label;

    PredictMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
