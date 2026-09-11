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

package cc.aerial.client.features.impl.combat.crystalaura.calc;

import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Collision shape of the block at a cell, exactly as {@code state.getCollisionShape(level, pos, context)}
 * returns it.
 *
 * <p>This is the shape {@code ClipContext.Block.COLLIDER} resolves for every cell of
 * {@code BlockGetter.clip}. Fluids are deliberately not represented: the crystal pipeline always
 * clips with {@code ClipContext.Fluid.NONE}, whose fluid shape is {@code Shapes.empty()}, so a fluid
 * can never produce a hit.</p>
 */
@FunctionalInterface
public interface ShapeSource {
    VoxelShape collisionShape(int x, int y, int z);
}
