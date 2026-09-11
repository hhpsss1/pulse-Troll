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

/** Where one mining job stands; the machine is {@code IDLE -> AIM -> DIG -> SETTLE -> DIG -> DONE | ABORTED}. */
public enum MinePhase {
    /** No target. */
    IDLE,

    /** A cell is targeted, the aim is being brought onto it, no start-destroy has gone out for it yet. */
    AIM,

    /** A start-destroy for the current cell is outstanding; an abort is owed if we stop now. */
    DIG,

    /** The cell fell; the pacing window before the next cell's start. */
    SETTLE,

    /** Every cell of the target is gone. */
    DONE,

    /** Given up, interrupted or invalidated. */
    ABORTED
}
