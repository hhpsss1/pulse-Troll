package cc.aerial.client.features.impl.combat.crystalaura.calc;

import cc.aerial.client.testsupport.MinecraftBootstrap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClipCore} against the vanilla DDA: {@code FakeBlockGetter.clip} is the default
 * {@code BlockGetter.clip}, i.e. the real {@code traverseBlocks} + {@code VoxelShape.clip}.
 */
class ClipCoreTest {
    private static final int TERRAINS = 4;
    private static final int SEGMENTS_PER_TERRAIN = 3000;
    private static final int SIZE = FakeBlockGetter.SIZE;

    @BeforeAll
    static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    private static FakeBlockGetter emptyWorld() {
        return new FakeBlockGetter(SIZE, SIZE, SIZE);
    }

    /** The oracle: vanilla clip with COLLIDER / Fluid.NONE / an empty collision context. */
    private static boolean vanillaBlocked(FakeBlockGetter fake, Vec3 from, Vec3 to) {
        ClipContext context = new ClipContext(
                from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty());
        return fake.clip(context).getType() == HitResult.Type.BLOCK;
    }

    /** Shapes resolved exactly like ClipContext.Block.COLLIDER does. */
    private static ShapeSource rawSource(FakeBlockGetter fake) {
        return (x, y, z) -> {
            BlockPos pos = new BlockPos(x, y, z);
            return fake.getBlockState(pos).getCollisionShape(fake, pos, CollisionContext.empty());
        };
    }

    @Test
    void agreesWithTheVanillaClipOnRandomSegmentsThroughMixedTerrain() {
        int blocked = 0;
        int total = 0;
        for (int terrain = 0; terrain < TERRAINS; terrain++) {
            long seed = 0x5EED_0000L + terrain;
            FakeBlockGetter fake = FakeBlockGetter.randomTerrain(new Random(seed));
            ShapeSource raw = rawSource(fake);
            LevelShapeSource memo = new LevelShapeSource(fake, CollisionContext.empty());
            SegmentGenerator segments = new SegmentGenerator(seed ^ 0x7A11L);
            for (int i = 0; i < SEGMENTS_PER_TERRAIN; i++) {
                Vec3 from = segments.point();
                Vec3 to = segments.end(from);
                boolean expected = vanillaBlocked(fake, from, to);
                String where = "terrain " + terrain + ", segment " + i + ": " + from + " -> " + to;
                assertEquals(expected, ClipCore.isBlocked(from, to, raw), where);
                assertEquals(expected, ClipCore.isBlocked(from, to, memo), "memoized " + where);
                if (expected) {
                    blocked++;
                }
                total++;
            }
        }
        assertTrue(blocked >= 1 && blocked < total,
                "degenerate sample: " + blocked + " of " + total + " segments blocked");
    }

    @Test
    void crystalCentreOnTopFaceOfItsBaseIsNotBlockedAlthoughTheCellIsVisited() {
        FakeBlockGetter fake = emptyWorld();
        fake.set(5, 5, 5, Blocks.OBSIDIAN.defaultBlockState());
        Vec3 from = new Vec3(2.3, 7.7, 2.1);
        Vec3 centre = new Vec3(5.5, 6.0, 5.5);
        ShapeSource raw = rawSource(fake);
        LongOpenHashSet visited = new LongOpenHashSet();
        ShapeSource counting = (x, y, z) -> {
            visited.add(BlockPos.asLong(x, y, z));
            return raw.collisionShape(x, y, z);
        };

        assertFalse(ClipCore.isBlocked(from, centre, counting), "s == 1.0 on the top face must be rejected");
        assertTrue(visited.contains(BlockPos.asLong(5, 5, 5)), "the DDA must have visited the obsidian cell");
        assertFalse(vanillaBlocked(fake, from, centre), "vanilla must agree");
    }

    @Test
    void stoneBetweenTheEyeAndTheCrystalCentreBlocksTheRay() {
        FakeBlockGetter fake = emptyWorld();
        fake.set(5, 5, 5, Blocks.OBSIDIAN.defaultBlockState());
        fake.set(4, 6, 4, Blocks.STONE.defaultBlockState());
        Vec3 from = new Vec3(2.3, 7.7, 2.1);
        Vec3 centre = new Vec3(5.5, 6.0, 5.5);

        assertTrue(ClipCore.isBlocked(from, centre, rawSource(fake)));
        assertTrue(vanillaBlocked(fake, from, centre));
    }

    @Test
    void samplePointInsideABlockIsBlocked() {
        FakeBlockGetter fake = emptyWorld();
        fake.set(5, 5, 5, Blocks.OBSIDIAN.defaultBlockState());
        Vec3 inside = new Vec3(5.5, 5.5, 5.5);
        Vec3 target = new Vec3(8.5, 6.0, 8.5);

        assertTrue(ClipCore.isBlocked(inside, target, rawSource(fake)));
        assertTrue(vanillaBlocked(fake, inside, target));
    }

