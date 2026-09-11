package cc.aerial.client.features.impl.combat.crystalaura.calc;

import net.minecraft.world.Difficulty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VictimPipelineTest {
    private static final float EPS = 1e-4f;
    private static final Vec3 FEET = new Vec3(3.5, 64.0, 0.5);
    private static final AABB BOX = new AABB(3.2, 64.0, 0.2, 3.8, 65.8, 0.8);
    private static final Vec3 SOURCE = new Vec3(0.5, 64.0, 0.5);

    /** {@code calculateViewVector(0, 0)} is {@code (0, 0, 1)}: the victim looks towards +Z. */
    private static final Vec3 LOOK_POS_Z = new Vec3(0.0, 0.0, 1.0);
    private static final Difficulty[] DIFFICULTIES =
            {Difficulty.PEACEFUL, Difficulty.EASY, Difficulty.NORMAL, Difficulty.HARD};

    /**
     * Mutable stand-in for the Kotlin data class copy() the original tests use: start from
     * {@link #base()} and override only what the case is about.
     */
    private static final class V {
        boolean isPlayer;
        boolean skip;
        int armor;
        float toughness;
        int resistanceAmplifier = -1;
        float protectionPoints;
        float absorption;
        float health = 20f;
        BlockingState blocking;
        int invulnerableTime;
        Float lastHurt;
        Difficulty difficulty = Difficulty.NORMAL;

        V isPlayer(boolean v) { isPlayer = v; return this; }
        V skip(boolean v) { skip = v; return this; }
        V armor(int v) { armor = v; return this; }
        V toughness(float v) { toughness = v; return this; }
        V resistance(int v) { resistanceAmplifier = v; return this; }
        V protection(float v) { protectionPoints = v; return this; }
        V absorption(float v) { absorption = v; return this; }
        V health(float v) { health = v; return this; }
        V blocking(BlockingState v) { blocking = v; return this; }
        V invulnerableTime(int v) { invulnerableTime = v; return this; }
        V lastHurt(Float v) { lastHurt = v; return this; }
        V difficulty(Difficulty v) { difficulty = v; return this; }

        VictimState build() {
            return new VictimState(FEET, BOX, isPlayer, skip, armor, toughness, resistanceAmplifier,
                    protectionPoints, absorption, health, blocking, invulnerableTime, lastHurt, difficulty);
        }
    }

    /** Non-player, NORMAL, no armor, no effects, no shield, no i-frames, 20 health. */
    private static V base() {
        return new V();
    }

    private static DamageResult pipeline(VictimState victim, float raw) {
        return pipeline(victim, raw, true, SOURCE);
    }

    private static DamageResult pipeline(VictimState victim, float raw, boolean respectIFrames, Vec3 source) {
        return VictimPipeline.apply(victim, raw, 1f, source, respectIFrames);
    }

    private static BlockingState shield(BlockReduction... reductions) {
        return new BlockingState(List.of(reductions), false, LOOK_POS_Z, FEET);
    }

    private static BlockingState bypassedShield(BlockReduction... reductions) {
        return new BlockingState(List.of(reductions), true, LOOK_POS_Z, FEET);
    }

    /** A source {@code distance} blocks away, rotated {@code degrees} around Y off the +Z view direction. */
    private static Vec3 sourceAtYaw(double degrees) {
        double radians = Math.toRadians(degrees);
        return FEET.add(Math.sin(radians) * 5.0, 0.0, Math.cos(radians) * 5.0);
    }

    @Test
    void skippedVictimsYieldTheZeroResultButKeepExposureAndRaw() {
        DamageResult result = pipeline(base().skip(true).build(), 30f);
        assertEquals(1f, result.exposure());
        assertEquals(30f, result.raw());
        assertEquals(0f, result.afterDifficulty());
        assertEquals(0f, result.effective());
        assertEquals(0f, result.healthLoss());
        assertFalse(result.iFrameGated());
        assertEquals(DamageResult.none(1f, 30f), result);
    }

    @Test
    void difficultyScalesPlayers() {
        assertEquals(6f, pipeline(base().isPlayer(true).difficulty(Difficulty.EASY).build(), 10f).afterDifficulty());
        assertEquals(10f, pipeline(base().isPlayer(true).difficulty(Difficulty.NORMAL).build(), 10f).afterDifficulty());
        assertEquals(15f, pipeline(base().isPlayer(true).difficulty(Difficulty.HARD).build(), 10f).afterDifficulty());
        assertEquals(15f, pipeline(base().isPlayer(true).difficulty(Difficulty.HARD).build(), 10f).effective());
        DamageResult peaceful =
                pipeline(base().isPlayer(true).difficulty(Difficulty.PEACEFUL).build(), 10f);
        assertEquals(0f, peaceful.afterDifficulty());
        assertEquals(0f, peaceful.afterBlocking());
        assertEquals(0f, peaceful.dealt());
        assertEquals(0f, peaceful.effective());
        assertEquals(0f, peaceful.healthLoss());
        assertFalse(peaceful.iFrameGated());
    }

    @Test
    void easyKeepsSmallDamageUnchanged() {
        // Math.min(damage / 2 + 1, damage) with damage = 1.5 gives min(1.75, 1.5)
        assertEquals(1.5f, pipeline(base().isPlayer(true).difficulty(Difficulty.EASY).build(), 1.5f).afterDifficulty());
        assertEquals(2f, pipeline(base().isPlayer(true).difficulty(Difficulty.EASY).build(), 2f).afterDifficulty());
        assertEquals(43.5f, pipeline(base().isPlayer(true).difficulty(Difficulty.EASY).build(), 85f).afterDifficulty());
    }

    @Test
    void difficultyNeverScalesNonPlayers() {
        for (Difficulty difficulty : DIFFICULTIES) {
            DamageResult result = pipeline(base().difficulty(difficulty).build(), 10f);
            assertEquals(10f, result.afterDifficulty(), "difficulty " + difficulty);
            assertEquals(10f, result.effective(), "difficulty " + difficulty);
        }
    }

    @Test
    void shieldInTheArcBlocksEverything() {
        VictimState victim = base().blocking(shield(new BlockReduction(90f, true, 0f, 1f))).build();
        DamageResult result = pipeline(victim, 10f, true, sourceAtYaw(0.0));
        assertEquals(10f, result.afterDifficulty());
        assertEquals(0f, result.afterBlocking());
        assertEquals(0f, result.dealt());
        assertEquals(0f, result.effective());
        assertFalse(result.iFrameGated());
    }

    @Test
    void shieldOutsideTheArcBlocksNothing() {
        VictimState victim = base().blocking(shield(new BlockReduction(90f, true, 0f, 1f))).build();
        assertEquals(10f, pipeline(victim, 10f, true, sourceAtYaw(180.0)).afterBlocking());
        assertEquals(10f, pipeline(victim, 10f, true, sourceAtYaw(120.0)).afterBlocking());
        assertEquals(10f, pipeline(victim, 10f, true, sourceAtYaw(-120.0)).afterBlocking());
    }

    @Test
    void blockingAngleIsComparedAgainstTheHorizontalArc() {
        VictimState victim = base().blocking(shield(new BlockReduction(45f, true, 0f, 1f))).build();
        assertEquals(0f, pipeline(victim, 10f, true, sourceAtYaw(30.0)).afterBlocking());
        assertEquals(0f, pipeline(victim, 10f, true, sourceAtYaw(-30.0)).afterBlocking());
        assertEquals(10f, pipeline(victim, 10f, true, sourceAtYaw(60.0)).afterBlocking());
        assertEquals(10f, pipeline(victim, 10f, true, sourceAtYaw(-60.0)).afterBlocking());
    }

    @Test
    void sourceStraightAboveCountsAsANinetyDegreeAngle() {
        // The flattened direction is ZERO, so the dot is 0 and acos(0) is PI / 2, compared against
        // the float arc widened to double.
        Vec3 above = FEET.add(0.0, 3.0, 0.0);
        VictimState wide = base().blocking(shield(new BlockReduction(90f, true, 0f, 1f))).build();
        assertEquals(0f, pipeline(wide, 10f, true, above).afterBlocking());
        VictimState narrow = base().blocking(shield(new BlockReduction(89f, true, 0f, 1f))).build();
        assertEquals(10f, pipeline(narrow, 10f, true, above).afterBlocking());
    }

    @Test
    void partialReductionsAreBasePlusFactorTimesDamage() {
        VictimState victim = base().blocking(shield(new BlockReduction(90f, true, 2f, 0.5f))).build();
        assertEquals(3f, pipeline(victim, 10f, true, sourceAtYaw(0.0)).afterBlocking(), EPS);
    }

    @Test
    void summedReductionsAreClampedToTheDamage() {
        VictimState victim = base().blocking(shield(
                new BlockReduction(90f, true, 0f, 0.8f), new BlockReduction(90f, true, 0f, 0.8f))).build();
        assertEquals(0f, pipeline(victim, 10f, true, sourceAtYaw(0.0)).afterBlocking());
    }

    @Test
    void bypassedShieldsAndNonMatchingReductionTypesBlockNothing() {
        VictimState bypassed = base().blocking(bypassedShield(new BlockReduction(90f, true, 0f, 1f))).build();
        assertEquals(10f, pipeline(bypassed, 10f, true, sourceAtYaw(0.0)).afterBlocking());
        VictimState wrongType = base().blocking(shield(new BlockReduction(90f, false, 0f, 1f))).build();
        assertEquals(10f, pipeline(wrongType, 10f, true, sourceAtYaw(0.0)).afterBlocking());
    }

    @Test
    void armor20WithToughness8Reduces40To24() {
        // toughness = 2 + 8 / 4 = 4; realArmor = clamp(20 - 40 / 4, 4, 20) = 10; 40 * (1 - 10 / 25) = 24
        DamageResult result = pipeline(base().armor(20).toughness(8f).build(), 40f);
        assertEquals(40f, result.dealt());
        assertEquals(24f, result.afterArmor(), EPS);
        assertEquals(24f, result.effective(), EPS);
        assertEquals(24f, result.healthLoss(), EPS);
    }

    @Test
    void armorFloorIsAFifthOfTheArmorValue() {
        // toughness = 2; realArmor = clamp(20 - 40 / 2, 4, 20) = 4; 40 * (1 - 4 / 25) = 33.6
        assertEquals(33.6f, pipeline(base().armor(20).build(), 40f).afterArmor(), EPS);
        // small hits use the full armor: realArmor = clamp(20 - 2 / 2, 4, 20) = 19; 2 * (1 - 19 / 25) = 0.48
        assertEquals(0.48f, pipeline(base().armor(20).build(), 2f).afterArmor(), EPS);
    }

    @Test
    void resistanceRemovesAFifthPerLevelAndCapsAtZero() {
        float[] expected = {16f, 12f, 8f, 4f, 0f};
        for (int amplifier = 0; amplifier <= 4; amplifier++) {
            DamageResult result = pipeline(base().resistance(amplifier).build(), 20f);
            assertEquals(expected[amplifier], result.afterMagic(), EPS, "amplifier " + amplifier);
        }
        assertEquals(0f, pipeline(base().resistance(4).build(), 20f).healthLoss());
        assertEquals(20f, pipeline(base().resistance(-1).build(), 20f).afterMagic());
    }

    @Test
    void protectionPointsScaleByOneTwentyFifthUpToTwenty() {
        assertEquals(8.4f, pipeline(base().protection(4f).build(), 10f).afterMagic(), EPS);
        assertEquals(2f, pipeline(base().protection(25f).build(), 10f).afterMagic(), EPS);
        assertEquals(2f, pipeline(base().protection(20f).build(), 10f).afterMagic(), EPS);
        assertEquals(10f, pipeline(base().protection(0f).build(), 10f).afterMagic());
    }

    @Test
    void resistanceAppliesBeforeProtection() {
        // 20 through resistance I gives 16, then protection 4 gives 16 * 0.84 = 13.44
        DamageResult result = pipeline(base().resistance(0).protection(4f).build(), 20f);
        assertEquals(13.44f, result.afterMagic(), EPS);
    }

    @Test
    void absorptionIsConsumedBeforeHealth() {
        DamageResult partial = pipeline(base().absorption(4f).build(), 10f);
        assertEquals(6f, partial.healthLoss(), EPS);
        assertEquals(4f, partial.absorbed(), EPS);
        assertEquals(10f, partial.effective(), EPS);
        DamageResult full = pipeline(base().absorption(15f).build(), 10f);
        assertEquals(0f, full.healthLoss());
        assertEquals(10f, full.absorbed(), EPS);
        assertEquals(10f, full.effective(), EPS);
    }

    @Test
    void insideTheWindowOnlyTheExcessOverLastHurtIsDealt() {
        DamageResult result = pipeline(base().invulnerableTime(15).lastHurt(7f).build(), 10f);
        assertFalse(result.iFrameGated());
        assertEquals(10f, result.afterBlocking());
        assertEquals(3f, result.dealt(), EPS);
        assertEquals(3f, result.effective(), EPS);
        assertEquals(3f, result.healthLoss(), EPS);
    }

    @Test
    void damageNotExceedingLastHurtInsideTheWindowIsGated() {
        for (float lastHurt : new float[]{10f, 12f}) {
            DamageResult result = pipeline(base().invulnerableTime(15).lastHurt(lastHurt).build(), 10f);
            assertTrue(result.iFrameGated(), "lastHurt " + lastHurt);
            assertEquals(10f, result.afterDifficulty());
            assertEquals(10f, result.afterBlocking());
            assertEquals(0f, result.dealt());
            assertEquals(0f, result.afterArmor());
            assertEquals(0f, result.afterMagic());
            assertEquals(0f, result.absorbed());
            assertEquals(0f, result.healthLoss());
        }
    }

    @Test
    void ignoringIFramesDealsTheFullDamage() {
        VictimState victim = base().invulnerableTime(15).lastHurt(12f).build();
        DamageResult result = pipeline(victim, 10f, false, SOURCE);
        assertFalse(result.iFrameGated());
        assertEquals(10f, result.dealt());
        assertEquals(10f, result.effective());
    }

    @Test
    void windowNeedsInvulnerableTimeStrictlyAboveTenAndUnknownLastHurtCountsAsZero() {
        assertEquals(5f, pipeline(base().invulnerableTime(10).lastHurt(7f).build(), 5f).dealt());
        assertEquals(5f, pipeline(base().invulnerableTime(0).lastHurt(7f).build(), 5f).dealt());
        assertEquals(10f, pipeline(base().invulnerableTime(15).lastHurt(null).build(), 10f).dealt());
        assertEquals(10f, pipeline(base().invulnerableTime(11).lastHurt(0f).build(), 10f).dealt());
    }

    @Test
    void difficultyAppliesBeforeTheIFrameGateAndArmorSeesTheDelta() {
        VictimState easy = base().isPlayer(true).difficulty(Difficulty.EASY)
                .invulnerableTime(15).lastHurt(7f).build();
        assertTrue(pipeline(easy, 10f).iFrameGated()); // 10 scales to 6, which is <= 7
        // toughness 2; dealt = 15 - 5 = 10; realArmor = clamp(20 - 10 / 2, 4, 20) = 15; 10 * (1 - 15 / 25) = 4
        VictimState armored = base().armor(20).invulnerableTime(15).lastHurt(5f).build();
        assertEquals(4f, pipeline(armored, 15f).effective(), EPS);
    }

    @Test
    void shieldReducesTheValueComparedAgainstLastHurt() {
        // 10 with 7 blocked (base 2 + 0.5 * 10) leaves 3, which is <= lastHurt 3, so it is gated
        V shielded = base().blocking(shield(new BlockReduction(90f, true, 2f, 0.5f)))
                .invulnerableTime(15).lastHurt(3f);
        assertTrue(pipeline(shielded.build(), 10f, true, sourceAtYaw(0.0)).iFrameGated());
        assertFalse(pipeline(shielded.build(), 12f, true, sourceAtYaw(0.0)).iFrameGated());
    }

    @Test
    void lethalityComparesHealthLossAgainstHealth() {
        VictimState fragile = base().health(5f).build();
        assertTrue(pipeline(fragile, 10f).isLethalFor(fragile));
        assertFalse(pipeline(base().build(), 10f).isLethalFor(base().build()));
        VictimState absorbing = base().health(5f).absorption(6f).build();
        assertFalse(pipeline(absorbing, 10f).isLethalFor(absorbing));
        VictimState exact = base().health(10f).build();
        assertTrue(pipeline(exact, 10f).isLethalFor(exact));
    }

    @Test
    void effectiveDamageIsMonotoneInTheRawDamage() {
        Random random = new Random(20260905L);
        for (int i = 0; i < 500; i++) {
            VictimState victim = randomVictim(random);
            Vec3 source = randomSource(random);
            boolean respect = random.nextBoolean();
            float raw1 = random.nextFloat() * 85f;
            float raw2 = raw1 + random.nextFloat() * (85f - raw1);
            DamageResult low = pipeline(victim, raw1, respect, source);
            DamageResult high = pipeline(victim, raw2, respect, source);
            assertTrue(low.effective() <= high.effective() + EPS,
                    "raw " + raw1 + " gave " + low.effective()
                            + " but raw " + raw2 + " gave " + high.effective() + " for " + victim);
            assertTrue(low.healthLoss() <= high.healthLoss() + EPS,
                    "raw " + raw1 + " gave " + low.healthLoss()
                            + " but raw " + raw2 + " gave " + high.healthLoss() + " for " + victim);
        }
    }

    @Test
    void noStageAmplifiesTheDamageHandedToIt() {
        Random random = new Random(1337L);
        for (int i = 0; i < 500; i++) {
            VictimState victim = randomVictim(random);
            DamageResult result =
                    pipeline(victim, random.nextFloat() * 85f, random.nextBoolean(), randomSource(random));
            assertTrue(result.afterBlocking() <= Math.max(result.afterDifficulty(), 0f) + EPS, "blocking " + result);
            assertTrue(result.dealt() <= result.afterBlocking() + EPS, "i-frames " + result);
            assertTrue(result.afterArmor() <= result.dealt() + EPS, "armor " + result);
            assertTrue(result.afterMagic() <= result.afterArmor() + EPS, "magic " + result);
            assertEquals(result.afterMagic(), result.absorbed() + result.healthLoss(), EPS, "absorption " + result);
            assertTrue(result.healthLoss() >= 0f && result.absorbed() >= 0f, "signs " + result);
            assertTrue(result.absorbed() <= victim.absorption() + EPS, "absorbed more than available " + result);
        }
    }

    private static VictimState randomVictim(Random random) {
        return base()
                .isPlayer(random.nextBoolean())
                .skip(random.nextInt(10) == 0)
                .armor(random.nextInt(31))
                .toughness(random.nextFloat() * 12f)
                .resistance(random.nextInt(6) - 1)
                .protection(random.nextFloat() * 30f)
                .absorption(random.nextFloat() * 16f)
                .health(1f + random.nextFloat() * 19f)
                .blocking(random.nextInt(3) == 0 ? randomShield(random) : null)
                .invulnerableTime(random.nextInt(21))
                .lastHurt(random.nextBoolean() ? null : random.nextFloat() * 30f)
                .difficulty(DIFFICULTIES[random.nextInt(DIFFICULTIES.length)])
                .build();
    }

    private static BlockingState randomShield(Random random) {
        List<BlockReduction> reductions = new ArrayList<>();
        int count = 1 + random.nextInt(2);
        for (int i = 0; i < count; i++) {
            reductions.add(new BlockReduction(
                    random.nextFloat() * 180f,
                    random.nextInt(4) != 0,
                    random.nextFloat() * 5f,
                    random.nextFloat()));
        }
        double yaw = Math.toRadians(random.nextDouble() * 360.0);
        return new BlockingState(reductions, random.nextInt(4) == 0,
                new Vec3(Math.sin(yaw), 0.0, Math.cos(yaw)), FEET);
    }

    private static Vec3 randomSource(Random random) {
        return FEET.add(
                random.nextDouble() * 12.0 - 6.0,
                random.nextDouble() * 6.0 - 3.0,
                random.nextDouble() * 12.0 - 6.0);
    }
}
