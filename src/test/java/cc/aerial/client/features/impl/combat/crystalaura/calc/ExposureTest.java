package cc.aerial.client.features.impl.combat.crystalaura.calc;

import cc.aerial.client.testsupport.MinecraftBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Exposure} against a transcription of {@code ServerExplosion.getSeenPercent} that clips with
 * the vanilla {@code BlockGetter.clip}.
 */
class ExposureTest {
    private static final int SCENES = 200;
    private static final int SIZE = FakeBlockGetter.SIZE;

    @BeforeAll
    static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    private static FakeBlockGetter emptyWorld() {
        return new FakeBlockGetter(SIZE, SIZE, SIZE);
    }

    private static ShapeSource source(FakeBlockGetter fake) {
        return new LevelShapeSource(fake, CollisionContext.empty());
    }

    /** Player-sized box (0.6 x 1.8 x 0.6) standing at the given feet position. */
    private static AABB playerBox(double x, double y, double z) {
        return new AABB(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
    }

    /** Victim at (11, 1, 11); a stone wall in the x = 13 plane, z = 10..12, y = 1..wallTop. */
    private static FakeBlockGetter wallScene(int wallTop) {
        FakeBlockGetter fake = emptyWorld();
        fake.fill(new BlockPos(13, 1, 10), new BlockPos(13, wallTop, 12), Blocks.STONE.defaultBlockState());
        return fake;
    }

    /**
     * {@code ServerExplosion.getSeenPercent} transcribed line by line, with {@code entity.level().clip(...)}
     * replaced by the fake's vanilla clip and an empty collision context.
     */
    private static float referenceSeenPercent(Vec3 center, AABB bb, FakeBlockGetter fake) {
        double xs = 1.0 / ((bb.maxX - bb.minX) * 2.0 + 1.0);
        double ys = 1.0 / ((bb.maxY - bb.minY) * 2.0 + 1.0);
        double zs = 1.0 / ((bb.maxZ - bb.minZ) * 2.0 + 1.0);
        double xOffset = (1.0 - Math.floor(1.0 / xs) * xs) / 2.0;
        double zOffset = (1.0 - Math.floor(1.0 / zs) * zs) / 2.0;
        if (xs < 0.0 || ys < 0.0 || zs < 0.0) {
            return 0.0f;
        }

        int hits = 0;
        int count = 0;
        for (double xx = 0.0; xx <= 1.0; xx += xs) {
            for (double yy = 0.0; yy <= 1.0; yy += ys) {
                for (double zz = 0.0; zz <= 1.0; zz += zs) {
                    double x = Mth.lerp(xx, bb.minX, bb.maxX);
                    double y = Mth.lerp(yy, bb.minY, bb.maxY);
                    double z = Mth.lerp(zz, bb.minZ, bb.maxZ);
                    Vec3 from = new Vec3(x + xOffset, y, z + zOffset);
                    ClipContext context = new ClipContext(from, center,
                            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty());
                    if (fake.clip(context).getType() == HitResult.Type.MISS) {
                        hits++;
                    }
                    count++;
                }
            }
        }
        return (float) hits / (float) count;
    }

    @Test
    void openFieldExposesTheWholeBox() {
        FakeBlockGetter fake = emptyWorld();
        AABB box = playerBox(10.0, 1.0, 10.0);
        Vec3 centre = new Vec3(14.5, 1.0, 14.5);

        assertEquals(1.0f, Exposure.seenPercent(centre, box, source(fake)));
        assertEquals(1.0f, referenceSeenPercent(centre, box, fake));
    }

    @Test
    void victimFullyBehindAThreeWideThreeHighWallHasZeroExposure() {
        FakeBlockGetter fake = wallScene(3);
        AABB box = playerBox(11.0, 1.0, 11.0);
        Vec3 centre = new Vec3(16.5, 2.9, 11.0);

        assertEquals(0.0f, Exposure.seenPercent(centre, box, source(fake)));
        assertEquals(0.0f, referenceSeenPercent(centre, box, fake));
    }

    @Test
    void wallCoveringOnlyTheLowestCellLeavesAFractionStrictlyBetweenZeroAndOne() {
        FakeBlockGetter fake = wallScene(1);
        AABB box = playerBox(11.0, 1.0, 11.0);
        Vec3 centre = new Vec3(16.5, 2.9, 11.0);

        float exposure = Exposure.seenPercent(centre, box, source(fake));
        assertTrue(exposure > 0.0f && exposure < 1.0f, "exposure " + exposure);
        assertEquals(referenceSeenPercent(centre, box, fake), exposure);
        // Rays rise towards the centre: the two lowest of the five sample rows (y = 1.0 and 1.39) cross
        // the x = 13 plane below y = 2 and hit the wall cell, the other three pass above it.
        assertEquals(27.0f / 45.0f, exposure, "two of five sample rows are behind the single wall cell");
    }

    @Test
    void playerBoxIsSampledWith45Rays() {
        assertEquals(45, Exposure.sampleCount(playerBox(11.0, 1.0, 11.0)));
        assertEquals(45, Exposure.sampleCount(new AABB(0.2, 64.0, -3.7, 0.8, 65.8, -3.1)));
    }

    @Test
    void matchesTheVanillaReferenceOnRandomScenes() {
        Random random = new Random(0x5EE_D00L);
        int partial = 0;
        for (int scene = 0; scene < SCENES; scene++) {
            FakeBlockGetter fake = FakeBlockGetter.randomTerrain(random, 0.04 + random.nextDouble() * 0.2);
            AABB box = playerBox(
                    2.0 + random.nextDouble() * 20.0,
                    1.0 + random.nextDouble() * 18.0,
                    2.0 + random.nextDouble() * 20.0);
            Vec3 centre = new Vec3(
                    1.0 + random.nextDouble() * 22.0,
                    0.5 + random.nextDouble() * 22.0,
                    1.0 + random.nextDouble() * 22.0);
            float expected = referenceSeenPercent(centre, box, fake);
            float actual = Exposure.seenPercent(centre, box, source(fake));
            assertEquals(expected, actual, "scene " + scene + ": box " + box + ", centre " + centre);
            if (expected > 0.0f && expected < 1.0f) {
                partial++;
            }
        }
        assertTrue(partial > 0, "no partially covered scene among " + SCENES);
    }
}
