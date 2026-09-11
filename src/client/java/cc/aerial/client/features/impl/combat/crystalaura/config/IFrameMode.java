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

/** Whether the damage model honours the victim's vanilla invulnerability window. */
public enum IFrameMode {
    /** Model {@code LivingEntity.hurtServer}: inside the window only the delta above lastHurt is dealt. */
    RESPECT("Respect"),
    /** Evaluate every hit as if the victim were outside the window. */
    IGNORE("Ignore");

    private final String label;

    IFrameMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
