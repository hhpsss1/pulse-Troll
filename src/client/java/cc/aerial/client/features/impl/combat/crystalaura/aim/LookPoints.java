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

package cc.aerial.client.features.impl.combat.crystalaura.aim;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Aim points for the crystal aura.
 *
 * <p>For each action — place on a block face, attack a crystal, attack a predicted crystal box — it finds the
 * rotation that is cheapest to reach from the rotation already on the wire and still performs the action after the
 * rotation pipeline has quantised and dithered it. Pure client-side ray tests; no packets, no world mutation; every
 * entry point traces at most {@code budget} rays.
 *
 * <p>Search structure, in every case: sample points on the visible faces of a box, turn them into candidate
 * rotations with their angle to the wire, sort per ring, then test them lazily in that order and stop at the first
 * ROBUST one — centre and the four slack corners all pass. Later rings are only built when the earlier ones failed
 * and budget remains.
 *
 * <p>Only the client pick is modelled; the passed ranges are eye-to-hit-point distances, and the server's own
 * checks are looser than any range a caller passes here.
 */
public final class LookPoints {
    private LookPoints() {
    }

    /** Face-relative sample proportions per ring. */
    private static final double[] CENTRE = {0.5};
    private static final double[] GRID_5 = grid(5, 0.1, 0.2);
    private static final double[] GRID_9 = grid(9, 0.06, 0.11);
    private static final double[][] BLOCK_RINGS = {CENTRE, GRID_5, GRID_9};

    private static final Comparator<Candidate> BY_ANGLE = Comparator.comparingDouble(Candidate::angle);
    private static final Comparator<Candidate> UP_FIRST_BY_ANGLE =
            Comparator.<Candidate, Boolean>comparing(c -> c.face() != Direction.UP)
                    .thenComparingDouble(Candidate::angle);

    private static double[] grid(int size, double start, double step) {
        double[] values = new double[size];
        for (int i = 0; i < size; i++) {
            values[i] = start + step * i;
        }
        return values;
    }

    /**
     * Look point for placing a crystal on the obsidian or bedrock at a position — any face may be clicked —
     * preferring one that also picks the crystal to attack, so the executor can attack and place in the same tick
     * from a single rotation.
     *
     * <p>Candidates are the visible faces of the block's box, sampled ring by ring: centre, then a 5x5 grid inset
     * to [0.1, 0.9], then a 9x9 grid inset to [0.06, 0.94], every ring ordered UP face first and then by angle to
     * the wire.
     *
     * <p>Ranking, best first: robust and serving the attack, then robust, then centre-only and serving, then
     * centre-only. With a crystal to attack the same candidates are searched twice — block only first, then block
     * and crystal with the remaining budget. Block rays are memoised per rotation, so the second pass only pays
     * for the crystal rays, and a serving candidate is robust only when its corners pass both tests.
     *
     * <p>Without a crystal to attack, the point serves the attack when the face is UP: the future crystal box
     * spans the whole cell horizontally and starts at the top face, so a ray reaching that face from an eye above
     * it is inside the box just before the face point — once the crystal exists the same rotation picks it, since
     * entity hits win when strictly closer. That is subject to the break range and to occluders, which the
     * executor re-checks next tick.
     *
     * <p>{@code sampleBox} overrides the box the candidates are sampled on; the ray test still names the block and
     * the range is still the eye-to-hit-point distance. It exists for a block that does not fill its cell, where
     * the outline bounds make a click aim at the thin slab that is really there rather than at the empty part of
     * the cell above it. Null means the plain unit cube, which is what every full block wants.
     */
    @Nullable
    public static LookPoint forPlacement(Vec3 eye, Rotation wire, BlockPos pos, double placeRange,
                                         @Nullable Entity attackCrystal, double breakRange,
                                         Predicate<Entity> ignore, float slackDeg, int budget,
                                         boolean throughBlocks, @Nullable AABB sampleBox) {
        AABB box = sampleBox == null ? new AABB(pos) : sampleBox;
        List<Direction> faces = visibleFaces(box, eye);
        if (faces.isEmpty()) {
            return null;
        }

        Budget left = new Budget(budget);
        CandidateRings candidates = blockCandidates(box, faces, eye, wire, UP_FIRST_BY_ANGLE);
        MemoTest<BlockHitResult> hitsBlock = new MemoTest<>(left, rotation ->
                RayTests.hitsBlock(eye, rotation, pos, placeRange, ignore, throughBlocks));

        Found<BlockHitResult> plain = search(candidates, left, slackDeg, hitsBlock);
        if (plain == null) {
            return null;
        }
        if (attackCrystal == null) {
            return toBlockLookPoint(plain, pos, plain.hit().getDirection() == Direction.UP);
        }

        Predicate<Entity> pickable = ignoreExcept(attackCrystal, ignore);
        MemoTest<EntityHitResult> hitsCrystal = new MemoTest<>(left, rotation ->
                RayTests.hitsCrystal(eye, rotation, attackCrystal, breakRange, pickable));
        Found<BlockHitResult> serving = search(candidates, left, slackDeg, rotation -> {
            BlockHitResult blockHit = hitsBlock.apply(rotation);
            return blockHit != null && hitsCrystal.apply(rotation) != null ? blockHit : null;
        });

        if (serving != null && (serving.robust() || !plain.robust())) {
            return toBlockLookPoint(serving, pos, true);
        }
        return toBlockLookPoint(plain, pos, false);
    }

