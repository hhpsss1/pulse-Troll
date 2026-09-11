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
 * AutoMine (off by default): break the one or two blocks that stand between us and an obsidian base,
 * so a crystal can be placed where the terrain does not currently allow one.
 *
 * <p>Two situations justify it. An enemy on flat ground: breaking the ground block next to them and
 * dropping the obsidian into that hole puts the crystal at FOOT level. An enemy underground: the
 * 1x1x1 pocket the crystal needs has to be carved out first.
 *
 * <p>Why foot level is worth a dig: {@code ServerExplosion.hurtEntities} measures to the victim's
 * FEET — {@code Entity.distanceToSqr} uses {@code getY()}, while the eye position only steers the
 * knockback — so a crystal one block lower has a strictly smaller distance fraction and therefore
 * deals more. On flat ground the gain is far larger than that term alone suggests, because a base
 * placed beside the enemy at their own level occludes its own crystal: the obsidian spans the block
 * below the explosion centre and cuts the lowest rows of the exposure sample grid. Measured for a
 * victim at (0.5, 64.0, 0.5) with 20 armour and 8 toughness: a base at y-1 gives exposure ~1.0, raw
 * 74.8, ~62.8 effective; a base beside them at y gives exposure ~0.4, raw 21.1, ~8.6 effective.
 *
 * <p>How many cells that is follows from {@code EndCrystalItem.useOn}: the base cell must be
 * replaceable by obsidian (or already be obsidian or bedrock) and the cell above it must be TRUE AIR.
 * There is no block check above that, none on the horizontal neighbours, and no test against the
 * crystal's own 2x2x2 hitbox — so a fully enclosed pocket works and two cells are always enough,
 * hence the {@link #maxBlocks} default of 2.
 *
 * <p>Digging is slow and mutually exclusive with everything else the aura does — a tick that carries
 * a dig packet carries nothing else — which is what {@link #minGain} and {@link #interrupt} pay for.
 */
public final class MineSettings {
    /** Whether AutoMine runs at all. */
    public final BooleanProperty enabled = new BooleanProperty("Enabled", false);

    /**
     * How many cells may be cleared to open ONE base.
     *
     * <p>1 is the flat-ground case (break the ground block next to the enemy, the obsidian goes into
     * the hole); 2 is the enclosed case (base cell and the cell above it are both solid). Nothing
     * above 2 is ever needed for a crystal — the item checks exactly those two cells — so 3 only
     * exists as headroom.
     */
    public final NumberProperty maxBlocks = new NumberProperty("MaxBlocks", 2, 1, 3, 1);

    /**
     * How much better, in percent, the mined placement must be than the best placement available
     * RIGHT NOW.
     *
     * <p>The floor is {@code best.effective * (1 + MinGain / 100)}, the same shape the base-place gain
     * uses. It is higher than the base-place default because a dig costs whole ticks in which the aura
     * can neither place nor attack, so a mined spot must be clearly worth waiting for and must never
     * displace something we can do now.
     */
    public final NumberProperty minGain = new NumberProperty("MinGain", 30, 0, 200, 1);

    /**
     * Maximum eye-to-hit-point distance for the DIG; the placement keeps the base-place range.
     *
     * <p>The server-side bound is {@code Player.isWithinBlockInteractionRange}, and Grim's FarBreak
     * measures eye to block AABB against the same interaction range; the box distance used for the
     * pre-cut is a lower bound of the real hit-point distance, so a value at or below the server's own
     * limit is always safe.
     */
    public final NumberProperty range = new NumberProperty("Range", 4.5, 1, 5.5, 0.1);

    /**
     * How the mining tool is brought into the hand: server-side only, as a real hotbar change, or not
     * at all (dig with whatever is held).
     *
     * <p>The tool decides the dig time — {@code Player.getDestroySpeed} reads the SELECTED hotbar slot,
     * and the wrong tool is a 3.33x penalty (the 30 versus 100 divisor of
     * {@code BlockBehaviour.getDestroyProgress}), not a hard stop. The switch must be announced BEFORE
     * the dig packet, which {@code MultiPlayerGameMode.continueDestroyBlock} does by itself by calling
     * {@code ensureHasSentCarriedItem()} first.
     */
    public final ModeProperty<SwitchMode> tool = new ModeProperty<>("Tool", SwitchMode.SILENT);

    /**
     * Give up on a cell that is still not broken after this many ticks.
     *
     * <p>A cell can simply be too slow to be worth it: obsidian with the wrong tool is about 167 ticks,
     * and the aura would spend every one of them unable to attack or place. This is the hard ceiling;
     * the real cost of a two-cell pocket is dig, 300 ms, dig — the finish-to-start pacing Grim's
     * FastBreak requires.
     */
    public final NumberProperty maxTicks = new NumberProperty("MaxTicks", 40, 5, 200, 1);

    /**
     * Abort the dig the moment the aura has a placement or an attack it can execute right now.
     *
     * <p>Digging must never cost a crystal that was already available. With this off the job keeps its
     * ticks until the cell is broken or {@link #maxTicks} runs out.
     */
    public final BooleanProperty interrupt = new BooleanProperty("Interrupt", true);

    private final GroupProperty group;

    public MineSettings() {
        maxBlocks.hideIf(() -> !enabled.getValue());
        minGain.hideIf(() -> !enabled.getValue());
        range.hideIf(() -> !enabled.getValue());
        tool.hideIf(() -> !enabled.getValue());
        maxTicks.hideIf(() -> !enabled.getValue());
        interrupt.hideIf(() -> !enabled.getValue());
        this.group = new GroupProperty("AutoMine",
                enabled, maxBlocks, minGain, range, tool, maxTicks, interrupt);
    }

    public boolean isEnabled() {
        return enabled.getValue();
    }

    public GroupProperty get() {
        return group;
    }
}
