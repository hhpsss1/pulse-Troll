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

package cc.aerial.client.features.impl.combat.crystalaura.base;

import cc.aerial.client.features.impl.combat.crystalaura.aim.LookPoint;
import cc.aerial.client.features.impl.combat.crystalaura.aim.LookPoints;
import cc.aerial.client.features.impl.combat.crystalaura.aim.RayTests;
import cc.aerial.client.features.impl.combat.crystalaura.aim.Rotation;
import cc.aerial.client.features.impl.combat.crystalaura.calc.ExplosionMath;
import cc.aerial.client.features.impl.combat.crystalaura.calc.OverrideShapeSource;
import cc.aerial.client.features.impl.combat.crystalaura.config.BasePlaceSettings;
import cc.aerial.client.features.impl.combat.crystalaura.scan.PlaceCandidate;
import cc.aerial.client.features.impl.combat.crystalaura.scan.PlaceSearch;
import cc.aerial.client.features.impl.combat.crystalaura.scan.ScanGeometry;
import cc.aerial.client.features.impl.combat.crystalaura.world.TargetEntry;
import cc.aerial.client.features.impl.combat.crystalaura.world.WorldView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * Finds an obsidian base to place so that a crystal on it beats the best direct placement by the configured gain.
 * One instance per tick, since it holds the tick's world view; main thread only.
 *
 * <h2>Vanilla obsidian placement — TWO forms</h2>
 * <p>The block item builds a placement context, and that context decides: when the clicked block can be replaced
 * the obsidian takes its place, otherwise it goes to the neighbour on the hit face. Both branches are searched
 * here and the plan carries which one was taken.
 *
 * <ul>
 *   <li>the RELATIVE form — a base placed by clicking a NON-replaceable neighbour on the face pointing back at
 *       the cell;</li>
 *   <li>the REPLACE-IN-PLACE form — the base cell is clicked DIRECTLY and the obsidian takes its place. The hit
 *       direction is then read by nobody. This is the form a player uses on a snow layer or a fire, and it is the
 *       only one available when every neighbour of the cell is itself replaceable. It also matters for the aim: a
 *       full-footprint outline glued to the bottom of the cell — a one-layer snow is 2/16 tall, a fire 1/16 —
 *       sits flush against the top face of the block below, and that neighbour's UP face is the only clickable
 *       one left once the cell above has been forced to air.</li>
 * </ul>
 *
 * <p>Before the item is used at all, the clicked block may consume the click unless the player sneaks with
 * something in hand. Interactable neighbours are therefore skipped unless sneaking.
 *
 * <h2>Cost order</h2>
 * <p>Cells of the cube around the eye go through the cheap stage first; nothing is allocated for a cell that
 * fails one of these: box distance to the eye, at least one target inside the explosion cut-off, the state checks
 * (air above first, then replaceable, the order that keeps the placement context out of every cell whose ceiling
 * is solid), and finally the damage bound.
 *
 * <p>Survivors are queued by bound and drained in descending bound, stopping as soon as the next bound is below
 * the best effective damage found — no later cell can reach or tie it. Only a drained cell pays the expensive
 * admissions: click faces, the replace-in-place test, entity obstruction, and then the rays.
 */
public final class BasePlaceSearch {
    private static final float PERCENT = 100f;

    /** Cells that get the look point search, best damage first. */
    private static final int LOOK_CANDIDATES = 4;

    /** Ray budget of one look point search. */
    private static final int LOOK_BUDGET = 32;

    private static final Comparator<Cell> BY_BOUND = Comparator.comparingDouble(cell -> -cell.bound);

    private static final Comparator<ScoredCell> BY_RANK =
            Comparator.<ScoredCell>comparingDouble(s -> -s.candidate.damage().effective())
                    .thenComparingDouble(s -> s.candidate.selfDamage().effective())
                    .thenComparingDouble(s -> s.cell.distSq);

    private final WorldView view;
    private final PlaceSearch placeSearch;
    private final BasePlaceSettings settings;
    private final Predicate<Entity> ignore;
    private final Rotation wire;
    private final float slackDeg;
    private final boolean throughBlocks;

    private final LocalPlayer player;
    private final ClientLevel level;
    private final boolean sneaking;
    private final CollisionContext placementContext;
    private final CollisionContext pickContext;
    private final ItemStack obsidianStack = new ItemStack(Items.OBSIDIAN);
    private final BlockState obsidianState = Blocks.OBSIDIAN.defaultBlockState();

