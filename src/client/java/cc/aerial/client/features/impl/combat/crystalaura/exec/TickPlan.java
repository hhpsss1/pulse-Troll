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

package cc.aerial.client.features.impl.combat.crystalaura.exec;

import cc.aerial.client.features.impl.combat.crystalaura.aim.LookPoint;
import cc.aerial.client.features.impl.combat.crystalaura.base.BasePlan;
import cc.aerial.client.features.impl.combat.crystalaura.calc.VictimState;
import cc.aerial.client.features.impl.combat.crystalaura.scan.BreakCandidate;
import cc.aerial.client.features.impl.combat.crystalaura.scan.PlaceCandidate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * What one client tick does: at most ONE attack — real or predicted, never both — and at most ONE use — a crystal
 * placement or an obsidian base, never both — attack first, plus the rotation to request for this tick.
 *
 * <p>The two attempt flags say the plan OFFERS the action to the executor for this tick. Whether it actually hits
 * is decided at execute against the rotation this tick's movement packet carries: the plan owns the intent — what
 * to attack, what to place, where to look — and the executor owns the verdict. An action that is planned but not
 * offered stays in the plan for rendering and debugging, and it is what the look point aims at.
 *
 * <p>The slot is brought into the hand for an offered use, because the game has to emit the carried-item change
 * BEFORE the clicks. The place hit is the block hit of the use against the wire when the wire already hits,
 * otherwise the hit the look point promises; it is informational only — the executor always re-traces the ray
 * against the outgoing rotation.
 */
public record TickPlan(
        @Nullable BreakCandidate attack,
        @Nullable PhantomAttack phantomAttack,
        @Nullable PlaceCandidate place,
        @Nullable BasePlan base,
        boolean attackAttempt,
        boolean useAttempt,
        @Nullable LookPoint look,
        @Nullable CrystalSlot slot,
        InteractionHand hand,
        @Nullable BlockHitResult placeHit) {

    private static final int DESCRIBE_CAPACITY = 96;

    /** An empty plan: nothing to do this tick. */
    public static TickPlan idle() {
        return new TickPlan(null, null, null, null, false, false, null, null,
                InteractionHand.MAIN_HAND, null);
    }

    public boolean attempting() {
        return attackAttempt || useAttempt;
    }

    public boolean hasAttack() {
        return attack != null || phantomAttack != null;
    }

    public boolean hasUse() {
        return place != null || base != null;
    }

    /** Block the next crystal is placed on, for rendering. */
    @Nullable
    public BlockPos placePos() {
        return place == null ? null : place.pos();
    }

    /**
     * Block the obsidian base goes to THIS tick, for rendering; null when none is being laid.
     *
     * <p>A base can sit in the plan without getting the tick's single use — it is kept there because it is
     * what the aim is being pointed at, and because the debug line reports it. Drawing it regardless made the
     * marker wander over every candidate the search reconsidered while a crystal was going down, which reads
     * as the module changing its mind rather than as it working. The box now marks obsidian actually being
     * placed.
     */
    @Nullable
    public BlockPos basePos() {
        return base == null || !useAttempt ? null : base.block();
    }

    /** Hitbox of the crystal, real or predicted, to attack; for rendering. */
    @Nullable
    public AABB attackBox() {
        if (attack != null) {
            return attack.crystal().getBoundingBox();
        }
        return phantomAttack == null ? null : phantomAttack.box();
    }

    /**
     * Compact one-liner: actions with positions and target or self damage, what is offered to the executor this
     * tick, and the look angle. What actually went out is the execution report logged right after execute — the
     * attempt flags are plan-time fields, and the ray verdict is not taken yet when this is printed.
     */
    public String describe() {
        StringBuilder out = new StringBuilder(DESCRIBE_CAPACITY);
        if (attack != null) {
            out.append("atk#").append(attack.crystal().getId()).append(' ')
                    .append(fmt(attack.damage().effective())).append('/')
                    .append(fmt(attack.selfDamage().effective()));
        }
        if (phantomAttack != null) {
            out.append("phantom#").append(phantomAttack.id());
        }
        if (place != null) {
            separate(out).append("place ").append(pos(place.pos())).append(' ')
                    .append(fmt(place.damage().effective())).append('/')
                    .append(fmt(place.selfDamage().effective()));
            describeDefence(out, place.target().placeState());
        }
        if (base != null) {
            // The face is null for the replace-in-place form, where the obsidian goes into the clicked cell
            // itself and the hit direction is read by nobody — printed so a trace says which form ran.
            separate(out).append("base ").append(pos(base.block())).append(" via ").append(pos(base.clickPos()))
                    .append('/').append(base.face() == null ? "in-place" : base.face().name())
                    .append(' ').append(fmt(base.candidate().damage().effective()));
        }
        if (out.isEmpty()) {
            out.append("idle");
        }
        out.append(" try=").append(attackAttempt ? 'A' : '-').append(useAttempt ? 'U' : '-');
        if (look != null) {
            out.append(" look=").append(fmt(look.angle())).append("deg");
            if (look.servesAttack()) {
                out.append("+atk");
            }
        }
        return out.toString();
    }

    /**
     * The victim's defence as the model read it: armour points, toughness, the enchantment protection points the
     * damage pipeline applies, and the Resistance amplifier where -1 means none.
     *
     * <p>These four are what separates a predicted number from the damage actually taken, and three of them can
     * be wrong without anything looking broken: the protection is computed client-side because vanilla refuses to
     * compute it off a server level, the Resistance is never synced for a remote victim at all, and a server may
     * rewrite the enchantment components the first is read from. Printing them turns "the aura over-estimates"
     * from a guess into a reading — a full Protection IV set is 16 points, a mixed set with Blast Protection IV
     * on one piece reaches the 20-point cap, and zero protection on a visibly enchanted opponent is the bug, not
     * the model.
     */
    private static void describeDefence(StringBuilder out, VictimState victim) {
        out.append(" arm=").append(victim.armor())
                .append('/').append(fmt(victim.toughness()))
                .append(" prot=").append(fmt(victim.protectionPoints()))
                .append(" res=").append(victim.resistanceAmplifier());
    }

    private static StringBuilder separate(StringBuilder out) {
        return out.isEmpty() ? out : out.append(' ');
    }

    private static String pos(BlockPos pos) {
        return "(" + pos.getX() + ' ' + pos.getY() + ' ' + pos.getZ() + ')';
    }

    private static String fmt(float value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
