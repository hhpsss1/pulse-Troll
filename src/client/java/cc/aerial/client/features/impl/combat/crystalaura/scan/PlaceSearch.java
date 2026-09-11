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
import cc.aerial.client.features.impl.combat.crystalaura.calc.ExplosionMath;
import cc.aerial.client.features.impl.combat.crystalaura.calc.ShapeSource;
import cc.aerial.client.features.impl.combat.crystalaura.config.PlaceSettings;
import cc.aerial.client.features.impl.combat.crystalaura.state.CrystalLedger;
import cc.aerial.client.features.impl.combat.crystalaura.world.TargetEntry;
import cc.aerial.client.features.impl.combat.crystalaura.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Finds the obsidian or bedrock block to place a crystal on right now. One instance per tick, since it holds the
 * tick's world view; main thread only.
 *
 * <h2>Candidate cells</h2>
 * <p>The integer cube of radius {@code range + 1} around the eye. Nothing is allocated for a cell that fails one
 * of these checks, in this order:
 * <ul>
 *   <li>the eye is closer than the configured range to the block's unit cube. The range is the eye-to-hit-point
 *       distance of the later click; the box distance is its lower bound, and the exact test is the look point
 *       search. The server's own check is looser than any configurable range.</li>
 *   <li>the crystal item accepts only obsidian or bedrock with an empty block above. The cell state is read once
 *       and reused for both halves, which is the placeability rule verbatim at two block reads instead of three.</li>
 *   <li>no placement of ours is still pending for the block above.</li>
 *   <li>the crystal that would stand there is within the break range of the eye — a crystal we could never
 *       detonate is a wasted one that also blocks its own column.</li>
 *   <li>the entity column above is clear of anything but ignored entities. This is the only entity query per cell,
 *       so the ranker runs it just after a target's damage bound has passed.</li>
 * </ul>
 *
 * <h2>Acceptance and ordering</h2>
 * <p>Target: the effective damage reaches the minimum, or the lethal override applies and the hit kills. Self: the
 * effective self damage is within the limit, and with anti-suicide the self health loss plus absorbed damage stays
 * below our own health plus absorption. Ordering: higher target damage first; ties go to lower self damage, then
 * to the cell nearer the eye.
 *
 * <h2>Deliberate deviations</h2>
 * <ul>
 *   <li>A candidate whose target damage is exactly zero — victim out of range, skipped, or fully resisted — is
 *       never accepted, even with a minimum of zero: vanilla would not hurt that victim at all.</li>
 *   <li>Self damage costs 45 rays, so only a bounded number of finalists is ever tried; when every one of them
 *       fails the self checks the result is empty although a later finalist might have passed.</li>
 *   <li>{@link #candidates} is the whole accepted ranking and not just its head, so a caller that cannot use the
 *       best placement can demote instead of idling.</li>
 * </ul>
 */
public final class PlaceSearch {
    private final WorldView view;
    private final DamageCalculator calc;
    private final PlaceSettings settings;
    private final CrystalLedger ledger;
    private final Predicate<Entity> ignore;

    private final float range;
    private final double rangeSq;

    /**
     * The break range squared: a cell whose crystal would stand outside it is refused outright, because we could
     * never detonate that crystal.
     *
     * <p>The place range reaches farther than the break range and nothing linked the two, so the band between them
     * produced crystals no attack ray can reach: the attack is dropped for good, the planner's own-blast guard
     * skips out-of-reach own crystals, and the crystal keeps occupying its column — the aura pays for a crystal
     * that can do nothing and loses the block under it. Whether a cell qualifies is re-decided every tick, so
     * stepping closer brings it straight back.
     */
    private final double breakReachSq;

    /** Bound-only ranker, whose queue is never used, shared by every {@link #upperBound} call. */
    private ExplosionRanker<BlockPos> bounds;

    public PlaceSearch(WorldView view, DamageCalculator calc, PlaceSettings settings,
                       CrystalLedger ledger, Predicate<Entity> ignore, float breakRange) {
        this.view = view;
        this.calc = calc;
        this.settings = settings;
        this.ledger = ledger;
        this.ignore = ignore;
        this.range = settings.range.getValue().floatValue();
        this.rangeSq = (double) range * range;
        this.breakReachSq = (double) breakRange * breakRange;
    }

    /** The configured place range, read once for the tick. */
    public float range() {
        return range;
    }

    /**
     * Every placement this tick's ranker accepts, best first, evaluated on demand.
     *
     * <p>The cell loop runs EAGERLY here, before anything is pulled. The ranker is single use — the cell loop
     * fills its queue and the first pull drains it — so filling the queue lazily would make the scan depend on
     * whether the caller pulls at all, and a second iteration would find an empty queue instead of the same list.
     * Filling first and keeping only the ranking lazy gives the result one honest shape: it may be iterated ONCE,
     * its head costs exactly what {@link #best} costs, and every further element costs one more 45-ray self-damage
     * evaluation.
     */
    public Iterable<PlaceCandidate> candidates() {
        ExplosionRanker<BlockPos> ranker = ranker(null);
        lastRanker = ranker;
        collectCells(ranker);
        Iterable<Ranked<BlockPos>> ranked = ranker.ranked();
        return () -> {
            Iterator<Ranked<BlockPos>> inner = ranked.iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return inner.hasNext();
                }

                @Override
                public PlaceCandidate next() {
                    return toCandidate(inner.next());
                }
            };
        };
    }

    /** Best block to place a crystal on right now, or null. */
    @Nullable
    public PlaceCandidate best() {
        Iterator<PlaceCandidate> iterator = candidates().iterator();
        return iterator.hasNext() ? iterator.next() : null;
    }

    /**
     * Evaluates one explicit position with the acceptance rules of {@link #best}: range, placeability, no pending
     * placement, and a free column.
     *
     * <p>{@code requirePlaceable} is false for an obsidian base that does not exist yet, where the caller has
     * already checked the cell itself. {@code sourceOverride} wraps both the targets' and the local player's shape
     * source, which is how a future base is made visible to the exposure rays.
     */
    @Nullable
    public PlaceCandidate evaluate(BlockPos pos, boolean requirePlaceable,
                                   @Nullable UnaryOperator<ShapeSource> sourceOverride) {
        double distSq = ScanGeometry.blockDistanceSq(pos.getX(), pos.getY(), pos.getZ(), view.eye());
        if (distSq >= rangeSq || !admits(pos, requirePlaceable)) {
            return null;
        }
        ExplosionRanker<BlockPos> ranker = ranker(sourceOverride);
        ranker.offer(pos.immutable(), ExplosionMath.crystalCenter(pos), distSq);
        Ranked<BlockPos> best = ranker.best();
        return best == null ? null : toCandidate(best);
    }

    /** Convenience overload for the ordinary case: the cell must be placeable and the world is unmodified. */
    @Nullable
    public PlaceCandidate evaluate(BlockPos pos) {
        return evaluate(pos, true, null);
    }

    /**
     * Largest upper bound over the targets that could pass the target acceptance for a crystal on this block, or 0
     * when none could. Pure arithmetic, no placeability check — the pruning key of the base-place search.
     */
    public float upperBound(BlockPos pos) {
        if (bounds == null) {
            bounds = ranker(null);
        }
        return bounds.bound(ExplosionMath.crystalCenter(pos));
    }

    /**
     * The cell loop.
     *
     * <p>The cell state is read once and reused for the base test and for the air test above, so an obsidian or
     * bedrock cell costs two block reads instead of three; the reused mutable position also spares an allocation
     * per cell. Accept and reject are unchanged: base state plus air above is the placeability rule verbatim.
     */
    private void collectCells(ExplosionRanker<BlockPos> ranker) {
        cellsFound = 0;
        Vec3 eye = view.eye();
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
        for (BlockPos cell : ScanGeometry.blocksInCuboid(eye, range + 1f)) {
            int x = cell.getX();
            int y = cell.getY();
            int z = cell.getZ();
            double distSq = ScanGeometry.blockDistanceSq(x, y, z, eye);
            if (distSq >= rangeSq || !isBase(view.state(cell))) {
                continue;
            }
            above.set(x, y + 1, z);
            if (!view.state(above).isAir() || ledger.isPending(above)) {
                continue;
            }
            if (ScanGeometry.crystalDistanceSq(x, y, z, eye) >= breakReachSq) {
                continue;
            }
            BlockPos immutable = cell.immutable();
            cellsFound++;
            ranker.offer(immutable, ExplosionMath.crystalCenter(immutable), distSq);
        }
    }

    /**
     * How many cells the last sweep found a crystal could stand on, before any damage rule was applied.
     *
     * <p>Zero and "found some, none worth it" are the two halves of an idle tick and they have nothing in
     * common: the first means there is no obsidian or bedrock in reach at all, the second means the damage
     * thresholds are the thing saying no.
     */
    public int cellsFound() {
        return cellsFound;
    }

    /** The best damage numbers the last sweep saw, accepted or not; for the debug line only. */
    public String peaks() {
        return lastRanker == null ? "bound=? dmg=? self=?" : lastRanker.peaks();
    }

    @Nullable
    private ExplosionRanker<BlockPos> lastRanker;

    private int cellsFound;

    /** The placeability rules except the range and the column. */
    private boolean admits(BlockPos pos, boolean requirePlaceable) {
        if (requirePlaceable && !view.isPlaceable(pos)) {
            return false;
        }
        if (ScanGeometry.crystalDistanceSq(pos.getX(), pos.getY(), pos.getZ(), view.eye()) >= breakReachSq) {
            return false;
        }
        return !ledger.isPending(pos.above());
    }

    /** The crystal item's own base test. */
    private static boolean isBase(BlockState state) {
        return state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK);
    }

    private ExplosionRanker<BlockPos> ranker(@Nullable UnaryOperator<ShapeSource> sourceOverride) {
        Function<TargetEntry, ShapeSource> sourceOf = target ->
                sourceOverride == null ? target.source() : sourceOverride.apply(target.source());
        ShapeSource self = sourceOverride == null
                ? view.selfSource()
                : sourceOverride.apply(view.selfSource());
        return new ExplosionRanker<>(view, calc, settings,
                TargetEntry::placeState, sourceOf, self,
                pos -> view.isColumnFree(pos.above(), ignore));
    }

    private static PlaceCandidate toCandidate(Ranked<BlockPos> ranked) {
        return new PlaceCandidate(ranked.key(), ranked.center(), ranked.target(),
                ranked.damage(), ranked.selfDamage());
    }
}
