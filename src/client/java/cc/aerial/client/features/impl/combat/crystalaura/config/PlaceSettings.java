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
 * Placement settings: how far and how often crystals are placed, the damage thresholds a placement
 * has to meet, target prediction, how the crystal gets into the hand, and whether a place may share
 * a tick with a break.
 *
 * <p>Every increment below exactly divides its own default. {@code NumberProperty.setValue} rounds to
 * a multiple of the increment on every write, including the constructor's, so a mismatched pair
 * would silently shift the default.
 */
public final class PlaceSettings {
    /** Maximum distance from the eye to the hit point on the clicked block face. */
    public final NumberProperty range = new NumberProperty("Range", 4.5, 1.0, 5.5, 0.1);

    /** Minimum time between two placements, in milliseconds. */
    public final NumberProperty delay = new NumberProperty("Delay", 0, 0, 1000, 1);

    /** Minimum effective damage the crystal must deal to the target. */
    public final NumberProperty minDamage = new NumberProperty("MinDamage", 6, 0, 40, 0.5);

    /** Maximum effective damage the crystal may deal to the local player. */
    public final NumberProperty maxSelfDamage = new NumberProperty("MaxSelfDamage", 6, 0, 40, 0.5);

    /** Never place a crystal whose explosion would be lethal for the local player. */
    public final BooleanProperty antiSuicide = new BooleanProperty("AntiSuicide", true);

    /** Accept a placement that is lethal for the target even when it fails the damage thresholds. */
    public final BooleanProperty lethalOverride = new BooleanProperty("LethalOverride", true);

    /**
     * Resistance level assumed for a victim whose real effect the client cannot see; 0 assumes none.
     *
     * <p>The server sends {@code ClientboundUpdateMobEffectPacket} only to the affected player's own
     * connection and to players riding the entity — never to trackers. {@code getEffect(RESISTANCE)}
     * is therefore always null for a remote victim, and the damage model over-estimates by 1.25x per
     * level, up to predicting full damage against a victim that takes zero at Resistance V. Raise
     * this on servers where Resistance is common; it is only ever a fallback and never lowers an
     * effect the client can actually see — the world capture takes {@code assumeResistance - 1} as an
     * amplifier and maxes it against the real reading.
     */
    public final NumberProperty assumeResistance = new NumberProperty("AssumeResistance", 0, 0, 5, 1);

    /**
     * Armour a remote victim is assumed to have when the server hides it; see {@link ArmorAssumption}.
     *
     * <p>Reach for this on servers that obfuscate enchantments: without it every opponent is scored as
     * unenchanted, the predicted damage is far above the real one, and MinDamage lets through
     * placements that do not deliver.
     */
    public final ModeProperty<ArmorAssumption> assumeArmor =
            new ModeProperty<>("AssumeArmor", ArmorAssumption.OFF);

    /**
     * Which difficulty player damage is scaled by; see {@link DifficultyAssumption} for why the level's own
     * reading cannot simply be trusted on a server.
     */
    public final ModeProperty<DifficultyAssumption> assumeDifficulty =
            new ModeProperty<>("AssumeDifficulty", DifficultyAssumption.AUTO);

    /**
     * Act on blocks that other blocks hide: place and dig without a line of sight. Governs placements,
     * the obsidian base and AutoMine alike.
     *
     * <p>Nothing on the server side objects. {@code handleUseItemOn} validates only
     * {@code isWithinBlockInteractionRange(pos, 1.0)} and that the hit location sits within
     * +-1.0000001 of the block centre per axis; {@code handleBlockBreakAction} validates range, the
     * build limit, spawn protection, the world border and {@code blockActionRestricted}. Neither
     * traces the world between the eye and the block. Grim does not either: its place and break
     * rotation checks build a collision box of the TARGET position alone and return true as soon as
     * the ray meets that one box — the requirement is to point at the block within interaction range,
     * not to see it.
     *
     * <p>What it does cost, stated plainly: this is the one action in this module a vanilla client
     * cannot produce. Its crosshair picks the FIRST block along the ray, so a real player can never
     * click an occluded one. Every packet stays well formed and every other rule still holds — the
     * face is still one the eye is outside of, the range is still checked, the packet order is
     * untouched — but the behaviour itself is not reproducible by hand. Hence opt-in and off by
     * default.
     */
    public final BooleanProperty throughBlocks = new BooleanProperty("ThroughBlocks", false);

    /** Which entities the placement pick ray may pass through. */
    public final ModeProperty<LookThrough> lookThrough = new ModeProperty<>("LookThrough", LookThrough.CRYSTALS);

    /** Target position prediction for the damage evaluation. */
    public final ModeProperty<PredictMode> predict = new ModeProperty<>("Predict", PredictMode.AUTO);

    /** Extrapolation length used by {@link PredictMode#FIXED}. */
    public final NumberProperty predictTicks = new NumberProperty("PredictTicks", 2, 0, 10, 1);

    /** How the end crystal is brought into the hand. */
    public final ModeProperty<SwitchMode> switchMode = new ModeProperty<>("Switch", SwitchMode.SILENT);

    /** Ticks the switched slot stays selected before swapping back. */
    public final NumberProperty swapBack = new NumberProperty("SwapBack", 0, 0, 20, 1);

    /**
     * Allow a placement in the SAME tick as a break, attack first — the order vanilla's click loops
     * produce.
     *
     * <p>Default off, and it has to stay off against Grim. A block interaction and an entity
     * interaction inside one tick window are exactly what its multi-action check looks for
     * ("Interacting with a block and an entity in the same tick"), and the resulting attack followed
     * by a use-item with no interact-entity between them trips its packet-order check ("Sent use item
     * after attacking without the expected interaction packet"). The window closes on the movement
     * packet, or on the tick-end event when the tick sent no movement — and our clicks always precede
     * both, so the pair lands inside one window every single time.
     *
     * <p>On servers without those checks it roughly doubles the rate; it is the module's one
     * deliberate multi-action.
     */
    public final BooleanProperty sameTick = new BooleanProperty("SameTick", false);

    /**
     * Only allow {@link #sameTick} while the player is standing still, and drop back to one action per
     * tick the moment they move.
     *
     * <p>This is an EMPIRICAL mitigation, not a legalisation: the pair is just as illegal standing as
     * moving — the window is one client tick either way, since our clicks precede both the movement
     * packet and the tick-end event. What changes is the rate. Standing, the aura runs far fewer
     * cycles per second, so the violations stay under the alert threshold; moving, they pile up and
     * the check reports. Playtesting on a Grim server produced exactly that split, which is why the
     * mode exists.
     *
     * <p>"Standing" is deliberately strict and asymmetric: any per-tick movement or sprinting disables
     * it at once, and it takes several calm ticks to come back, so a single step cannot flip it back
     * and forth.
     */
    public final BooleanProperty sameTickSafe = new BooleanProperty("SameTickSafe", true);

    private final GroupProperty group;

    public PlaceSettings() {
        predictTicks.hideIf(() -> predict.getValue() != PredictMode.FIXED);
        swapBack.hideIf(() -> switchMode.getValue() == SwitchMode.NONE);
        sameTickSafe.hideIf(() -> !sameTick.getValue());
        this.group = new GroupProperty("Place",
                range, delay, minDamage, maxSelfDamage, antiSuicide, lethalOverride,
                assumeResistance, assumeArmor, assumeDifficulty, throughBlocks, lookThrough,
                predict, predictTicks, switchMode, swapBack, sameTick, sameTickSafe);
    }

    public GroupProperty get() {
        return group;
    }
}
