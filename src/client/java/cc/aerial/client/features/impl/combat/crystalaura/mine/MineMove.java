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

/** What the mining flow decided this tick belongs to. */
public enum MineMove {
    /** The job does not own this tick. */
    NONE,

    /** The job owns the tick and drives vanilla's progressive breaking on the current cell. */
    DIG,

    /** The job owns the tick, aims, and sends nothing (the pacing floor of the settle window). */
    HOLD,

    /** The job owns the tick and ends it with a stop-destroy. */
    ABORT
}