    private final double rangeSq;
    private final float cellReach;
    private final double cellReachSq;

    private final BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();

    public BasePlaceSearch(WorldView view, PlaceSearch placeSearch, BasePlaceSettings settings,
                           Predicate<Entity> ignore, Rotation wire, float slackDeg, boolean throughBlocks) {
        this.view = view;
        this.placeSearch = placeSearch;
        this.settings = settings;
        this.ignore = ignore;
        this.wire = wire;
        this.slackDeg = slackDeg;
        this.throughBlocks = throughBlocks;

        Minecraft minecraft = Minecraft.getInstance();
        this.player = minecraft.player;
        this.level = minecraft.level;
        if (player == null || level == null) {
            throw new IllegalStateException("BasePlaceSearch requires a level and a player");
        }
        this.sneaking = player.isSecondaryUseActive();
        this.placementContext = CollisionContext.placementContext(player);
        this.pickContext = CollisionContext.of(player);

        float range = settings.range.getValue().floatValue();
        this.rangeSq = (double) range * range;
        // A base cell may sit up to one block beyond the range — the clicked NEIGHBOUR carries that range, not
        // the base — but never beyond the range the placement scan enforces on the crystal cell itself.
        this.cellReach = Math.min(range + 1f, placeSearch.range());
        this.cellReachSq = (double) cellReach * cellReach;
    }

    /** Best obsidian base to place, with the gain rule applied against the best direct placement, or null. */
    @Nullable
    public BasePlan best(@Nullable PlaceCandidate bestExisting) {
        float floor = bestExisting == null
                ? 0f
                : bestExisting.damage().effective() * (1f + settings.minGain.getValue().floatValue() / PERCENT);
        for (ScoredCell entry : refine(collect(floor), floor)) {
            BasePlan plan = aim(entry);
            if (plan != null) {
                return plan;
            }
        }
        return null;
    }

    /** The cheap stage: geometry, then state, then the bound. Everything expensive waits for the refinement. */
    private PriorityQueue<Cell> collect(float floor) {
        PriorityQueue<Cell> heap = new PriorityQueue<>(BY_BOUND);
        Vec3 eye = view.eye();
        for (BlockPos cell : ScanGeometry.blocksInCuboid(eye, cellReach)) {
            double distSq = ScanGeometry.blockDistanceSq(cell.getX(), cell.getY(), cell.getZ(), eye);
            if (distSq >= cellReachSq || !reachesAnyTarget(cell) || !isBaseCell(cell)) {
                continue;
            }
            BlockPos immutable = cell.immutable();
            float bound = placeSearch.upperBound(immutable);
            if (bound > 0f && bound >= floor) {
                heap.add(new Cell(immutable, distSq, bound));
            }
        }
        return heap;
    }

    /**
     * The explosion cut-off for at least one target: a crystal standing on this cell explodes at its own centre,
     * and a victim whose feet are farther than twice the radius from it is skipped by vanilla whatever the
     * exposure, so such a cell can never score. Same arithmetic as the distance fraction, without allocating the
     * centre.
     */
    private boolean reachesAnyTarget(BlockPos cell) {
        double centerX = cell.getX() + 0.5;
        double centerY = cell.getY() + 1;
        double centerZ = cell.getZ() + 0.5;
        for (TargetEntry target : view.targets()) {
            Vec3 feet = target.placeState().feet();
            double distSq = Mth.lengthSquared(centerX - feet.x, centerY - feet.y, centerZ - feet.z);
            if (Math.sqrt(distSq) / (double) ExplosionMath.DOUBLE_RADIUS <= 1.0) {
                return true;
            }
        }
        return false;
    }

    /**
     * State checks: the block above the cell is air and the cell itself is replaceable by obsidian. The air test
     * comes first because it never allocates, while the replaceability test builds a placement context for every
     * non-air cell.
     */
    private boolean isBaseCell(BlockPos cell) {
        above.set(cell.getX(), cell.getY() + 1, cell.getZ());
        if (!view.state(above).isAir()) {
            return false;
        }
        return isReplaceable(view.state(cell), cell);
    }

    /** Click targets, obstruction and the rays, cheapest first; the scored cell or null. */
    @Nullable
    private ScoredCell admit(Cell cell) {
        int faces = clickFaces(cell.pos);
        boolean self = isSelfClickable(cell.pos);
        if ((faces == 0 && !self) || !isUnobstructed(cell.pos)) {
            return null;
        }
        PlaceCandidate candidate = score(cell.pos);
        return candidate == null ? null : new ScoredCell(cell, faces, self, candidate);
    }

