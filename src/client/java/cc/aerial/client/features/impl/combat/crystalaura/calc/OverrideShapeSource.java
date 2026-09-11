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
 * Same as the delegate except that one cell reports a shape of the caller's choosing.
 *
 * <p>Used to score a placement whose obsidian base does not exist yet: overriding the base position
 * with {@code Shapes.block()} makes the exposure rays see the future base exactly like a real full
 * cube.</p>
 */
public final class OverrideShapeSource implements ShapeSource {
    private final ShapeSource base;
    private final int x;
    private final int y;
    private final int z;
    private final VoxelShape shape;

    public OverrideShapeSource(ShapeSource base, int x, int y, int z, VoxelShape shape) {
        this.base = base;
        this.x = x;
        this.y = y;
        this.z = z;
        this.shape = shape;
    }

    @Override
    public VoxelShape collisionShape(int x, int y, int z) {
        return x == this.x && y == this.y && z == this.z ? shape : base.collisionShape(x, y, z);
    }
}