    /**
     * Look point that clicks ONLY one face of a block — used to place an obsidian base, which then goes to the
     * neighbour on that side. Same sampling and robustness as a placement look point, ordered by angle, with the
     * extra condition that the pick reports that very face.
     *
     * <p>Null when the eye is not strictly beyond that face — no ray from there can enter the block through it —
     * when nothing hits, or when the budget is spent first. Such a point never serves an attack.
     */
    @Nullable
    public static LookPoint forFace(Vec3 eye, Rotation wire, BlockPos pos, Direction face, double range,
                                    Predicate<Entity> ignore, float slackDeg, int budget, boolean throughBlocks) {
        AABB box = new AABB(pos);
        if (!isOutside(box, face, eye)) {
            return null;
        }

        Budget left = new Budget(budget);
        CandidateRings candidates = blockCandidates(box, List.of(face), eye, wire, BY_ANGLE);
        MemoTest<BlockHitResult> hitsFace = new MemoTest<>(left, rotation -> {
            BlockHitResult hit = RayTests.hitsBlock(eye, rotation, pos, range, ignore, throughBlocks);
            return hit != null && hit.getDirection() == face ? hit : null;
        });
        Found<BlockHitResult> found = search(candidates, left, slackDeg, hitsFace);
        return found == null ? null : toBlockLookPoint(found, pos, false);
    }

    /**
     * Look point for attacking a crystal: candidates on its bounding box ordered by angle, tested against that
     * crystal, which is never ignored by the test even when the caller's filter lists it.
     */
    @Nullable
    public static LookPoint forCrystal(Vec3 eye, Rotation wire, Entity crystal, double breakRange,
                                       Predicate<Entity> ignore, float slackDeg, int budget) {
        Budget left = new Budget(budget);
        Predicate<Entity> pickable = ignoreExcept(crystal, ignore);
        MemoTest<EntityHitResult> hitsCrystal = new MemoTest<>(left, rotation ->
                RayTests.hitsCrystal(eye, rotation, crystal, breakRange, pickable));
        Found<EntityHitResult> found =
                search(boxCandidates(crystal.getBoundingBox(), eye, wire), left, slackDeg, hitsCrystal);
        return found == null ? null : boxLookPoint(found, found.hit().getLocation());
    }

    /**
     * The crystal look point for a crystal that does not exist yet: candidates on the given box, tested against
     * the phantom-box confirmation, with the geometric entry point as the hit.
     */
    @Nullable
    public static LookPoint forPhantomBox(Vec3 eye, Rotation wire, AABB box, double breakRange,
                                          Predicate<Entity> ignore, float slackDeg, int budget) {
        Budget left = new Budget(budget);
        MemoTest<Vec3> hitsBox = new MemoTest<>(left, rotation ->
                RayTests.hitsPhantomBox(eye, rotation, box, breakRange, ignore));
        Found<Vec3> found = search(boxCandidates(box, eye, wire), left, slackDeg, hitsBox);
        return found == null ? null : boxLookPoint(found, found.hit());
    }

    /**
     * The lazy search shared by every look point: walk the candidates in priority order and return the first
     * ROBUST one — its centre rotation passes the test and so do the four slack corners. Every ray is charged to
     * the budget as it is traced; once the budget is exhausted, or the candidates run out, the FIRST candidate
     * whose centre passed is returned as non-robust, or null when none passed. A candidate whose evaluation is
     * cut short by the budget counts as not robust.
     *
     * <p>Why corners: what goes on the wire is the requested rotation with its delta rounded onto the sensitivity
     * grid, dithered by up to 0.4 of a step and pitch-clamped, so the wire rotation may differ from the requested
     * one by up to 0.9 of a step per axis. A look point is only worth requesting when the whole slack square still
     * performs the action, and the four corners are the cheap proxy for that square. A zero slack skips them.
     */
    @Nullable
    private static <T> Found<T> search(CandidateRings candidates, Budget budget, float slackDeg,
                                       Function<Rotation, T> test) {
        Found<T> fallback = null;
        for (Candidate candidate : candidates) {
            if (budget.isExhausted()) {
                break;
            }
            T hit = test.apply(candidate.rotation());
            if (hit == null) {
                continue;
            }
            if (isRobust(candidate.rotation(), slackDeg, budget, test)) {
                return new Found<>(candidate, hit, true);
            }
            if (fallback == null) {
                fallback = new Found<>(candidate, hit, false);
            }
        }
        return fallback;
    }