    /**
     * Whether the base cell itself may be clicked so the obsidian REPLACES what stands in it: inside the range —
     * the click lands on this cell, so it carries the range, not a neighbour — and the replace-in-place rule.
     * The replaceability term is re-read rather than inherited so the rule stays one self-contained statement.
     */
    private boolean isSelfClickable(BlockPos pos) {
        if (ScanGeometry.blockDistanceSq(pos.getX(), pos.getY(), pos.getZ(), view.eye()) >= rangeSq) {
            return false;
        }
        BlockState state = view.state(pos);
        return BaseRules.isReplaceInPlace(level, pos, state, isReplaceable(state, pos), pickContext, sneaking);
    }

    /** The replace-clicked test for obsidian at a position; air is answered without building the context. */
    private boolean isReplaceable(BlockState state, BlockPos pos) {
        return state.isAir() || BaseRules.canBeReplacedWith(state, pos, obsidianStack);
    }

    /** Bit set of the neighbours of a cell that can be clicked to place the base. */
    private int clickFaces(BlockPos block) {
        int mask = 0;
        for (Direction dir : Direction.values()) {
            neighbour.setWithOffset(block, dir);
            if (isClickable(neighbour)) {
                mask |= 1 << dir.ordinal();
            }
        }
        return mask;
    }

    /**
     * A neighbour within the range — box distance, the eye-to-hit-point lower bound — that the obsidian will not
     * replace and whose use will not consume the click.
     */
    private boolean isClickable(BlockPos pos) {
        if (ScanGeometry.blockDistanceSq(pos.getX(), pos.getY(), pos.getZ(), view.eye()) >= rangeSq) {
            return false;
        }
        BlockState state = view.state(pos);
        return !isReplaceable(state, pos) && (!BaseRules.isInteractable(state) || sneaking);
    }

    /**
     * The vanilla unobstructed test for the obsidian at this cell: its collision shape, moved into place, must
     * not intersect any entity that blocks building.
     *
     * <p>Deviation: entities the caller's filter ignores are treated as absent, like the crystal column test
     * does, with one exception below.
     */
    private boolean isUnobstructed(BlockPos block) {
        VoxelShape shape = obsidianState.getCollisionShape(level, block, placementContext);
        if (shape.isEmpty()) {
            return true;
        }
        VoxelShape moved = shape.move(block);
        for (Entity entity : level.getEntities((Entity) null, moved.bounds())) {
            if (obstructs(entity, moved)) {
                return false;
            }
        }
        return true;
    }

    /**
     * An end crystal ALWAYS blocks the obsidian, even one this tick is about to detonate, which the caller's
     * filter would otherwise hide.
     *
     * <p>Server-side such a placement does succeed — the attack precedes the use and the explosion runs first —
     * but the obsidian would land in the very cell the crystal stood in, directly on top of the block the aura is
     * cycling on. A placement needs the block above the obsidian to be AIR, so that turns a working spot into a
     * dead one and walks the base upwards a block per crystal. Everything else follows the caller's filter.
     */
    private boolean obstructs(Entity entity, VoxelShape shape) {
        if (entity.isRemoved() || !entity.blocksBuilding) {
            return false;
        }
        if (!(entity instanceof EndCrystal) && ignore.test(entity)) {
            return false;
        }
        return Shapes.joinIsNotEmpty(shape, Shapes.create(entity.getBoundingBox()), BooleanOp.AND);
    }

    /**
     * Drains the heap in descending bound, admitting and scoring each cell and keeping candidates at or above
     * the floor, until the next bound is below the BEST effective damage found so far: the bound is an upper
     * bound, so no later cell can reach or tie it. The test is non-strict, keeping exact ties alive for the rank
     * tie-breaks.
     *
     * <p>The result is ordered by rank and cut to the cells that get the look point search, chosen after the
     * refinement, from the cells whose damage was actually evaluated. That group can hold fewer cells than the
     * cap: a cell whose bound already lost to the best effective damage is never evaluated and can therefore no
     * longer serve as the fallback of a cell the look point search fails on. The best plan itself is unaffected,
     * because the bound is an upper bound.
     */
    private List<ScoredCell> refine(PriorityQueue<Cell> heap, float floor) {
        List<ScoredCell> scored = new ArrayList<>();
        float bestEffective = 0f;
        Cell next = heap.poll();
        while (next != null && next.bound >= bestEffective) {
            ScoredCell entry = admit(next);
            if (entry != null && entry.candidate.damage().effective() >= floor) {
                scored.add(entry);
                bestEffective = Math.max(bestEffective, entry.candidate.damage().effective());
            }
            next = heap.poll();
        }
        scored.sort(BY_RANK);
        return scored.size() > LOOK_CANDIDATES ? scored.subList(0, LOOK_CANDIDATES) : scored;
    }

