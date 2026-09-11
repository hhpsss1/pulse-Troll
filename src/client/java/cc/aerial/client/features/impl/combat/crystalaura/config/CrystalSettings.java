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
import cc.aerial.client.property.Property;

/**
 * Root of the module's setting tree.
 *
 * <p>The seven nodes are kept separate on purpose: every name below is unique only within its own
 * parent. {@code ConfigUtility} serialises by display name and stops at the first match among
 * siblings, so flattening Place and Break into the root would collide on Range and Delay and lose
 * the Break values on every load.
 */
public final class CrystalSettings {
    public final PlaceSettings place = new PlaceSettings();
    public final BreakSettings brk = new BreakSettings();
    public final RotationSettings rotation = new RotationSettings();
    public final BasePlaceSettings basePlace = new BasePlaceSettings();
    public final TargetSettings target = new TargetSettings();
    public final RenderSettings render = new RenderSettings();

    /** Per-tick decision log and the on-screen breakdown. */
    public final BooleanProperty debug = new BooleanProperty("Debug", false);

    public Property<?>[] getProperties() {
        return new Property<?>[]{
                place.get(), brk.get(), rotation.get(), basePlace.get(), target.get(), render.get(), debug,
        };
    }
}