    @Test
    void identicalEndpointsNeverBlockEvenInsideABlock() {
        FakeBlockGetter fake = emptyWorld();
        fake.set(5, 5, 5, Blocks.OBSIDIAN.defaultBlockState());
        Vec3 inside = new Vec3(5.5, 5.5, 5.5);

        assertFalse(ClipCore.isBlocked(inside, new Vec3(5.5, 5.5, 5.5), rawSource(fake)));
        assertFalse(vanillaBlocked(fake, inside, new Vec3(5.5, 5.5, 5.5)));
    }

    @Test
    void overrideSourceInjectsAndRemovesSingleCells() {
        FakeBlockGetter fake = emptyWorld();
        fake.set(4, 6, 4, Blocks.STONE.defaultBlockState());
        ShapeSource raw = rawSource(fake);
        Vec3 from = new Vec3(2.3, 7.7, 2.1);
        Vec3 centre = new Vec3(5.5, 6.0, 5.5);

        ShapeSource futureBase = new OverrideShapeSource(raw, 5, 5, 5, Shapes.block());
        assertTrue(ClipCore.isBlocked(from, centre, futureBase), "the stone still blocks");
        ShapeSource stoneRemoved = new OverrideShapeSource(raw, 4, 6, 4, Shapes.empty());
        assertFalse(ClipCore.isBlocked(from, centre, stoneRemoved), "without the stone the ray is free");
        ShapeSource baseOnly = new OverrideShapeSource(stoneRemoved, 5, 5, 5, Shapes.block());
        assertFalse(ClipCore.isBlocked(from, centre, baseOnly), "a cube whose top face holds the centre is no hit");
    }

    @Test
    void levelSourceMemoizesUntilCleared() {
        FakeBlockGetter fake = emptyWorld();
        LevelShapeSource memo = new LevelShapeSource(fake, CollisionContext.empty());
        assertTrue(memo.collisionShape(4, 6, 4).isEmpty());

        fake.set(4, 6, 4, Blocks.STONE.defaultBlockState());
        assertTrue(memo.collisionShape(4, 6, 4).isEmpty(), "stale by design until clear()");
        memo.clear();
        assertFalse(memo.collisionShape(4, 6, 4).isEmpty());
    }

    /** Seeded generator of the endpoint mix the contract asks for. */
    private static final class SegmentGenerator {
        private final Random random;

        SegmentGenerator(long seed) {
            this.random = new Random(seed);
        }

        /** Coordinate in [-1, 25). One in eight lands exactly on an integer plane, one in eight on a .5 plane. */
        double coordinate(boolean horizontal) {
            double raw = random.nextDouble() * 26.0 - 1.0;
            return switch (random.nextInt(8)) {
                case 0 -> Math.floor(raw);
                case 1 -> horizontal ? Math.floor(raw) + 0.5 : raw;
                default -> raw;
            };
        }

        Vec3 point() {
            return new Vec3(coordinate(true), coordinate(false), coordinate(true));
        }

        /** Unit direction. One in six is axis-aligned, which exercises the sign == 0 branches. */
        Vec3 direction() {
            if (random.nextInt(6) == 0) {
                double sign = random.nextBoolean() ? 1.0 : -1.0;
                return switch (random.nextInt(3)) {
                    case 0 -> new Vec3(sign, 0.0, 0.0);
                    case 1 -> new Vec3(0.0, sign, 0.0);
                    default -> new Vec3(0.0, 0.0, sign);
                };
            }
            Vec3 v = new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian());
            while (v.lengthSqr() < 1.0E-6) {
                v = new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian());
            }
            return v.normalize();
        }

        /** End point: 2 percent identical, 3 percent nearer than 1e-4, else 0.05 to 14 blocks away. */
        Vec3 end(Vec3 from) {
            int roll = random.nextInt(100);
            if (roll < 2) {
                return from;
            }
            if (roll < 5) {
                return from.add(1.0E-5 * (random.nextDouble() * 5.0 + 1.0), -2.0E-5, 3.0E-6);
            }
            return snap(from.add(direction().scale(0.05 + random.nextDouble() * 13.95)));
        }

        /** One in eight snaps every coordinate to an integer plane, one in eight snaps x/z to .5 planes. */
        private Vec3 snap(Vec3 p) {
            return switch (random.nextInt(8)) {
                case 0 -> new Vec3(Math.floor(p.x), Math.floor(p.y), Math.floor(p.z));
                case 1 -> new Vec3(Math.floor(p.x) + 0.5, p.y, Math.floor(p.z) + 0.5);
                default -> p;
            };
        }
    }
}
