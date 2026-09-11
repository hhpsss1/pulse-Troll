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

import cc.aerial.client.property.BooleanProperty;
import cc.aerial.client.property.GroupProperty;
import cc.aerial.client.property.NumberProperty;

/**
 * Base placement (off by default): place an obsidian base first when a crystal on it would deal at
 * least {@link #minGain} percent more damage than the best direct placement. {@link #range} is the
 * eye-to-hit-point limit for placing the obsidian.
 *
 * <p>{@link #mine} is the same idea one step earlier: when the base cell is not free yet, break what
 * is in the way first.
 */
public final class BasePlaceSettings {
    /** Whether base placement runs at all. */
    public final BooleanProperty enabled = new BooleanProperty("Enabled", false);

    /** Minimum damage gain over the best direct placement, in percent. */
    public final NumberProperty minGain = new NumberProperty("MinGain", 20, 0, 200, 1);

    /** Maximum distance from the eye to the hit point when placing the obsidian. */
    public final NumberProperty range = new NumberProperty("Range", 4.5, 1, 5.5, 0.1);

    /** Mine the cells that stand between us and a better base. */
    public final MineSettings mine = new MineSettings();

    private final GroupProperty group;

    public BasePlaceSettings() {
        minGain.hideIf(() -> !enabled.getValue());
        range.hideIf(() -> !enabled.getValue());
        mine.get().hideIf(() -> !enabled.getValue());
        this.group = new GroupProperty("BasePlace", enabled, minGain, range, mine.get());
    }

    public boolean isEnabled() {
        return enabled.getValue();
    }

    /** AutoMine only runs when base placement itself is on. */
    public boolean isMining() {
        return isEnabled() && mine.isEnabled();
    }

    public GroupProperty get() {
        return group;
    }
}