    /** All four corners pass; false as soon as one fails or the budget is exhausted. */
    private static <T> boolean isRobust(Rotation rotation, float slackDeg, Budget budget,
                                        Function<Rotation, T> test) {
        if (slackDeg <= 0f) {
            return true;
        }
        for (int index = 0; index < 4; index++) {
            if (budget.isExhausted() || test.apply(corner(rotation, slackDeg, index)) == null) {
                return false;
            }
        }
        return true;
    }

    /** One corner of the slack square, with the pitch clamped exactly as the wire clamps it. */
    private static Rotation corner(Rotation rotation, float slackDeg, int index) {
        float yawSign = (index & 1) == 0 ? -1f : 1f;
        float pitchSign = (index & 2) == 0 ? -1f : 1f;
        return new Rotation(
                rotation.yaw() + yawSign * slackDeg,
                Mth.clamp(rotation.pitch() + pitchSign * slackDeg, -90f, 90f));
    }

    /** The faces of a box with the eye strictly beyond their plane; at most three. */
    private static List<Direction> visibleFaces(AABB box, Vec3 eye) {
        List<Direction> faces = new ArrayList<>(3);
        for (Direction direction : Direction.values()) {
            if (isOutside(box, direction, eye)) {
                faces.add(direction);
            }
        }
        return faces;
    }

    /**
     * True when the eye lies strictly beyond the plane of a face. From such an eye the segment to any point of
     * that face stays beyond the plane until the point itself, so the sampled point is the first surface point of
     * the box the ray meets.
     */
    private static boolean isOutside(AABB box, Direction face, Vec3 eye) {
        double coordinate = eye.get(face.getAxis());
        return face.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? coordinate > box.max(face.getAxis())
                : coordinate < box.min(face.getAxis());
    }

    /** The three block rings over the visible faces, each sorted and built on first use. */
    private static CandidateRings blockCandidates(AABB box, List<Direction> faces, Vec3 eye, Rotation wire,
                                                  Comparator<Candidate> order) {
        List<java.util.function.Supplier<List<Candidate>>> rings = new ArrayList<>(BLOCK_RINGS.length);
        for (double[] grid : BLOCK_RINGS) {
            rings.add(() -> {
                List<Candidate> ring = faceRing(box, faces, grid, eye, wire);
                ring.sort(order);
                return ring;
            });
        }
        return new CandidateRings(rings);
    }

    /**
     * Candidates on an entity box: the first ring is the centres of the visible faces plus the point of the box
     * nearest to the eye, the second is the 5x5 grids of the visible faces; both sorted by angle.
     *
     * <p>An eye inside the box has no visible face, and its nearest point is the eye itself, which maps to the
     * wire rotation — correct, because a ray starting inside the box hits it at distance zero for every rotation.
     */
    private static CandidateRings boxCandidates(AABB box, Vec3 eye, Rotation wire) {
        List<Direction> faces = visibleFaces(box, eye);
        List<java.util.function.Supplier<List<Candidate>>> rings = List.of(
                () -> {
                    List<Candidate> ring = faceRing(box, faces, CENTRE, eye, wire);
                    ring.add(candidate(BoxSampling.nearestPoint(box, eye), null, eye, wire));
                    List<Candidate> distinct = distinctByRotation(ring);
                    distinct.sort(BY_ANGLE);
                    return distinct;
                },
                () -> {
                    List<Candidate> ring = faceRing(box, faces, GRID_5, eye, wire);
                    ring.sort(BY_ANGLE);
                    return ring;
                });
        return new CandidateRings(rings);
    }

    /**
     * One ring: every pair of grid proportions on every visible face, minus the middle cell of grids larger than
     * the centre ring, which is the centre again.
     */
    private static List<Candidate> faceRing(AABB box, List<Direction> faces, double[] grid, Vec3 eye,
                                            Rotation wire) {
        int size = grid.length;
        int middle = size / 2;
        List<Candidate> ring = new ArrayList<>(faces.size() * size * size);
        for (Direction face : faces) {
            for (int cell = 0; cell < size * size; cell++) {
                int i = cell / size;
                int j = cell % size;
                if (size > 1 && i == middle && j == middle) {
                    continue;
                }
                ring.add(candidate(BoxSampling.samplePointOnSide(box, face, grid[i], grid[j]), face, eye, wire));
            }
        }
        return ring;
    }

