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

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * {@link ShapeSource} reading a live {@link BlockGetter} through a per-instance memo keyed by
 * {@link BlockPos#asLong}.
 *
 * <p>A cell is resolved at most once with
 * {@code level.getBlockState(pos).getCollisionShape(level, pos, context)}; afterwards it is served
 * from the memo, so the 45 exposure rays of one victim share every cell lookup. Intended lifetime is
 * one instance per (tick, victim): the memo never observes world changes by itself, so call
 * {@link #clear()} or drop the instance whenever the world may have changed.</p>
 *
 * <p>Not thread-safe — the memo and the scratch position are shared.</p>
 */
public final class LevelShapeSource implements ShapeSource {
    private final BlockGetter level;
    private final CollisionContext context;
    private final Long2ObjectOpenHashMap<VoxelShape> memo = new Long2ObjectOpenHashMap<>();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    public LevelShapeSource(BlockGetter level, CollisionContext context) {
        this.level = level;
        this.context = context;
    }

    @Override
    public VoxelShape collisionShape(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        VoxelShape cached = memo.get(key);
        if (cached != null) {
            return cached;
        }
        pos.set(x, y, z);
        VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos, context);
        memo.put(key, shape);
        return shape;
    }

    /** Forgets every memoized shape; the next query of any cell reads the level again. */
    public void clear() {
        memo.clear();
    }
}
