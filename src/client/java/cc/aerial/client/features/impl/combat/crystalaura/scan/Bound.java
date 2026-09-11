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

package cc.aerial.client.features.impl.combat.crystalaura.scan;

import cc.aerial.client.features.impl.combat.crystalaura.world.TargetEntry;
import net.minecraft.world.phys.Vec3;

/** An explosion centre paired with one target and its exposure-free damage bound; the key names the source. */
final class Bound<K> {
    final K key;
    final Vec3 center;
    final double distSq;
    final TargetEntry target;
    final float ub;

    Bound(K key, Vec3 center, double distSq, TargetEntry target, float ub) {
        this.key = key;
        this.center = center;
        this.distSq = distSq;
        this.target = target;
        this.ub = ub;
    }
}