    /** The crystal placement this base would enable, with the future obsidian visible to the exposure rays. */
    @Nullable
    private PlaceCandidate score(BlockPos pos) {
        return placeSearch.evaluate(pos, false, source ->
                new OverrideShapeSource(source, pos.getX(), pos.getY(), pos.getZ(), Shapes.block()));
    }

    /**
     * The aim for one scored cell, over BOTH placement forms: the neighbours the cell reported, in face order,
     * and, when the cell itself is clickable, the replace-in-place form. Whichever is reachable wins; when both
     * are, the one whose look point needs the smaller turn does — that is the cheaper tick, and the one a
     * duplicate-rotation check prefers.
     */
    @Nullable
    private BasePlan aim(ScoredCell entry) {
        BlockPos block = entry.cell.pos;
        BasePlan relative = null;
        for (Direction dir : Direction.values()) {
            if (entry.hasFace(dir)) {
                relative = lookThrough(block, dir, entry.candidate);
                if (relative != null) {
                    break;
                }
            }
        }
        BasePlan self = entry.selfClickable ? lookSelf(block, entry.candidate) : null;
        if (relative == null) {
            return self;
        }
        if (self == null) {
            return relative;
        }
        return self.look().angle() < relative.look().angle() ? self : relative;
    }

    /** A look point on the face of the neighbour that points back at the block. */
    @Nullable
    private BasePlan lookThrough(BlockPos block, Direction dir, PlaceCandidate candidate) {
        BlockPos clickPos = block.relative(dir);
        Direction face = dir.getOpposite();
        LookPoint look = LookPoints.forFace(view.eye(), wire, clickPos, face,
                settings.range.getValue(), ignore, slackDeg, LOOK_BUDGET, throughBlocks);
        return look == null ? null : new BasePlan(block, clickPos, face, look, candidate);
    }

    /**
     * The replace-in-place aim: a placement look point on the block ITSELF, which accepts ANY visible face —
     * the replace-clicked branch makes vanilla ignore the hit direction, so unlike the relative form there is no
     * face to constrain and a face-constrained search would be the wrong one.
     *
     * <p>The candidates are sampled on the OUTLINE bounds, not on the unit cube: the blocks this form exists for
     * do not fill their cell, so most of the cube names rays that never terminate on it. A null box means the
     * shape is empty and nothing can be picked, which the clickable test already refused — belt and braces.
     */
    @Nullable
    private BasePlan lookSelf(BlockPos block, PlaceCandidate candidate) {
        AABB sampleBox = RayTests.pickBox(block);
        if (sampleBox == null) {
            return null;
        }
        double range = settings.range.getValue();
        LookPoint look = LookPoints.forPlacement(view.eye(), wire, block, range,
                null, range, ignore, slackDeg, LOOK_BUDGET, throughBlocks, sampleBox);
        return look == null ? null : new BasePlan(block, block, null, look, candidate);
    }

    /** A cell that passed the geometry, state and bound checks; the heap is ordered by the bound. */
    private static final class Cell {
        private final BlockPos pos;
        private final double distSq;
        private final float bound;

        private Cell(BlockPos pos, double distSq, float bound) {
            this.pos = pos;
            this.distSq = distSq;
            this.bound = bound;
        }
    }

    /**
     * A cell that passed the admissions. The face mask is the relative form, the self flag the replace-in-place
     * form, and the candidate the placement it justifies. At least one of the two forms is set, or the cell
     * would have been refused.
     */
    private static final class ScoredCell {
        private final Cell cell;
        private final int faces;
        private final boolean selfClickable;
        private final PlaceCandidate candidate;

        private ScoredCell(Cell cell, int faces, boolean selfClickable, PlaceCandidate candidate) {
            this.cell = cell;
            this.faces = faces;
            this.selfClickable = selfClickable;
            this.candidate = candidate;
        }

        private boolean hasFace(Direction dir) {
            return (faces & (1 << dir.ordinal())) != 0;
        }
    }
}
