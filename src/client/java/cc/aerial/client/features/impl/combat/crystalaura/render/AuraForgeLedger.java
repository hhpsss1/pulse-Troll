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

package cc.aerial.client.features.impl.combat.crystalaura.render;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The forge's heat table: how hard the aura is working each block, and how that cools off.
 *
 * <p>Every detonation adds a fixed amount to the site keyed by the crystal's block position and refreshes its
 * stamp; the heat then decays exponentially and is clamped to one. So a single crystal is a flicker, and a
 * sustained cycle pins the site at full heat.
 *
 * <p>Nothing is ever drawn at the raw value: it goes through a low pass first, so a detonation's step becomes
 * a rise rather than a jump. The low pass is snapped rather than eased across a long frame gap, which keeps a
 * stall from leaving a site glowing at a stale value.
 *
 * <p>A fixed number of slots, replaced coldest-first, because the effect only ever draws the hottest few and an
 * unbounded table would grow for the whole session.
 */
public final class AuraForgeLedger {
    private AuraForgeLedger() {
    }

    /** Sites tracked at once; the coldest is evicted when a new one arrives. */
    private static final int SLOTS = 8;

    /** Heat one detonation adds. Three in quick succession saturate a site. */
    private static final float HEAT_PER_HIT = 0.34f;

    /** Time constant of the exponential cool-off, in milliseconds. */
    private static final float DECAY_TAU_MS = 1100f;

    /** Below this a site counts as cold and is not drawn. */
    public static final float DEAD_HEAT = 0.004f;

    /** Time constant of the low pass on the drawn value. */
    private static final float SMOOTH_TAU_MS = 110f;

    /** Longest frame gap the low pass eases over; beyond it the drawn value snaps. */
    private static final long MAX_FRAME_MS = 250L;

    private static final long EMPTY_KEY = Long.MIN_VALUE;

    private static final long[] KEYS = new long[SLOTS];
    private static final float[] RAW = new float[SLOTS];
    private static final long[] STAMP = new long[SLOTS];
    private static final float[] SHOWN = new float[SLOTS];

    private static long lastFrameMs;

    static {
        clear();
    }

    /** One warm site: where its crystal stands and how hot it is right now. */
    public record Site(Vec3 center, float heat) {
    }

    /** Records a detonation at a crystal position. */
    public static void hit(Vec3 center, long nowMs) {
        long key = BlockPos.containing(center).asLong();
        int slot = slotFor(key, nowMs);
        boolean fresh = KEYS[slot] != key;
        float base = fresh ? 0f : heatAt(slot, nowMs);
        if (fresh) {
            KEYS[slot] = key;
            SHOWN[slot] = 0f;
        }
        RAW[slot] = Math.min(base + HEAT_PER_HIT, 1f);
        STAMP[slot] = nowMs;
    }

    /**
     * The warm sites, hottest first, with the low pass advanced to this frame.
     *
     * <p>Called once per frame: the low pass keys on the time since the last call, so calling it twice in one
     * frame would advance it twice.
     */
    public static List<Site> sites(long nowMs) {
        float step = smoothingStep(nowMs);
        List<Site> live = new ArrayList<>(SLOTS);
        for (int slot = 0; slot < SLOTS; slot++) {
            if (KEYS[slot] == EMPTY_KEY) {
                continue;
            }
            float raw = heatAt(slot, nowMs);
            SHOWN[slot] += (raw - SHOWN[slot]) * step;
            if (raw < DEAD_HEAT && SHOWN[slot] < DEAD_HEAT) {
                KEYS[slot] = EMPTY_KEY;
                RAW[slot] = 0f;
                SHOWN[slot] = 0f;
                continue;
            }
            live.add(new Site(anchorOf(KEYS[slot]), SHOWN[slot]));
        }
        live.sort((a, b) -> Float.compare(b.heat(), a.heat()));
        return live;
    }

    /** The hottest site's drawn heat without advancing anything, or zero while nothing is warm. */
    public static float peakHeat() {
        float peak = 0f;
        for (int slot = 0; slot < SLOTS; slot++) {
            if (KEYS[slot] != EMPTY_KEY) {
                peak = Math.max(peak, SHOWN[slot]);
            }
        }
        return peak;
    }

    /** Forgets every site. */
    public static void clear() {
        for (int slot = 0; slot < SLOTS; slot++) {
            KEYS[slot] = EMPTY_KEY;
            RAW[slot] = 0f;
            STAMP[slot] = 0L;
            SHOWN[slot] = 0f;
        }
        lastFrameMs = 0L;
    }

    /** The raw heat of one slot, decayed to now. */
    private static float heatAt(int slot, long nowMs) {
        long age = nowMs - STAMP[slot];
        if (age <= 0L) {
            return RAW[slot];
        }
        return (float) (RAW[slot] * Math.exp(-age / (double) DECAY_TAU_MS));
    }

    /**
     * How far the low pass moves towards the raw value this frame. A gap longer than the cap snaps instead of
     * easing, so a stall does not leave a site glowing at a stale value.
     */
    private static float smoothingStep(long nowMs) {
        long previous = lastFrameMs;
        lastFrameMs = nowMs;
        if (previous == 0L) {
            return 1f;
        }
        long delta = nowMs - previous;
        if (delta <= 0L) {
            return 0f;
        }
        if (delta >= MAX_FRAME_MS) {
            return 1f;
        }
        return (float) (1.0 - Math.exp(-delta / (double) SMOOTH_TAU_MS));
    }

    /** The slot holding this key, or the coldest one to evict. */
    private static int slotFor(long key, long nowMs) {
        int coldest = 0;
        float coldestHeat = Float.MAX_VALUE;
        for (int slot = 0; slot < SLOTS; slot++) {
            if (KEYS[slot] == key) {
                return slot;
            }
            if (KEYS[slot] == EMPTY_KEY) {
                return slot;
            }
            float heat = heatAt(slot, nowMs);
            if (heat < coldestHeat) {
                coldestHeat = heat;
                coldest = slot;
            }
        }
        return coldest;
    }

    /** The centre of the crystal standing on the site's block. */
    private static Vec3 anchorOf(long key) {
        BlockPos pos = BlockPos.of(key);
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }
}
