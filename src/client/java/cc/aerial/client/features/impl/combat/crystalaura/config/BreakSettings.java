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

import cc.aerial.client.property.BooleanProperty;
import cc.aerial.client.property.GroupProperty;
import cc.aerial.client.property.ModeProperty;
import cc.aerial.client.property.NumberProperty;

/**
 * Break settings: how far and how often crystals are attacked, id prediction, ownership filtering,
 * i-frame handling, and how long to wait before attacking an unremoved crystal again.
 */
public final class BreakSettings {
    /** Maximum distance from the eye to the hit point on the crystal box. */
    public final NumberProperty range = new NumberProperty("Range", 3, 1, 6, 0.1);

    /** Minimum time between two attacks, in milliseconds. */
    public final NumberProperty delay = new NumberProperty("Delay", 0, 0, 1000, 1);

    /** Attack our own placement by its predicted entity id before the spawn packet arrives. */
    public final BooleanProperty idPredict = new BooleanProperty("IdPredict", false);

    /** Only attack crystals placed by this module. */
    public final BooleanProperty onlyOwn = new BooleanProperty("OnlyOwn", false);

    /** Whether the damage model honours the victim's invulnerability window. */
    public final ModeProperty<IFrameMode> iFrames = new ModeProperty<>("IFrames", IFrameMode.RESPECT);

    /** Ticks before an attacked but still present crystal is attacked again; 0 derives it from the ping. */
    public final NumberProperty retryTicks = new NumberProperty("RetryTicks", 0, 0, 40, 1);

    /**
     * Detonate even while vanilla's own attack lockout is running.
     *
     * <p>{@code Minecraft.startAttack} sets {@code missTime = 10} whenever a left click resolves to
     * MISS — which includes a click on an air block, through the deliberate {@code case BLOCK:}
     * fall-through — and {@code Minecraft.tick} decrements it once per tick, and only while no screen
     * is open. {@code startAttack} refuses to attack at all while it is positive, and this module
     * mirrored that refusal.
     *
     * <p>The cost of mirroring it is severe in kitpvp: one whiffed click freezes detonation for half a
     * second. It does NOT freeze placement, so the aura places a crystal it then cannot pop, and the
     * planner's own-blast guard suppresses every further placement inside that crystal's blast — the
     * whole aura stalls on a single miss.
     *
     * <p>{@code missTime} is a purely client-side input lockout: the server's attack handler has no
     * analogue of it, so an attack sent while it runs is an ordinary, correct packet, and no check
     * reads it. Turning this off restores the vanilla-faithful behaviour at the cost of that stall.
     */
    public final BooleanProperty ignoreMissTime = new BooleanProperty("IgnoreMissTime", true);

    private final GroupProperty group;

    public BreakSettings() {
        this.group = new GroupProperty("Break",
                range, delay, idPredict, onlyOwn, iFrames, retryTicks, ignoreMissTime);
    }

    public GroupProperty get() {
        return group;
    }
}
