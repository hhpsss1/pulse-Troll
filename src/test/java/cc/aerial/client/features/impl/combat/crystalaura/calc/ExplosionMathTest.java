package cc.aerial.client.features.impl.combat.crystalaura.calc;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplosionMathTest {
    private static final Vec3 CENTER = new Vec3(0.5, 64.0, 0.5);

    private static Vec3 randomPoint(Random random, double spread) {
        return CENTER.add(
                (random.nextDouble() * 2.0 - 1.0) * spread,
                (random.nextDouble() * 2.0 - 1.0) * spread,
                (random.nextDouble() * 2.0 - 1.0) * spread);
    }

    @Test
    void constantsMatchTheCrystalExplosion() {
        assertEquals(6f, ExplosionMath.POWER);
        assertEquals(12f, ExplosionMath.DOUBLE_RADIUS);
    }

    @Test
    void fullExposureAtTheCenterDeals85() {
        assertEquals(85f, ExplosionMath.rawDamage(CENTER, CENTER, 1f));
        assertEquals(85f, ExplosionMath.rawUpperBound(CENTER, CENTER));
    }

    @Test
    void halfDistanceFractionWithFullExposureMatchesTheHandComputedValue() {
        Vec3 feet = CENTER.add(0.0, 6.0, 0.0);
        assertEquals(0.5, ExplosionMath.distanceFraction(feet, CENTER), 0.0);
        // pow = 0.5: ((0.25 + 0.5) / 2 * 84 + 1) = 32.5
        assertEquals(32.5f, ExplosionMath.rawDamage(feet, CENTER, 1f), 1e-5f);
    }

    @Test
    void halfExposureAtTheCenterIsTheSameAsFullExposureAtHalfDistance() {
        // dist = 0 gives pow = 0.5
        assertEquals(32.5f, ExplosionMath.rawDamage(CENTER, CENTER, 0.5f), 1e-5f);
    }

    @Test
    void zeroExposureInsideTheRadiusStillDealsExactlyOne() {
        assertEquals(1f, ExplosionMath.rawDamage(CENTER, CENTER, 0f));
        assertEquals(1f, ExplosionMath.rawDamage(CENTER.add(3.0, 1.0, -2.0), CENTER, 0f));
        assertEquals(1f, ExplosionMath.rawDamage(CENTER.add(0.0, -11.9, 0.0), CENTER, 0f));
    }

    @Test
    void beyondTheDoubleRadiusNothingIsDealtButTheBoundaryItselfIsHit() {
        assertTrue(ExplosionMath.distanceFraction(CENTER.add(12.5, 0.0, 0.0), CENTER) > 1.0);
        assertEquals(0f, ExplosionMath.rawDamage(CENTER.add(12.5, 0.0, 0.0), CENTER, 1f));
        assertEquals(0f, ExplosionMath.rawDamage(CENTER.add(0.0, -13.0, 0.0), CENTER, 0f));
        assertEquals(0f, ExplosionMath.rawUpperBound(CENTER.add(8.0, 8.0, 8.0), CENTER));
        // dist == 1.0 is not skipped by vanilla's dist > 1.0 test: pow = 0, so 1 damage
        assertEquals(1.0, ExplosionMath.distanceFraction(CENTER.add(12.0, 0.0, 0.0), CENTER), 0.0);
        assertEquals(1f, ExplosionMath.rawDamage(CENTER.add(12.0, 0.0, 0.0), CENTER, 1f));
    }

    @Test
    void distanceFractionDoesNotDependOnTheSubtractionOrder() {
        Random random = new Random(42);
        for (int i = 0; i < 1000; i++) {
            Vec3 a = randomPoint(random, 100.0);
            Vec3 b = randomPoint(random, 100.0);
            assertEquals(a.distanceToSqr(b), b.distanceToSqr(a), 0.0);
            assertEquals(ExplosionMath.distanceFraction(a, b), ExplosionMath.distanceFraction(b, a), 0.0);
        }
    }

    @Test
    void crystalCenterSitsOnTheBlockAboveTheObsidian() {
        assertEquals(new Vec3(1.5, 3.0, 3.5), ExplosionMath.crystalCenter(new BlockPos(1, 2, 3)));
        assertEquals(new Vec3(-3.5, -60.0, 0.5), ExplosionMath.crystalCenter(new BlockPos(-4, -61, 0)));
    }

    @Test
    void crystalBoxIsTheTwoByTwoByTwoEntityBoxAroundTheCenter() {
        AABB box = ExplosionMath.crystalBox(new BlockPos(1, 2, 3));
        assertEquals(0.5, box.minX, 0.0);
        assertEquals(3.0, box.minY, 0.0);
        assertEquals(2.5, box.minZ, 0.0);
        assertEquals(2.5, box.maxX, 0.0);
        assertEquals(5.0, box.maxY, 0.0);
        assertEquals(4.5, box.maxZ, 0.0);
        assertEquals(2.0, box.maxX - box.minX, 0.0);
        assertEquals(2.0, box.maxY - box.minY, 0.0);
        assertEquals(2.0, box.maxZ - box.minZ, 0.0);
    }

    @Test
    void rawUpperBoundDominatesEveryExposure() {
        Random random = new Random(20260905L);
        for (int i = 0; i < 2000; i++) {
            Vec3 feet = randomPoint(random, 13.0);
            float exposure = random.nextFloat();
            float raw = ExplosionMath.rawDamage(feet, CENTER, exposure);
            float bound = ExplosionMath.rawUpperBound(feet, CENTER);
            assertTrue(bound >= raw, "bound " + bound + " < raw " + raw + " at " + feet + " with exposure " + exposure);
            assertTrue(raw >= 0f, "negative raw " + raw + " at " + feet);
            assertTrue(bound <= 85f, "bound " + bound + " above 85 at " + feet);
        }
    }
}