    /** Keeps the first candidate per distinct rotation, preserving order. */
    private static List<Candidate> distinctByRotation(List<Candidate> candidates) {
        Set<Rotation> seen = new LinkedHashSet<>();
        List<Candidate> distinct = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            if (seen.add(candidate.rotation())) {
                distinct.add(candidate);
            }
        }
        return distinct;
    }

    /**
     * The rotation that looks at a point and its angle to the wire; the eye itself — the nearest point of a box
     * that contains the eye — maps to the wire rotation.
     */
    private static Candidate candidate(Vec3 point, @Nullable Direction face, Vec3 eye, Rotation wire) {
        Rotation rotation = point.distanceToSqr(eye) == 0.0 ? wire : Rotation.lookingAt(point, eye);
        return new Candidate(rotation, face, wire.angleTo(rotation));
    }

    /** The caller's filter with one entity always pickable — a ray meant to hit it must be allowed to. */
    private static Predicate<Entity> ignoreExcept(Entity entity, Predicate<Entity> ignore) {
        return other -> other != entity && ignore.test(other);
    }

    private static LookPoint toBlockLookPoint(Found<BlockHitResult> found, BlockPos pos, boolean servesAttack) {
        return new LookPoint(found.hit().getLocation(), found.candidate().rotation(),
                found.hit().getDirection(), pos, found.candidate().angle(), servesAttack);
    }

    private static LookPoint boxLookPoint(Found<?> found, Vec3 point) {
        return new LookPoint(point, found.candidate().rotation(), null, null, found.candidate().angle(), true);
    }

    /** One sample: the rotation that looks at it, the face it was sampled on, and its angle to the wire. */
    private record Candidate(Rotation rotation, @Nullable Direction face, float angle) {
    }

    /** A candidate whose centre ray hit, with the hit and whether all four slack corners hit as well. */
    private record Found<T>(Candidate candidate, T hit, boolean robust) {
    }

    /**
     * The rings of one search, each built on first use and then shared.
     *
     * <p>A placement search walks the candidates twice — once for the block alone, once for block and crystal —
     * so the rings must survive the first walk rather than being regenerated. Iterating again is therefore free
     * for every ring already built.
     */
    private static final class CandidateRings implements Iterable<Candidate> {
        private final List<java.util.function.Supplier<List<Candidate>>> suppliers;
        private final List<List<Candidate>> built;

        private CandidateRings(List<java.util.function.Supplier<List<Candidate>>> suppliers) {
            this.suppliers = suppliers;
            this.built = new ArrayList<>(java.util.Collections.nCopies(suppliers.size(), null));
        }

        private List<Candidate> ring(int index) {
            List<Candidate> ring = built.get(index);
            if (ring == null) {
                ring = suppliers.get(index).get();
                built.set(index, ring);
            }
            return ring;
        }

        @Override
        public java.util.Iterator<Candidate> iterator() {
            return new java.util.Iterator<>() {
                private int ringIndex;
                private int inRing;

                @Override
                public boolean hasNext() {
                    while (ringIndex < suppliers.size() && inRing >= ring(ringIndex).size()) {
                        ringIndex++;
                        inRing = 0;
                    }
                    return ringIndex < suppliers.size();
                }

                @Override
                public Candidate next() {
                    if (!hasNext()) {
                        throw new java.util.NoSuchElementException();
                    }
                    return ring(ringIndex).get(inRing++);
                }
            };
        }
    }

    /** Ray tests still allowed in one search; each traced ray charges one unit. */
    private static final class Budget {
        private int remaining;

        private Budget(int remaining) {
            this.remaining = remaining;
        }

        private boolean isExhausted() {
            return remaining <= 0;
        }

        /** Charges one ray test; false, and nothing charged, when the budget is exhausted. */
        private boolean charge() {
            if (remaining <= 0) {
                return false;
            }
            remaining--;
            return true;
        }
    }

    /**
     * A ray test memoised per rotation for one search: each distinct rotation is charged and traced at most once,
     * and a cached outcome is free. A test that cannot be charged answers null, without caching; the search loop
     * stops on an exhausted budget anyway.
     */
    private static final class MemoTest<T> implements Function<Rotation, T> {
        private final Budget budget;
        private final Function<Rotation, T> trace;
        private final Map<Rotation, T> cache = new HashMap<>();
        private final Set<Rotation> known = new java.util.HashSet<>();

        private MemoTest(Budget budget, Function<Rotation, T> trace) {
            this.budget = budget;
            this.trace = trace;
        }

        @Override
        public T apply(Rotation rotation) {
            if (known.contains(rotation)) {
                return cache.get(rotation);
            }
            if (!budget.charge()) {
                return null;
            }
            T hit = trace.apply(rotation);
            known.add(rotation);
            cache.put(rotation, hit);
            return hit;
        }
    }
}
