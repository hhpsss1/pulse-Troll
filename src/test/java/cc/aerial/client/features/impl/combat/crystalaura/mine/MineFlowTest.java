package cc.aerial.client.features.impl.combat.crystalaura.mine;

import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The state machine of {@link MineJob}, driven headlessly through {@link MineFlow} — which is why the flow takes
 * its clock as a constructor argument and learns everything else through its tick and the started / finished /
 * swallowed / aborted observations. The shell around it (rays, packets, the mining claim) needs a running client
 * and is not exercised here.
 *
 * <p>The clock is a plain field so a test can hold it still — a real client tick is 50 ms, but after a stall
 * several ticks run inside one frame, which is exactly the case the wall-clock floor of the settle window exists
 * for.
 */
class MineFlowTest {
    /** The MaxTicks default of the setting — large enough that only the deadline tests reach it. */
    private static final int MAX_TICKS = 40;

    /** A deadline small enough to walk to in a test. */
    private static final int BUDGET = 5;

    private static final long PACING = MineJob.PACING_MS;

    private long now = 1_000L;

    private final MineFlow flow = new MineFlow(() -> now);

    /** A freshly begun job aims at the first cell and owns its ticks. */
    @Test
    void beginStartsOnTheFirstCellInAim() {
        flow.begin(2);

        assertEquals(MinePhase.AIM, flow.phase());
        assertEquals(0, flow.cell());
        assertEquals(0, flow.cellTicks());
        assertTrue(flow.isActive());
        assertFalse(flow.isStarted());
        assertNull(flow.face());
    }

    /** An idle flow owns nothing. */
    @Test
    void anIdleFlowDoesNotOwnTheTick() {
        assertEquals(MineMove.NONE, flow.tick(MAX_TICKS, PACING));
        assertFalse(flow.isActive());
    }

    /**
     * The one-cell dig: aim, start, middle ticks, stop. Every tick of it is a dig move, i.e. one break call, and
     * the job is done the moment the only cell falls.
     */
    @Test
    void aOneCellDigRunsAimToDone() {
        flow.begin(1);

        assertEquals(MineMove.DIG, flow.tick(MAX_TICKS, PACING));
        flow.onStarted(Direction.UP);
        assertEquals(MinePhase.DIG, flow.phase());
        assertTrue(flow.isStarted());
        assertSame(Direction.UP, flow.face());

        assertEquals(MineMove.DIG, flow.tick(MAX_TICKS, PACING));
        assertEquals(MinePhase.DIG, flow.phase());

        flow.onFinished(false);
        assertEquals(MinePhase.DONE, flow.phase());
        assertFalse(flow.isActive());
        assertFalse(flow.isStarted());
        assertNull(flow.face());
        assertEquals(MineMove.NONE, flow.tick(MAX_TICKS, PACING));
    }

    /**
     * The two-cell dig and its pacing window. The game mode swallows the five calls that follow a completed
     * break, so those are made straight away — they carry no packet — and only the call that would BECOME the
     * next start waits for the 300 ms floor.
     */
    @Test
    void theSecondCellWaitsOutTheDestroyDelayAndTheWallClock() {
        flow.begin(2);
        flow.tick(MAX_TICKS, PACING);
        flow.onStarted(Direction.UP);
        flow.onFinished(false);

        assertEquals(MinePhase.SETTLE, flow.phase());
        assertEquals(1, flow.cell());
        assertEquals(0, flow.cellTicks());

        // The calls vanilla is going to swallow are made at once; the clock does not move (a stalled client).
        for (int i = 0; i < MineFlow.VANILLA_DESTROY_DELAY; i++) {
            assertEquals(MineMove.DIG, flow.tick(MAX_TICKS, PACING));
            flow.onSwallowed();
        }

        // The next call would be the start, and no real time has passed.
        assertEquals(MineMove.HOLD, flow.tick(MAX_TICKS, PACING));
        assertEquals(MinePhase.SETTLE, flow.phase());

        now += PACING - 1;
        assertEquals(MineMove.HOLD, flow.tick(MAX_TICKS, PACING));

        now += 1;
        assertEquals(MineMove.DIG, flow.tick(MAX_TICKS, PACING));
        flow.onStarted(Direction.NORTH);
        assertEquals(MinePhase.DIG, flow.phase());
        assertSame(Direction.NORTH, flow.face());

        flow.onFinished(false);
        assertEquals(MinePhase.DONE, flow.phase());
    }

    /**
     * An INSTANT break leaves the destroy delay at zero — the non-creative instant path never sets it — so
     * nothing but the wall-clock floor separates it from the next start.
     */
    @Test
    void anInstantBreakIsPacedByTheClockAlone() {
        flow.begin(2);
        flow.tick(MAX_TICKS, PACING);
        flow.onStarted(Direction.UP);
        flow.onFinished(true);

        assertEquals(MinePhase.SETTLE, flow.phase());
        assertEquals(MineMove.HOLD, flow.tick(MAX_TICKS, PACING));

        now += PACING;
        assertEquals(MineMove.DIG, flow.tick(MAX_TICKS, PACING));
    }

    /** A cell that outlives MaxTicks ends the job; the shell still owes the abort while a start is outstanding. */
    @Test
    void aCellThatOutlivesMaxTicksGivesUp() {
        flow.begin(1);
        for (int i = 0; i < BUDGET; i++) {
            assertEquals(MineMove.DIG, flow.tick(BUDGET, PACING));
        }
        flow.onStarted(Direction.UP);

        assertEquals(MineMove.ABORT, flow.tick(BUDGET, PACING));
        assertEquals(MinePhase.ABORTED, flow.phase());
        assertFalse(flow.isActive());
        assertTrue(flow.isStarted());
    }

    /** The deadline is charged per cell: finishing one hands the next its own full budget. */
    @Test
    void theTickBudgetIsChargedPerCell() {
        flow.begin(2);
        for (int i = 0; i < BUDGET; i++) {
            flow.tick(BUDGET, PACING);
        }
        flow.onStarted(Direction.UP);
        flow.onFinished(true);
        now += PACING;

        assertEquals(0, flow.cellTicks());
        for (int i = 0; i < BUDGET; i++) {
            assertEquals(MineMove.DIG, flow.tick(BUDGET, PACING));
        }
        assertEquals(MineMove.ABORT, flow.tick(BUDGET, PACING));
    }

    /** After the abort went out nothing is owed any more and the flow owns no further tick. */
    @Test
    void anAbortClearsTheOutstandingStart() {
        flow.begin(2);
        flow.tick(MAX_TICKS, PACING);
        flow.onStarted(Direction.EAST);
        flow.onAborted();

        assertEquals(MinePhase.ABORTED, flow.phase());
        assertFalse(flow.isStarted());
        assertNull(flow.face());
        assertFalse(flow.isActive());
        assertEquals(MineMove.NONE, flow.tick(MAX_TICKS, PACING));
    }

    /** Resetting returns the flow to the idle state a fresh instance starts in. */
    @Test
    void resetReturnsTheFlowToIdle() {
        flow.begin(2);
        flow.tick(MAX_TICKS, PACING);
        flow.onStarted(Direction.SOUTH);
        flow.reset();

        assertEquals(MinePhase.IDLE, flow.phase());
        assertEquals(0, flow.cell());
        assertEquals(0, flow.cellTicks());
        assertFalse(flow.isStarted());
        assertNull(flow.face());
        assertFalse(flow.isActive());
    }
}
