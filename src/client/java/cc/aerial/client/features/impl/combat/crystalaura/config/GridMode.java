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

package cc.aerial.client.features.impl.combat.crystalaura.config;

/**
 * How much of the mouse-sensitivity grid step (gcd) a look point may be off the exact aim:
 * {@link #DITHER} allows most of a step, {@link #EXACT} only half a step.
 */
public enum GridMode {
    /**
     * The rotation is rounded onto the grid and dithered by up to 0.4 gcd, so what actually goes out
     * is NOT bit-identical to what the aura requested. The aura verifies every action against the
     * rotation this tick's movement packet carries, exactly — so when the dither pushes the ray off
     * the block, the same-tick attempt simply falls back to the next tick. The anticheat-friendlier
     * default: an exact grid is what Grim's AimProcessor keys on.
     */
    DITHER("Dither"),

    /**
     * The requested delta is pre-quantised to whole gcd counts and no dither is added. The rotation
     * that leaves is bit-identical to the requested one, so the aura's same-tick verification is
     * deterministic: a re-aim and the placement or attack it enables land in the same client tick
     * every time, instead of only when the dither happens to stay on the block.
     */
    EXACT("Exact");

    private final String label;

    GridMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
