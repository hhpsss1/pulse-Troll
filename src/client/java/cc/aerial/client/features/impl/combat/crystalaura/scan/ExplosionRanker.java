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

package cc.aerial.client.features.impl.combat.crystalaura.scan;

import cc.aerial.client.features.impl.combat.crystalaura.calc.DamageCalculator;
import cc.aerial.client.features.impl.combat.crystalaura.calc.DamageResult;
import cc.aerial.client.features.impl.combat.crystalaura.calc.ShapeSource;
import cc.aerial.client.features.impl.combat.crystalaura.calc.VictimState;
import cc.aerial.client.features.impl.combat.crystalaura.config.PlaceSettings;
import cc.aerial.client.features.impl.combat.crystalaura.world.TargetEntry;
import cc.aerial.client.features.impl.combat.crystalaura.world.WorldView;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Branch-and-bound ranking of explosion centres against the targets of a world view, shared by the placement scan
 * (centres keyed by the base block) and the break scan (keyed by the crystal).
 *
 * <p>Single use: {@link #offer} queues, and the first pull of {@link #ranked} — or of {@link #best}, its head —
 * consumes the queue. The victim function selects the snapshot, the source function the shape source of a target's
 * exposure rays, and the gate is the expensive per-key admission test (the entity column) that runs at most once
 * per key and only after a bound has passed.
 *
 * <ol>
 *   <li>{@link #offer}: for every target the exposure-free upper bound is computed, and the pair is queued when the
 *       bound is positive and either reaches the minimum damage or, with the lethal override, reaches health plus
 *       absorption — the bound form of the lethality test, because health loss is the effective damage minus
 *       absorption, floored at zero.</li>
 *   <li>Refinement: the queue is drained in descending bound; each entry is evaluated with rays and kept when it
 *       passes the target acceptance. The drain stops as soon as the next bound is below the best effective damage
 *       found — no later entry can reach or tie it. A non-strict test keeps exact ties alive so the self-damage and
 *       distance tie-breaks still apply.</li>
 *   <li>Ranking: self damage for the finalists in order, at most {@link #MAX_FINALISTS} of them, one tie group at a
 *       time and only as far as the consumer pulls. A finalist that fails the self checks never ends the search —
 *       the next one is tried.</li>
 * </ol>
 */
final class ExplosionRanker<K> {
    /**
     * How many finalists may get the self-damage evaluation.
     *
     * <p>{@link #best} walks them in rank order and stops at the first that passes the self checks, so the cap is
     * only reached when every one of them is unsafe — typically the self-damage limit or anti-suicide against a
     * victim standing right next to us. The trade-off: one more finalist is one more 45-ray self evaluation in that
     * worst case, while a cap that is too small silently returns nothing although a self-safe placement exists.
     */
    private static final int MAX_FINALISTS = 8;

    private static final Comparator<Bound<?>> BY_BOUND = Comparator.comparingDouble(b -> -b.ub);

    private static final Comparator<Scored<?>> BY_TARGET =
            Comparator.<Scored<?>>comparingDouble(s -> -s.damage.effective())
                    .thenComparingDouble(s -> s.bound.distSq);

    private final WorldView view;
    private final DamageCalculator calc;
    private final PlaceSettings settings;
    private final Function<TargetEntry, VictimState> victimOf;
    private final Function<TargetEntry, ShapeSource> sourceOf;
    private final ShapeSource selfSource;
    private final Predicate<K> gate;

    private final PriorityQueue<Bound<K>> heap = new PriorityQueue<>(BY_BOUND);

    ExplosionRanker(WorldView view, DamageCalculator calc, PlaceSettings settings,
                    Function<TargetEntry, VictimState> victimOf,
                    Function<TargetEntry, ShapeSource> sourceOf,
                    ShapeSource selfSource, Predicate<K> gate) {
        this.view = view;
        this.calc = calc;
        this.settings = settings;
        this.victimOf = victimOf;
        this.sourceOf = sourceOf;
        this.selfSource = selfSource;
        this.gate = gate;
    }

    /** Largest passing bound at a centre, without queueing anything; 0 when no target could pass. */
    float bound(Vec3 center) {
        float best = 0f;
        for (TargetEntry target : view.targets()) {
            VictimState victim = victimOf.apply(target);
            float bound = calc.upperBound(center, victim);
            if (bound > best && passesBound(bound, victim)) {
                best = bound;
            }
        }
        return best;
    }

    /**
     * Queues a centre for every target whose bound passes. The gate is consulted before the first entry is queued,
     * and a closed gate queues nothing.
     *
     * @return whether anything was queued
     */
    boolean offer(K key, Vec3 center, double distSq) {
        boolean open = false;
        for (TargetEntry target : view.targets()) {
            VictimState victim = victimOf.apply(target);
            float bound = calc.upperBound(center, victim);
            peakBound = Math.max(peakBound, bound);
            if (!passesBound(bound, victim)) {
                continue;
            }
            if (!open && !gate.test(key)) {
                return false;
            }
            open = true;
            heap.add(new Bound<>(key, center, distSq, target, bound));
        }
        return open;
    }

    /**
     * The accepted finalists in rank order, evaluated on demand; empty when nothing queued passes both
     * acceptances. The refinement runs at the first pull and drains the queue, so the ranker is spent from then on
     * and the result may be iterated only once.
     *
     * <p>The unit of work is the tie group, the run of finalists with equal target damage — contiguous, because the
     * finalists are sorted by that value first. Pulling an element evaluates the self damage of every finalist in
     * its group, and of every fully rejected group before it, and yields those that pass in rank order; nothing
     * beyond that group is touched until the consumer asks for more. So the head costs exactly what a
     * single-result search costs.
     */
    Iterable<Ranked<K>> ranked() {
        return FinalistRanking.rank(
                this::refine,
                center -> calc.damage(center, view.self(), selfSource),
                this::acceptsSelf);
    }

    /** The first element of {@link #ranked}, or null when nothing passes both acceptances. */
    @Nullable
    Ranked<K> best() {
        Iterator<Ranked<K>> iterator = ranked().iterator();
        return iterator.hasNext() ? iterator.next() : null;
    }

    private List<Scored<K>> refine() {
        List<Scored<K>> finalists = new ArrayList<>();
        float bestEffective = 0f;
        Bound<K> next = heap.poll();
        while (next != null && next.ub >= bestEffective) {
            VictimState victim = victimOf.apply(next.target);
            DamageResult damage = calc.damage(next.center, victim, sourceOf.apply(next.target));
            peakDamage = Math.max(peakDamage, damage.effective());
            if (acceptsTarget(damage, victim)) {
                finalists.add(new Scored<>(next, damage));
                bestEffective = Math.max(bestEffective, damage.effective());
            }
            next = heap.poll();
        }
        finalists.sort(BY_TARGET);
        return finalists.size() > MAX_FINALISTS ? finalists.subList(0, MAX_FINALISTS) : finalists;
    }

    private float peakBound;
    private float peakDamage;
    private float peakSelf;

    /**
     * The best numbers this ranker saw, whether or not they were accepted.
     *
     * <p>An empty result has three different shapes and the acceptance rules hide all of them: the bound never
     * cleared the minimum, or it did and the ray-accurate damage did not, or both passed and the self damage
     * vetoed it. Reading the peaks separates the three, and separates all of them from a damage model that is
     * simply returning nonsense.
     */
    String peaks() {
        return "bound=" + peakBound + " dmg=" + peakDamage + " self=" + peakSelf;
    }

    private boolean passesBound(float bound, VictimState victim) {
        if (bound <= 0f) {
            return false;
        }
        return bound >= settings.minDamage.getValue()
                || (settings.lethalOverride.getValue() && bound >= victim.health() + victim.absorption());
    }

    private boolean acceptsTarget(DamageResult damage, VictimState victim) {
        if (damage.effective() <= 0f) {
            return false;
        }
        return damage.effective() >= settings.minDamage.getValue()
                || (settings.lethalOverride.getValue() && damage.isLethalFor(victim));
    }

    private boolean acceptsSelf(DamageResult selfDamage) {
        peakSelf = Math.max(peakSelf, selfDamage.effective());
        if (selfDamage.effective() > settings.maxSelfDamage.getValue()) {
            return false;
        }
        return !settings.antiSuicide.getValue()
                || selfDamage.healthLoss() + selfDamage.absorbed()
                        < view.self().health() + view.self().absorption();
    }
}
