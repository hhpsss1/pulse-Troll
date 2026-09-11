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

import cc.aerial.client.features.impl.combat.crystalaura.calc.DamageResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The rank-ordered walk over an explosion ranker's finalists, lazy one tie group at a time — all of the ranking
 * except where the finalists and the self damage come from, so the ordering contract can be exercised on a
 * constructed ranking.
 *
 * <p>The finalists arrive through a supplier rather than as a list because producing them drains the ranker's
 * queue: that must happen when the first element is pulled, not when the walk is built. They must already be
 * sorted by target damage descending, which is what makes a tie group contiguous.
 *
 * <p>Per group: the self damage for every member, the acceptance as the filter, and the rank comparator as the
 * order. A group whose members all fail contributes nothing and the walk goes on — one unsafe candidate never
 * ends the search.
 *
 * <p>Single use, like the Kotlin sequence it replaces: a second {@code iterator()} throws.
 */
final class FinalistRanking {
    private FinalistRanking() {
    }

    /** Target damage descending, then self damage ascending, then distance ascending. */
    private static final Comparator<Ranked<?>> BY_RANK =
            Comparator.<Ranked<?>>comparingDouble(r -> -r.damage().effective())
                    .thenComparingDouble(r -> r.selfDamage().effective())
                    .thenComparingDouble(Ranked::distSq);

    static <K> Iterable<Ranked<K>> rank(Supplier<List<Scored<K>>> finalists,
                                        Function<Vec3, DamageResult> selfDamage,
                                        Predicate<DamageResult> acceptsSelf) {
        boolean[] taken = {false};
        return () -> {
            if (taken[0]) {
                throw new IllegalStateException("this ranking may only be iterated once");
            }
            taken[0] = true;
            return new RankIterator<>(finalists.get(), selfDamage, acceptsSelf);
        };
    }

    private static final class RankIterator<K> implements Iterator<Ranked<K>> {
        private final List<Scored<K>> sorted;
        private final Function<Vec3, DamageResult> selfDamage;
        private final Predicate<DamageResult> acceptsSelf;

        private int from;
        private List<Ranked<K>> group = List.of();
        private int groupIndex;

        private RankIterator(List<Scored<K>> sorted, Function<Vec3, DamageResult> selfDamage,
                             Predicate<DamageResult> acceptsSelf) {
            this.sorted = sorted;
            this.selfDamage = selfDamage;
            this.acceptsSelf = acceptsSelf;
        }

        @Override
        public boolean hasNext() {
            while (groupIndex >= group.size() && from < sorted.size()) {
                int to = tieEnd(from);
                group = acceptedTie(from, to);
                groupIndex = 0;
                from = to;
            }
            return groupIndex < group.size();
        }

        @Override
        public Ranked<K> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return group.get(groupIndex++);
        }

        /**
         * End, exclusive, of the run of equal target damage starting at {@code from}; never below
         * {@code from + 1}. Exact float equality is the right test here: a finalist always has a
         * positive effective damage, so no NaN can reach this.
         */
        private int tieEnd(int start) {
            float effective = sorted.get(start).damage.effective();
            int to = start + 1;
            while (to < sorted.size() && sorted.get(to).damage.effective() == effective) {
                to++;
            }
            return to;
        }

        /** Self damage for the half-open range, those passing the acceptance, in rank order. */
        private List<Ranked<K>> acceptedTie(int start, int end) {
            List<Ranked<K>> accepted = new ArrayList<>(end - start);
            for (int index = start; index < end; index++) {
                Scored<K> scored = sorted.get(index);
                DamageResult self = selfDamage.apply(scored.bound.center);
                if (acceptsSelf.test(self)) {
                    accepted.add(new Ranked<>(scored, self));
                }
            }
            accepted.sort(BY_RANK);
            return accepted;
        }
    }
}
