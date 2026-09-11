package cc.aerial.client.features.impl.combat.crystalaura.state;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrystalLedgerTest {
    /** Block above the clicked obsidian; a crystal placed there spawns at (1.5, 64.0, -2.5). */
    private static final BlockPos ABOVE = new BlockPos(1, 64, -3);

    private static CrystalLedger ledgerAt(int tick) {
        return ledgerAt(tick, 10, 10);
    }

    private static CrystalLedger ledgerAt(int tick, int attackTimeout, int placeTimeout) {
        CrystalLedger ledger = new CrystalLedger();
        ledger.onTickStart(tick, attackTimeout, placeTimeout);
        return ledger;
    }

    // ------------------------------------------------------------------ attack and chain marks

    @Test
    void markAttackedMarksOnlyThatIdAsAttacked() {
        CrystalLedger ledger = ledgerAt(0);
        ledger.markAttacked(7, Map.of());
        assertTrue(ledger.isAttacked(7));
        assertFalse(ledger.isAttacked(8));
        assertFalse(ledger.isExpectedRemoved(7));
    }

    @Test
    void attackedMarksExpireOnceOlderThanTheAttackTimeout() {
        CrystalLedger ledger = ledgerAt(0, 2, 10);
        ledger.markAttacked(7, Map.of());
        ledger.onTickStart(2, 2, 10);
        assertTrue(ledger.isAttacked(7));
        ledger.onTickStart(3, 2, 10);
        assertFalse(ledger.isAttacked(7));
    }

    @Test
    void aZeroAttackTimeoutExpiresOnTheNextTick() {
        CrystalLedger ledger = ledgerAt(5, 0, 10);
        ledger.markAttacked(7, Map.of());
        assertTrue(ledger.isAttacked(7));
        ledger.onTickStart(6, 0, 0);
        assertFalse(ledger.isAttacked(7));
    }

    @Test
    void onRemovedClearsAttackedExpectedRemovedAndOwn() {
        CrystalLedger ledger = ledgerAt(0);
        ledger.markAttacked(7, Map.of());
        ledger.markExpectedRemoved(7);
        ledger.markPlaced(ABOVE, null);
        ledger.onSpawn(7, true, 1.5, 64.0, -2.5);
        assertTrue(ledger.isOwn(7));
        ledger.onRemoved(7);
        assertFalse(ledger.isAttacked(7));
        assertFalse(ledger.isExpectedRemoved(7));
        assertFalse(ledger.isOwn(7));
    }

    @Test
    void markExpectedRemovedMarksTheChainCrystalAndExpiresWithTheAttackTimeout() {
        CrystalLedger ledger = ledgerAt(0, 1, 10);
        ledger.markExpectedRemoved(9);
        assertTrue(ledger.isExpectedRemoved(9));
        assertFalse(ledger.isAttacked(9));
        ledger.onTickStart(1, 1, 10);
        assertTrue(ledger.isExpectedRemoved(9));
        ledger.onTickStart(2, 1, 10);
        assertFalse(ledger.isExpectedRemoved(9));
    }

    @Test
    void attackedMarkSurvivesTheSpawnOfAPredictedId() {
        CrystalLedger ledger = ledgerAt(0);
        ledger.markPlaced(ABOVE, 42);
        ledger.markAttacked(42, Map.of());
        ledger.onSpawn(42, true, 1.5, 64.0, -2.5);
        assertTrue(ledger.isAttacked(42));
        assertTrue(ledger.isOwn(42));
    }

    // ------------------------------------------------------------------ placements and spawns

    @Test
    void crystalSpawnAtTheExactPlacementPositionMarksOwnAndClearsPending() {
        CrystalLedger ledger = ledgerAt(0);
        ledger.markPlaced(ABOVE, null);
        assertTrue(ledger.isPending(ABOVE));
        ledger.onSpawn(100, true, 1.5, 64.0, -2.5);
        assertTrue(ledger.isOwn(100));
        assertFalse(ledger.isPending(ABOVE));
        assertNull(ledger.pendingPlacement());
    }

    @Test
    void crystalSpawnAtADifferentPositionDoesNotMatch() {
        CrystalLedger ledger = ledgerAt(0);
        ledger.markPlaced(ABOVE, null);
        ledger.onSpawn(100, true, 1.5, 65.0, -2.5);
        ledger.onSpawn(101, true, 1.5, 64.0, -2.5000001);
        assertFalse(ledger.isOwn(100));
        assertFalse(ledger.isOwn(101));
        assertTrue(ledger.isPending(ABOVE));
    }

    @Test
    void nonCrystalSpawnAtThePlacementPositionDoesNotMatch() {
        CrystalLedger ledger = ledgerAt(0);
        ledger.markPlaced(ABOVE, 100);
        ledger.onSpawn(100, false, 1.5, 64.0, -2.5);
        assertFalse(ledger.isOwn(100));
        assertTrue(ledger.isPending(ABOVE));
    }

    @Test
    void crystalSpawnWithThePredictedIdMarksOwn() {
        CrystalLedger ledger = ledgerAt(0);
        ledger.markPlaced(ABOVE, 42);
        ledger.onSpawn(42, true, 0.0, 0.0, 0.0);
        assertTrue(ledger.isOwn(42));
        assertFalse(ledger.isPending(ABOVE));
    }

    @Test
    void spawnReusingAnOwnIdWithoutAnObservedRemovalClearsTheStaleOwnMark() {
        CrystalLedger ledger = ledgerAt(0);
        ledger.markPlaced(ABOVE, null);
        ledger.onSpawn(100, true, 1.5, 64.0, -2.5);
        assertTrue(ledger.isOwn(100));
        ledger.onSpawn(100, true, 9.5, 70.0, 9.5);
        assertFalse(ledger.isOwn(100));
    }

    @Test
    void anOwnMarkSurvivesUpToTheOwnTimeout() {
        CrystalLedger ledger = ledgerAt(0);
        ledger.markPlaced(ABOVE, null);
        ledger.onSpawn(100, true, 1.5, 64.0, -2.5);
        ledger.onTickStart(600, 10, 10);
        assertTrue(ledger.isOwn(100));
    }

    @Test
    void anOwnMarkExpiresOnceOlderThanTheOwnTimeout() {
        CrystalLedger ledger = ledgerAt(0);
        ledger.markPlaced(ABOVE, null);
        ledger.onSpawn(100, true, 1.5, 64.0, -2.5);
        ledger.onTickStart(601, 10, 10);
        assertFalse(ledger.isOwn(100));
    }

    @Test
    void theOwnTimeoutIsMeasuredFromTheSpawnNotFromThePlacement() {
        CrystalLedger ledger = ledgerAt(0);
        ledger.markPlaced(ABOVE, null);
        ledger.onTickStart(300, 1000, 1000);
        ledger.onSpawn(100, true, 1.5, 64.0, -2.5);
        ledger.onTickStart(900, 1000, 1000);
        assertTrue(ledger.isOwn(100));
        ledger.onTickStart(901, 1000, 1000);
        assertFalse(ledger.isOwn(100));
    }

    @Test
    void pendingPlacementsExpireAfterThePlaceTimeout() {
        CrystalLedger ledger = ledgerAt(0, 10, 3);
        ledger.markPlaced(ABOVE, null);
        ledger.onTickStart(3, 10, 3);
        assertTrue(ledger.isPending(ABOVE));
        ledger.onTickStart(4, 10, 3);
        assertFalse(ledger.isPending(ABOVE));
        assertNull(ledger.pendingPlacement());
    }

    @Test
    void pendingPlacementReturnsTheNewestPlacement() {
        CrystalLedger ledger = ledgerAt(1);
        BlockPos other = new BlockPos(5, 70, 5);
        ledger.markPlaced(other, 40);
        ledger.onTickStart(2, 10, 10);
        ledger.markPlaced(ABOVE, 41);
        assertEquals(new PendingPlacement(ABOVE, 2, 41), ledger.pendingPlacement());
        ledger.onTickStart(3, 10, 10);
        ledger.markPlaced(other, null);
        assertEquals(new PendingPlacement(other, 3, null), ledger.pendingPlacement());
    }

    @Test
    void onRemovedForgetsTheRemovedIdAsAPredictionButKeepsThePlacementPending() {
        CrystalLedger ledger = ledgerAt(0);
        ledger.markPlaced(ABOVE, 42);
        ledger.onRemoved(42);
        assertEquals(new PendingPlacement(ABOVE, 0, null), ledger.pendingPlacement());
        ledger.onSpawn(42, true, 0.0, 0.0, 0.0);
        assertFalse(ledger.isOwn(42));
        assertTrue(ledger.isPending(ABOVE));
    }

    // ------------------------------------------------------------------ id prediction

    @Test
    void predictNextIdIsNullForFewerThanThreeSpawns() {
        CrystalLedger ledger = ledgerAt(0);
        assertNull(ledger.predictNextId());
        assertEquals(-1, ledger.maxSeenEntityId());
        ledger.onSpawn(10, false, 0.0, 0.0, 0.0);
        ledger.onSpawn(11, false, 0.0, 0.0, 0.0);
        assertNull(ledger.predictNextId());
        assertEquals(11, ledger.maxSeenEntityId());
    }

    @Test
    void predictNextIdIsNullForNonConsecutiveIds() {
        CrystalLedger ledger = ledgerAt(0);
        ledger.onSpawn(10, false, 0.0, 0.0, 0.0);
        ledger.onSpawn(11, false, 0.0, 0.0, 0.0);
        ledger.onSpawn(13, false, 0.0, 0.0, 0.0);
        assertNull(ledger.predictNextId());
    }

    @Test
    void predictNextIdIsMaxPlusOneForConsecutiveIds() {
        CrystalLedger ledger = ledgerAt(0);
        ledger.onSpawn(10, false, 0.0, 0.0, 0.0);
        ledger.onSpawn(11, true, 0.0, 0.0, 0.0);
        ledger.onSpawn(12, false, 0.0, 0.0, 0.0);
        assertEquals(13, ledger.predictNextId());
        ledger.onSpawn(20, false, 0.0, 0.0, 0.0);
        assertNull(ledger.predictNextId());
        ledger.onSpawn(21, false, 0.0, 0.0, 0.0);
        ledger.onSpawn(22, false, 0.0, 0.0, 0.0);
        assertEquals(23, ledger.predictNextId());
    }

    // ------------------------------------------------------------------ lastHurt estimates

    @Test
    void markAttackedStoresPositiveLastHurtPredictions() {
        CrystalLedger ledger = ledgerAt(0);
        ledger.markAttacked(7, Map.of(3, 8.5f, 4, 0f, 5, -1f));
        assertEquals(8.5f, ledger.lastHurtEstimate(3));
        assertNull(ledger.lastHurtEstimate(4));
        assertNull(ledger.lastHurtEstimate(5));
        assertNull(ledger.lastHurtEstimate(6));
    }

    @Test
    void damageCausedByUsThroughTheAttackedCrystalKeepsTheEstimate() {
        CrystalLedger ledger = ledgerAt(0);
        ledger.markAttacked(7, Map.of(3, 8.5f));
        ledger.onDamageEvent(3, 1, 7, 1);
        assertEquals(8.5f, ledger.lastHurtEstimate(3));
    }

    @Test
    void damageThroughAnExpectedRemovedCrystalKeepsTheEstimate() {
        CrystalLedger ledger = ledgerAt(0);
        ledger.markAttacked(7, Map.of(3, 8.5f));
        ledger.markExpectedRemoved(8);
        ledger.onDamageEvent(3, 1, 8, 1);
        assertEquals(8.5f, ledger.lastHurtEstimate(3));
    }

    @Test
    void damageThroughOurCrystalRemovedRightBeforeTheEventKeepsTheEstimate() {
        CrystalLedger ledger = ledgerAt(0);
        ledger.markAttacked(7, Map.of(3, 8.5f));
        ledger.onRemoved(7);
        assertFalse(ledger.isAttacked(7));
        ledger.onDamageEvent(3, 1, 7, 1);
        assertEquals(8.5f, ledger.lastHurtEstimate(3));
    }

    @Test
    void foreignDamageDropsTheEstimate() {
        CrystalLedger ledger = ledgerAt(0);
        ledger.markAttacked(7, Map.of(3, 8.5f, 4, 6f, 5, 6f));
        ledger.onDamageEvent(3, 2, 7, 1);
        ledger.onDamageEvent(4, 1, 1, 1);
        ledger.onDamageEvent(5, -1, -1, 1);
        assertNull(ledger.lastHurtEstimate(3));
        assertNull(ledger.lastHurtEstimate(4));
        assertNull(ledger.lastHurtEstimate(5));
    }

    @Test
    void confirmedDamageRefreshesTheEstimateAge() {
        CrystalLedger ledger = ledgerAt(0);
        ledger.markAttacked(7, Map.of(3, 8.5f));
        ledger.onTickStart(15, 100, 100);
        ledger.onDamageEvent(3, 1, 7, 1);
        ledger.onTickStart(35, 100, 100);
        assertEquals(8.5f, ledger.lastHurtEstimate(3));
        ledger.onTickStart(36, 100, 100);
        assertNull(ledger.lastHurtEstimate(3));
    }

    @Test
    void lastHurtEstimateExpiresTwentyTicksAfterItsLastUpdate() {
        CrystalLedger ledger = ledgerAt(0);
        ledger.markAttacked(7, Map.of(3, 8.5f));
        ledger.onTickStart(20, 100, 100);
        assertEquals(8.5f, ledger.lastHurtEstimate(3));
        ledger.onTickStart(21, 100, 100);
        assertNull(ledger.lastHurtEstimate(3));
    }

    @Test
    void aNewerPredictionOverwritesTheEstimate() {
        CrystalLedger ledger = ledgerAt(0);
        ledger.markAttacked(7, Map.of(3, 8.5f));
        ledger.onTickStart(1, 10, 10);
        ledger.markAttacked(8, Map.of(3, 12f));
        assertEquals(12f, ledger.lastHurtEstimate(3));
    }

    // ------------------------------------------------------------------ reset

    @Test
    void resetClearsAllState() {
        CrystalLedger ledger = ledgerAt(5);
        ledger.markAttacked(7, Map.of(3, 8.5f));
        ledger.markExpectedRemoved(8);
        ledger.markPlaced(ABOVE, 42);
        ledger.onSpawn(10, false, 0.0, 0.0, 0.0);
        ledger.onSpawn(11, false, 0.0, 0.0, 0.0);
        ledger.onSpawn(12, true, 1.5, 64.0, -2.5);
        ledger.reset();
        assertEquals(0, ledger.tick());
        assertEquals(-1, ledger.maxSeenEntityId());
        assertFalse(ledger.isAttacked(7));
        assertFalse(ledger.isExpectedRemoved(8));
        assertFalse(ledger.isPending(ABOVE));
        assertFalse(ledger.isOwn(12));
        assertNull(ledger.lastHurtEstimate(3));
        assertNull(ledger.predictNextId());
        assertNull(ledger.pendingPlacement());
    }

    @Test
    void resetClearsTheSpawnWindowSoIdPredictionStartsOver() {
        CrystalLedger ledger = ledgerAt(0);
        ledger.onSpawn(10, false, 0.0, 0.0, 0.0);
        ledger.onSpawn(11, false, 0.0, 0.0, 0.0);
        ledger.onSpawn(12, false, 0.0, 0.0, 0.0);
        assertEquals(13, ledger.predictNextId());
        ledger.reset();
        ledger.onSpawn(50, false, 0.0, 0.0, 0.0);
        ledger.onSpawn(51, false, 0.0, 0.0, 0.0);
        assertNull(ledger.predictNextId());
        ledger.onSpawn(52, false, 0.0, 0.0, 0.0);
        assertEquals(53, ledger.predictNextId());
    }

    @Test
    void resetClearsOwnMarksThatNoRemovalWasObservedFor() {
        CrystalLedger ledger = ledgerAt(0);
        ledger.markPlaced(ABOVE, null);
        ledger.onSpawn(100, true, 1.5, 64.0, -2.5);
        assertTrue(ledger.isOwn(100));
        ledger.reset();
        assertFalse(ledger.isOwn(100));
        ledger.onTickStart(0, 10, 10);
        assertFalse(ledger.isOwn(100));
    }
}
