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

/** How an item is brought into the hand before it is used. */
public enum SwitchMode {
    /** Server-side slot change only; the client keeps showing the current item. */
    SILENT("Silent"),
    /** Real hotbar slot change, visible on the client. */
    NORMAL("Normal"),
    /** Never switch: only act while already holding the item. */
    NONE("None");

    private final String label;

    SwitchMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
