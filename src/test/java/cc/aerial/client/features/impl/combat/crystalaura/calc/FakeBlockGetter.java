package cc.aerial.client.features.impl.combat.crystalaura.calc;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;

import java.util.List;
import java.util.Random;

/**
 * Minimal {@link BlockGetter} over a dense {@code BlockState} array covering
 * {@code [0, sizeX) x [0, sizeY) x [0, sizeZ)}; every cell outside that box is air.
 *
 * <p>Because {@code BlockGetter.clip} is a default method running the real {@code traverseBlocks},
 * {@code fake.clip(new ClipContext(from, to, COLLIDER, NONE, CollisionContext.empty()))} is the
 * vanilla oracle these tests measure the replicas against.</p>
 *
 * <p>Requires {@code MinecraftBootstrap.ensure()} before construction — {@code Blocks} is
 * registry-backed.</p>
 */
public final class FakeBlockGetter implements BlockGetter {
    /** Edge length of the terrains built by {@link #randomTerrain}. */
    public static final int SIZE = 24;

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final BlockState air = Blocks.AIR.defaultBlockState();
    private final BlockState[] states;

    public FakeBlockGetter(int sizeX, int sizeY, int sizeZ) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.states = new BlockState[sizeX * sizeY * sizeZ];
        java.util.Arrays.fill(this.states, air);
    }

    public boolean contains(int x, int y, int z) {
        return x >= 0 && x < sizeX && y >= 0 && y < sizeY && z >= 0 && z < sizeZ;
    }

    public BlockState get(int x, int y, int z) {
        return contains(x, y, z) ? states[index(x, y, z)] : air;
    }

    public void set(int x, int y, int z, BlockState state) {
        if (!contains(x, y, z)) {
            throw new IllegalArgumentException(
                    "(" + x + ", " + y + ", " + z + ") is outside the " + sizeX + "x" + sizeY + "x" + sizeZ + " region");
        }
        states[index(x, y, z)] = state;
    }

    /** Sets every cell of the closed box {@code min..max}, clamped to the region, to {@code state}. */
    public void fill(BlockPos min, BlockPos max, BlockState state) {
        for (int x = Math.max(min.getX(), 0); x <= Math.min(max.getX(), sizeX - 1); x++) {
            for (int y = Math.max(min.getY(), 0); y <= Math.min(max.getY(), sizeY - 1); y++) {
                for (int z = Math.max(min.getZ(), 0); z <= Math.min(max.getZ(), sizeZ - 1); z++) {
                    states[index(x, y, z)] = state;
                }
            }
        }
    }

    private int index(int x, int y, int z) {
        return (y * sizeZ + z) * sizeX + x;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return get(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getHeight() {
        return sizeY;
    }

    @Override
    public int getMinY() {
        return 0;
    }

    /**
     * Block palette of {@link #randomTerrain}: full cubes, half slabs (both halves), stairs, a
     * 1.5-high fence, a thin pane, a fluid (empty collision shape under
     * {@code CollisionContext.empty()}), a no-collision plant and air.
     */
    public static List<BlockState> palette() {
        return List.of(
                Blocks.STONE.defaultBlockState(),
                Blocks.OBSIDIAN.defaultBlockState(),
                Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM),
                Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP),
                Blocks.OAK_STAIRS.defaultBlockState(),
                Blocks.OAK_FENCE.defaultBlockState(),
                Blocks.GLASS_PANE.defaultBlockState(),
                Blocks.WATER.defaultBlockState(),
                Blocks.SHORT_GRASS.defaultBlockState(),
                Blocks.AIR.defaultBlockState());
    }

    /**
     * A {@link #SIZE}^3 terrain: solid stone floor at {@code y = 0} and stone walls at {@code x = 0}
     * and {@code z = 0}, then every remaining cell is set with probability {@code density} to a
     * uniformly drawn {@link #palette} entry.
     */
    public static FakeBlockGetter randomTerrain(Random random, double density) {
        FakeBlockGetter fake = new FakeBlockGetter(SIZE, SIZE, SIZE);
        BlockState stone = Blocks.STONE.defaultBlockState();
        int last = SIZE - 1;
        fake.fill(new BlockPos(0, 0, 0), new BlockPos(last, 0, last), stone);
        fake.fill(new BlockPos(0, 0, 0), new BlockPos(0, last, last), stone);
        fake.fill(new BlockPos(0, 0, 0), new BlockPos(last, last, 0), stone);
        List<BlockState> palette = palette();
        for (int i = 0; i < SIZE * SIZE * SIZE; i++) {
            if (random.nextDouble() >= density) {
                continue;
            }
            int x = i % SIZE;
            int z = (i / SIZE) % SIZE;
            int y = i / (SIZE * SIZE);
            if (x == 0 || y == 0 || z == 0) {
                continue;
            }
            fake.set(x, y, z, palette.get(random.nextInt(palette.size())));
        }
        return fake;
    }

    public static FakeBlockGetter randomTerrain(Random random) {
        return randomTerrain(random, 0.12);
    }
}
