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

import cc.aerial.client.features.impl.combat.crystalaura.calc.DamageResult;
import cc.aerial.client.features.impl.combat.crystalaura.world.TargetEntry;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.phys.Vec3;

/**
 * One accepted attack: the crystal to hit, its explosion centre — its own position — the target it was
 * scored against, that target's damage evaluated at the break snapshot, and the local player's.
 */
public record BreakCandidate(
        EndCrystal crystal,
        Vec3 center,
        TargetEntry target,
        DamageResult damage,
        DamageResult selfDamage) {
}
