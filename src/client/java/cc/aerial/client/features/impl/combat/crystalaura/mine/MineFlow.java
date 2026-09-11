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

package cc.aerial.client.features.impl.combat.crystalaura.mine;

import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.function.LongSupplier;

/**
 * The pure part of {@link MineJob}: which phase the dig is in, which cell it is on, how many ticks that cell has
 * cost and when the last break completed. No world, no packets, no client — everything it knows arrives through
 * {@link #tick} and the three observations {@link #onStarted}, {@link #onFinished} and {@link #onSwallowed},
 * which is what makes the pacing and the give-up paths testable headlessly.
 *
 * <h2>Pacing: vanilla's own delay plus a wall-clock floor</h2>
 * <p>{@code MultiPlayerGameMode.continueDestroyBlock} sets {@code destroyDelay = 5} on the tick a break
 * completes, and every later call consumes one of those five and sends NOTHING — it merely returns true so the
 * caller still swings. The sixth call is the one that reaches {@code startDestroyBlock} and emits the
 * start-destroy, i.e. vanilla itself paces two breaks six client ticks = 300 ms apart, which is exactly what an
 * anticheat's fast-break check wants: it adds {@code 300 - gapMs} to a balance for every gap below 275 ms and
 * flags once that balance passes 1000 ms, so a 300 ms gap is neutral and decays the balance instead.
 *
 * <p>Two holes make the wall-clock floor necessary on top of it. An INSTANT break never sets
 * {@code destroyDelay} — the non-creative instant path of {@code startDestroyBlock} destroys the block and
 * returns without touching it, the only writes being the creative and the completion ones — so nothing would
 * separate two instant breaks. And ticks are not always 50 ms apart: after a stall the client runs several
 * ticks in one frame, which would compress six ticks into far less than 300 ms. The settle window therefore
 * lets vanilla swallow the calls it is going to swallow anyway (five after a real break, none after an instant
 * one) and only then gates the call that would BECOME the start on the job's pacing floor of real time.
 * Skipping a call is always safe: the delay only ever counts down through calls we make, so a skipped tick can
 * never produce an early start.
 *
 * <h2>Ticks are counted per cell, aim included</h2>
 * <p>MaxTicks is charged from the moment a cell becomes the current one, not from its start. A cell whose face
 * no rotation can reach costs the aura exactly as much as a slow one — the job owns its ticks either way — and
 * only a deadline that covers the aim can end that.
 */
final class MineFlow {
    /**
     * The {@code destroyDelay = 5} vanilla sets after a completed break; each further continue-destroy consumes
     * one and sends nothing, so the sixth call is the start.
     */
    static final int VANILLA_DESTROY_DELAY = 5;

    private final LongSupplier clock;

    private MinePhase phase = MinePhase.IDLE;
    private int cell;
    private int cellTicks;
    @Nullable
    private Direction face;
    private boolean started;

    private int cellCount;
    private long finishedAt;
    private int expectedSwallow;
    private int swallowed;

    MineFlow() {
        this(System::currentTimeMillis);
    }

    MineFlow(LongSupplier clock) {
        this.clock = clock;
    }

    MinePhase phase() {
        return phase;
    }

    /** Index into the target's cells; the list is ordered top-down and holds one or two entries. */
    int cell() {
        return cell;
    }

    /** Ticks this job has owned for the current cell, aim ticks included. */
    int cellTicks() {
        return cellTicks;
    }

    /**
     * The face the start went out with, frozen for the rest of the cell.
     *
     * <p>An anticheat accepts one position-and-face pair per tick across the non-abort dig packets, and it
     * remembers the face of the last abort and flags the next dig packet that names a different one, so the
     * stop that ends a dig must carry the face its start carried.
     */
    @Nullable
    Direction face() {
        return face;
    }

    /** True while a start-destroy is outstanding, i.e. an abort is owed before the job may stop. */
    boolean isStarted() {
        return started;
    }

    /** The job owns its ticks in exactly these three phases. */
    boolean isActive() {
        return phase == MinePhase.AIM || phase == MinePhase.DIG || phase == MinePhase.SETTLE;
    }

    /** Starts a job over the given number of cells (one or two). */
    void begin(int cells) {
        phase = MinePhase.AIM;
        cell = 0;
        cellTicks = 0;
        cellCount = cells;
        face = null;
        started = false;
        finishedAt = 0L;
        expectedSwallow = 0;
        swallowed = 0;
    }

    /** Forgets everything; the caller has already released the claim. */
    void reset() {
        phase = MinePhase.IDLE;
        cell = 0;
        cellTicks = 0;
        cellCount = 0;
        face = null;
        started = false;
    }

    /**
     * This tick's move. Both arguments are read live, so a setting changed mid-dig takes effect at once. A cell
     * that outlived {@code maxTicks} ends the job through {@link MineMove#ABORT} — the shell still owes the
     * abort packet while a start is outstanding.
     */
    MineMove tick(int maxTicks, long pacingMs) {
        if (!isActive()) {
            return MineMove.NONE;
        }

        cellTicks++;
        if (cellTicks > maxTicks) {
            phase = MinePhase.ABORTED;
            return MineMove.ABORT;
        }
        return phase == MinePhase.SETTLE ? settle(pacingMs) : MineMove.DIG;
    }

    /**
     * The pacing window: the calls vanilla is going to swallow are made straight away, because they carry no
     * packet and running them down in parallel with the clock is what makes the next start land at the vanilla
     * 300 ms instead of at 600. The call that would become the start waits for the pacing floor of real time.
     */
    private MineMove settle(long pacingMs) {
        if (swallowed < expectedSwallow) {
            return MineMove.DIG;
        }
        return clock.getAsLong() - finishedAt >= pacingMs ? MineMove.DIG : MineMove.HOLD;
    }

    /** A start-destroy went out with this face; the cell is now being destroyed. */
    void onStarted(Direction digFace) {
        phase = MinePhase.DIG;
        face = digFace;
        started = true;
    }

    /**
     * The cell fell: either the stop-destroy of {@code continueDestroyBlock} or, when {@code instant}, the
     * start-only instant break of {@code startDestroyBlock}. Advances to the next cell or to
     * {@link MinePhase#DONE} and opens the pacing window.
     */
    void onFinished(boolean instant) {
        started = false;
        face = null;
        finishedAt = clock.getAsLong();
        expectedSwallow = instant ? 0 : VANILLA_DESTROY_DELAY;
        swallowed = 0;
        cell++;
        cellTicks = 0;
        phase = cell >= cellCount ? MinePhase.DONE : MinePhase.SETTLE;
    }

    /** A break call that vanilla's destroy delay consumed without sending anything. */
    void onSwallowed() {
        swallowed++;
    }

    /** The abort went out (or nothing was owed); the job is over. */
    void onAborted() {
        started = false;
        face = null;
        phase = MinePhase.ABORTED;
    }

    @Override
    public String toString() {
        return phase + " cell=" + cell + "/" + cellCount + " t=" + cellTicks;
    }
}
