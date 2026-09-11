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

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Same as the delegate except that {@code cells[i]} reports {@code shapes[i]}, the FIRST match winning.
 *
 * <p>The mined variant of {@link OverrideShapeSource}: the mining search scores a base that does not
 * exist yet AND whose pocket is not dug yet, so the exposure rays have to see the world as it WILL
 * be — the future obsidian at the base cell as the full cube {@code Shapes.block()} and every cell
 * the dig removes as {@code Shapes.empty()}. Air is what a finished dig really leaves:
 * {@code MultiPlayerGameMode.destroyBlock} writes {@code fluidState.createLegacyBlock()}, and for an
 * empty fluid that block is air — the mining search refuses to dig any cell whose fluid is
 * non-empty, so the "empty" override is never a lie.</p>
 *
 * <p>The base cell is expected FIRST, because a plan may both dig its base cell and put the obsidian
 * there; first match wins, so the cube beats the emptiness.</p>
 *
 * <p>Cells are keyed by {@link BlockPos#asLong} and scanned linearly. With the two or three cells of
 * one plan that is a handful of {@code long} compares and no allocation per query, which is what
 * matters: the rays ask for a shape thousands of times per tick, and a map would hash or box a key
 * on every one of them.</p>
 */
public final class MultiOverrideShapeSource implements ShapeSource {
    private final ShapeSource base;
    private final long[] cells;
    private final VoxelShape[] shapes;

    public MultiOverrideShapeSource(ShapeSource base, long[] cells, VoxelShape[] shapes) {
        if (cells.length != shapes.length) {
            throw new IllegalArgumentException(
                    "cells (" + cells.length + ") and shapes (" + shapes.length + ") must have the same size");
        }
        this.base = base;
        this.cells = cells;
        this.shapes = shapes;
    }

    @Override
    public VoxelShape collisionShape(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        for (int index = 0; index < cells.length; index++) {
            if (cells[index] == key) {
                return shapes[index];
            }
        }
        return base.collisionShape(x, y, z);
    }
}
