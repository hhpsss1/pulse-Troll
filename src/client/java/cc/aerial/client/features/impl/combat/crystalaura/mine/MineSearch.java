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

import cc.aerial.client.features.impl.combat.crystalaura.aim.LookPoints;
import cc.aerial.client.features.impl.combat.crystalaura.aim.Rotation;
import cc.aerial.client.features.impl.combat.crystalaura.base.BaseRules;
import cc.aerial.client.features.impl.combat.crystalaura.calc.ExplosionMath;
import cc.aerial.client.features.impl.combat.crystalaura.calc.MultiOverrideShapeSource;
import cc.aerial.client.features.impl.combat.crystalaura.config.BasePlaceSettings;
import cc.aerial.client.features.impl.combat.crystalaura.config.MineSettings;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
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
 * Finds the obsidian base that becomes available after breaking up to the configured number of cells, and only
 * when it is worth more than everything available right now. A sibling of the base-place search, one instance
 * per tick (it holds the tick's {@link WorldView}); main thread only.
 *
 * <h2>Which cells have to go</h2>
 * <p>The crystal item has exactly three conditions: the clicked block is obsidian or bedrock, the cell above it
 * is EMPTY — and "empty" there means {@code getBlockState(pos).isAir()}, so ONE cell and only true air — and
 * the {@code 1 x 2 x 1} ENTITY volume above it is clear. There is no block test two cells up, none on the
 * horizontal neighbours and none against the crystal's own {@code 2 x 2} hitbox, so a fully enclosed
 * {@code 1 x 1 x 1} pocket is a legal crystal cell.
 *
 * <p>With the obsidian placement in front of it, that leaves the three cases of the clear mask and never more
 * than two cells: the enemy on flat ground (the ground cell is solid, the cell above already air — break 1, the
 * obsidian drops into the hole and the crystal ends at the victim's FOOT level, which is where the explosion
 * measures from), the enclosed case (both solid — break 2) and the ceiling case (the base cell is already air
 * or replaceable, only the cell above is solid — break 1).
 *
 * <h2>What is refused, and why</h2>
 * <ul>
 *   <li><b>Undiggable.</b> Hardness {@code -1} makes the destroy progress exactly {@code 0.0F}, so the dig
 *       never completes: bedrock, barrier, light, moving piston. Air has nothing to dig. An anticheat flags
 *       every one of them plus water and lava in one list, "the block does not have a hitbox". See
 *       {@link MineRules#isDiggable}.</li>
 *   <li><b>Fluids.</b> Breaking a block writes {@code fluidState.createLegacyBlock()} both client-side and on
 *       the server, so breaking a WATERLOGGED block leaves water, not air. Water is replaceable but not air, so
 *       the cell fails the crystal item's emptiness test and the whole dig was wasted.
 *       {@link MineRules#isDiggable} therefore refuses any cell whose fluid state is non-empty — which is also
 *       the water / lava / bubble-column half of the anticheat's list, since only an empty fluid legacy-blocks
 *       to air.</li>
 *   <li><b>Falling blocks.</b> A falling block schedules a tick two ticks out and spawns its entity as soon as
 *       the cell below is free. That tick is server-only, so the client never predicts it and the pocket
 *       silently refills two server ticks later. The clear mask checks the single cell directly above the
 *       TOPMOST cleared cell — walking further up buys nothing, because the boolean "does anything fall in" is
 *       decided by that one block, and a block two cells up is not even neighbour-updated by our break.
 *       Suspicious sand and suspicious gravel are brushable blocks and do NOT fall.</li>
 *   <li><b>The block under our feet.</b> The block the player stands on is never cleared.</li>
 *   <li><b>No way in for the obsidian.</b> The obsidian must be placed against a block an anticheat still
 *       believes is solid (see {@link BaseClick}): an untouched neighbour in the base-place range, or — when
 *       the plan leaves the base cell standing and replaceable — the base cell itself, which vanilla's
 *       replace-clicked branch puts the obsidian into. A plan with neither is dropped, not "fixed" later.</li>
 * </ul>
 *
 * <h2>Scoring and the gain rule</h2>
 * <p>A survivor is scored through the placement search with placeability waived and a
 * {@link MultiOverrideShapeSource} that shows the rays the pocket as it WILL be: a full block at the base (the
 * future obsidian) and an empty shape at every cell we are going to remove. The result must beat the floor
 * {@code bestExisting.damage.effective * (1 + MinGain / 100)}, the shape the base-place search uses — digging
 * costs whole ticks in which the aura can neither attack nor place (a tick carrying a dig packet carries
 * nothing else), so a mined plan must never displace something we can do now.
 *
 * <p>The placement search also enforces the break range on the crystal it would create, so a mined base whose
 * crystal we could never detonate is rejected for free; nothing here duplicates or bypasses that.
 *
 * <h2>Cost order</h2>
 * <p>Cells of the cube of the cell reach around the eye go through the cheap stage, cheapest first, and nothing
 * is allocated for a cell that fails one of these:
 * <ul>
 *   <li>box distance to the eye below the cell reach;</li>
 *   <li>the explosion cut-off for at least one target — one squared distance and one square root per target, no
 *       allocation;</li>
 *   <li>the clear mask: two block-state reads and the hazard tests, allocation-free through the shared scratch
 *       position; a mask of {@link MineRules#CLEAR_NONE} (already a base, the base search's job) or
 *       {@link MineRules#CLEAR_IMPOSSIBLE} is out, and so is a mask with more cells than MaxBlocks;</li>
 *   <li>the damage bound, pure arithmetic; {@code 0} or below the MinGain floor is out.</li>
 * </ul>
 *
 * <p>Survivors are queued by bound and drained in descending bound, which stops as soon as the next bound is
 * below the BEST effective damage found so far — the bound is an upper bound, so no later cell can reach or tie
 * it. The test is non-strict, keeping exact ties alive for the rank tie-breaks; this is the cutoff of the
 * explosion ranker and of the base-place search verbatim. Only a drained cell pays the admission: the click
 * neighbour scan (up to six state reads plus a placement context each) and then the rays of the scoring.
 *
 * <h2>Per-tick worst case</h2>
 * <p>The cube is {@code min(Place.Range, max(Mine.Range, BasePlace.Range) + 1)}, at most 11 cells per axis with
 * the defaults. Every cell pays one box distance, then — while any target is in range — one square root per
 * target, then at most two state reads plus one placement context, then the bound arithmetic. Unlike the
 * base-place search, whose base-cell test prunes to the handful of existing obsidian cells, almost every solid
 * cell near the victim survives the clear mask, so the heap can hold hundreds of entries; what bounds the
 * expensive part is the refinement cutoff, and since the heap is drained in descending bound the FIRST poll is
 * usually admissible and immediately raises the cutoff to the true optimum. The degenerate case — nothing
 * admissible anywhere — drains the whole heap, exactly as the base-place search does.
 *
 * <h2>Deliberate deviations</h2>
 * <ul>
 *   <li>Only the FIRST cell gets a look-point search, not the whole plan. A dig runs over many ticks and
 *       re-aims every one of them, so the aim of the later cells belongs to the job — and it could not be
 *       decided here anyway: the second cell of a two-block pocket is the one UNDER the first, hidden behind it
 *       until the first is gone, so testing it now would reject every ordinary underground plan. The first cell
 *       is different: it is dug immediately, and a plan whose first cell no rotation can reach is pure loss.
 *       Digging owns the whole tick — no attack, no placement — so a job that locks onto an unreachable cell
 *       does not merely fail, it starves the aura for as long as it keeps trying, which is exactly what "it
 *       stopped placing crystals" looks like from the outside. The job re-traces the ray before every packet,
 *       so nothing illegal was ever sent; the cost was in ticks, and ticks are the scarce thing here.</li>
 *   <li>No entity-obstruction test for the future obsidian. It would be a snapshot of where entities stand now,
 *       and the placement happens ticks later; the base-place search re-checks it at the moment it actually
 *       places.</li>
 *   <li>No dig-duration estimate. MaxTicks is enforced by the job, which knows the tool it will actually
 *       hold.</li>
 * </ul>
 */
public final class MineSearch {
    private static final float PERCENT = 100f;

    /** Rays one face look-point search may trace in {@link #aimable}. */
    private static final int LOOK_BUDGET = 16;

    private static final Comparator<Cell> BY_BOUND = Comparator.comparingDouble(cell -> -cell.bound);

    /**
     * The base-place rank with one term added: at equal damage the plan that breaks FEWER cells wins, because
     * every extra cell is another dig plus the ~300 ms finish-to-start pacing an anticheat requires, spent
     * unable to act.
     */
    private static final Comparator<Scored> BY_RANK =
            Comparator.<Scored>comparingDouble(s -> -s.target.candidate().damage().effective())
                    .thenComparingDouble(s -> s.target.candidate().selfDamage().effective())
                    .thenComparingInt(s -> s.target.cells().size())
                    .thenComparingDouble(s -> s.cell.distSq);

    private final WorldView view;
    private final PlaceSearch placeSearch;
    private final MineSettings settings;
    private final BasePlaceSettings baseSettings;
    private final Predicate<Entity> rayIgnore;
    private final Rotation wire;
    private final float slackDeg;
    private final boolean throughBlocks;

    private final ClientLevel level;

    /** Whether the player is sneaking, which is what lets a click through an interactable block. Read once. */
    private final boolean sneaking;

    /** The collision context the PICK resolves outlines with. */
    private final CollisionContext pickContext;

    /** The block we stand on, which no plan may take away. */
    private final BlockPos standing;

    /** The obsidian stack in hand, for the replace-clicked test. */
    private final ItemStack obsidianStack = new ItemStack(Items.OBSIDIAN);

    /** The dig range squared: the eye-to-block-box bound every cell we intend to break must satisfy. */
    private final double digRangeSq;

    /** The base-place range squared: the bound the neighbour clicked to place the obsidian must satisfy. */
    private final double clickRangeSq;

    /** How many cells one plan may clear. */
    private final int maxBlocks;

    /** The dig range, for the look-point searches. */
    private final double digRange;

    /**
     * Cube radius of the cheap stage: {@code min(PlaceSearch.range, max(Mine.Range, BasePlace.Range) + 1)}.
     *
     * <p>The {@code + 1} half is a superset bound and not an exact one, because which of the two ranges binds
     * depends on the plan: a base cell we break ourselves is bounded by the dig range directly, a base whose
     * CEILING we break sits up to one block beyond it, and a base we place obsidian into sits up to one block
     * beyond the base-place range (the box distance is 1-Lipschitz in the cell offset, so a neighbour within
     * {@code r} implies the cell within {@code r + 1}). The placement-search half is exact: it rejects a cell
     * beyond its own range outright, so no farther cell can ever score.
     */
    private final float cellReach;

    private final double cellReachSq;

    /** Scratch position of the clear mask; valid only inside one mask call. */
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

    /** Scratch position of the click search. */
    private final BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();

    public MineSearch(WorldView view, PlaceSearch placeSearch, MineSettings settings,
                      BasePlaceSettings baseSettings, Predicate<Entity> rayIgnore, Rotation wire,
                      float slackDeg, boolean throughBlocks) {
        this.view = view;
        this.placeSearch = placeSearch;
        this.settings = settings;
        this.baseSettings = baseSettings;
        this.rayIgnore = rayIgnore;
        this.wire = wire;
        this.slackDeg = slackDeg;
        this.throughBlocks = throughBlocks;

        LocalPlayer player = Minecraft.getInstance().player;
        this.level = view.level();
        if (player == null) {
            throw new IllegalStateException("MineSearch requires a player");
        }
        this.sneaking = player.isSecondaryUseActive();
        this.pickContext = CollisionContext.of(player);
        this.standing = player.getOnPos();

        float range = settings.range.getValue().floatValue();
        float baseRange = baseSettings.range.getValue().floatValue();
        this.digRange = range;
        this.digRangeSq = (double) range * range;
        this.clickRangeSq = (double) baseRange * baseRange;
        this.maxBlocks = settings.maxBlocks.getValue().intValue();
        this.cellReach = Math.min(placeSearch.range(), Math.max(range, baseRange) + 1f);
        this.cellReachSq = (double) cellReach * cellReach;
    }

    /**
     * The best mined base, or null.
     *
     * <p>{@code bestExisting} is the best placement available WITHOUT digging — the head of the placement
     * ranking, or, when the base-place search is on and found a plan this tick, whichever of that plan's
     * candidate and the direct one is better. Passing it is what makes MinGain mean "better than anything we
     * could do instead"; passing null says nothing at all is available, and then any accepted plan wins.
     */
    @Nullable
    public MineTarget best(@Nullable PlaceCandidate bestExisting) {
        float floor = bestExisting == null
                ? 0f
                : bestExisting.damage().effective() * (1f + settings.minGain.getValue().floatValue() / PERCENT);
        List<Scored> scored = refine(collect(floor), floor);
        return scored.isEmpty() ? null : scored.get(0).target;
    }

    /** The cheap stage: geometry, then the block states, then the bound. Everything expensive waits. */
    private PriorityQueue<Cell> collect(float floor) {
        PriorityQueue<Cell> heap = new PriorityQueue<>(BY_BOUND);
        Vec3 eye = view.eye();
        for (BlockPos cell : ScanGeometry.blocksInCuboid(eye, cellReach)) {
            double distSq = ScanGeometry.blockDistanceSq(cell.getX(), cell.getY(), cell.getZ(), eye);
            if (distSq >= cellReachSq || !reachesAnyTarget(cell)) {
                continue;
            }
            int mask = maskFor(cell);
            if (mask <= MineRules.CLEAR_NONE || Integer.bitCount(mask) > maxBlocks) {
                continue;
            }
            BlockPos immutable = cell.immutable();
            float bound = placeSearch.upperBound(immutable);
            if (bound > 0f && bound >= floor) {
                heap.add(new Cell(immutable, distSq, bound, mask));
            }
        }
        return heap;
    }

    /**
     * The explosion cut-off for at least one target, the pre-cut of the base-place search verbatim: a crystal
     * standing on this cell explodes at its own centre, and a victim whose feet are farther than twice the
     * radius from it is skipped by vanilla whatever the exposure, so such a cell can never score.
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

    /** The clear mask against the live client level, with this search's replaceability and clearing rules. */
    private int maskFor(BlockPos cell) {
        return MineRules.clearMask(level, cell, scratch, this::isReplaceable, this::isClearable);
    }

    /**
     * Whether some rotation reaches a cell at all: any face the eye is outside of for which the face look-point
     * search finds a point whose pick ray really lands on that block, exactly the test the job will re-run
     * before every dig packet.
     *
     * <p>A face the eye is not outside of costs nothing — the search rejects it before tracing — so a fully
     * buried cell is refused after six trivial checks, and a visible one usually hits on its first sampled
     * point. That is what keeps this affordable in the degenerate case described above, where the refinement
     * cutoff drains the whole heap: the gate sits before the scoring, so a cell nobody can look at never pays
     * for the exposure rays a scoring would cost.
     */
    private boolean aimable(BlockPos cell) {
        for (Direction face : Direction.values()) {
            if (LookPoints.forFace(view.eye(), wire, cell, face, digRange, rayIgnore, slackDeg,
                    LOOK_BUDGET, throughBlocks) != null) {
                return true;
            }
        }
        return false;
    }

    /** The click neighbour, then the aim gate, then the rays; the finished plan or null. */
    @Nullable
    private MineTarget admit(Cell cell) {
        BaseClick click = null;
        if (!MineRules.isCrystalBase(view.state(cell.pos))) {
            click = clickFor(cell.pos, cell.mask);
            if (click == null) {
                return null;
            }
        }
        List<BlockPos> cells = cellsOf(cell.pos, cell.mask);
        if (!aimable(cells.get(0))) {
            return null;
        }
        PlaceCandidate candidate = score(cell.pos, cells);
        return candidate == null ? null : new MineTarget(cell.pos, cells, click, candidate);
    }

    /**
     * Drains the heap in descending bound, admitting and scoring each cell and keeping plans at or above the
     * floor, until the next bound is below the BEST effective damage found so far. The survivors are returned in
     * rank order.
     */
    private List<Scored> refine(PriorityQueue<Cell> heap, float floor) {
        List<Scored> scored = new ArrayList<>();
        float bestEffective = 0f;
        Cell next = heap.poll();
        while (next != null && next.bound >= bestEffective) {
            MineTarget target = admit(next);
            if (target != null && target.candidate().damage().effective() >= floor) {
                scored.add(new Scored(next, target));
                bestEffective = Math.max(bestEffective, target.candidate().damage().effective());
            }
            next = heap.poll();
        }
        scored.sort(BY_RANK);
        return scored;
    }

    /**
     * The cells of a mask in the order they must be broken: TOP-DOWN, so that the block above a cell is already
     * gone when we aim at it and so that the falling-block test of the clear mask stays valid throughout the
     * dig.
     */
    private static List<BlockPos> cellsOf(BlockPos base, int mask) {
        List<BlockPos> cells = new ArrayList<>(2);
        if ((mask & MineRules.CLEAR_ABOVE) != 0) {
            cells.add(base.above());
        }
        if ((mask & MineRules.CLEAR_BASE) != 0) {
            cells.add(base);
        }
        return cells;
    }

    /**
     * The placement evaluation with placeability waived (the base does not exist yet) and the pocket as it will
     * be: a full block at the base — the future obsidian — and an empty shape at every cell of the plan. The
     * base is written FIRST because a plan may both break its base cell and put the obsidian there, and the
     * multi-override source lets the first match win.
     */
    @Nullable
    private PlaceCandidate score(BlockPos base, List<BlockPos> cells) {
        long[] keys = new long[cells.size() + 1];
        keys[0] = BlockPos.asLong(base.getX(), base.getY(), base.getZ());
        for (int index = 0; index < cells.size(); index++) {
            BlockPos cell = cells.get(index);
            keys[index + 1] = BlockPos.asLong(cell.getX(), cell.getY(), cell.getZ());
        }
        VoxelShape[] shapes = new VoxelShape[keys.length];
        shapes[0] = Shapes.block();
        for (int index = 1; index < shapes.length; index++) {
            shapes[index] = Shapes.empty();
        }
        return placeSearch.evaluate(base, false, source -> new MultiOverrideShapeSource(source, keys, shapes));
    }

    /**
     * How the obsidian gets into a base cell, in vanilla's two forms and in that order — a PROOF of feasibility,
     * never a click this module sends (see {@link BaseClick}).
     *
     * <p>The relative form first: the first neighbour of the base the obsidian can be clicked against that the
     * plan does NOT break. Only {@code UP} can name a cell of the plan, the base not being its own neighbour;
     * when the plan leaves that cell alone it is air and the clickable test refuses it anyway.
     *
     * <p>Then the replace-in-place form: the base itself, when the plan does not clear it (so it is still
     * standing when the obsidian goes in, and the clear mask therefore already found it replaceable) and it can
     * be clicked. Without it a base cell whose every neighbour is replaceable — a snow layer or a fire on open
     * ground — would be judged unreachable and the whole dig plan dropped, although a player places obsidian
     * into exactly such a cell by aiming straight at it.
     */
    @Nullable
    private BaseClick clickFor(BlockPos base, int mask) {
        for (Direction dir : Direction.values()) {
            if (dir == Direction.UP && (mask & MineRules.CLEAR_ABOVE) != 0) {
                continue;
            }
            neighbour.setWithOffset(base, dir);
            if (isClickable(neighbour)) {
                return new BaseClick(neighbour.immutable(), dir.getOpposite());
            }
        }
        if ((mask & MineRules.CLEAR_BASE) != 0 || !isSelfClickable(base)) {
            return null;
        }
        return new BaseClick(base, null);
    }

    /**
     * The base-place self-click test for the mined variant: inside the base-place range (the click lands on this
     * cell) and the replace-in-place rule, whose replaceability term is the same one the clear mask read.
     */
    private boolean isSelfClickable(BlockPos pos) {
        if (ScanGeometry.blockDistanceSq(pos.getX(), pos.getY(), pos.getZ(), view.eye()) >= clickRangeSq) {
            return false;
        }
        BlockState state = view.state(pos);
        return BaseRules.isReplaceInPlace(level, pos, state, isReplaceable(pos, state), pickContext, sneaking);
    }

    /**
     * A neighbour within the base-place range (box distance, the eye-to-hit-point lower bound) that the obsidian
     * will not replace and whose use will not consume the click — the base-place clickable test verbatim, on the
     * base-place range because that is the click this enables.
     */
    private boolean isClickable(BlockPos pos) {
        if (ScanGeometry.blockDistanceSq(pos.getX(), pos.getY(), pos.getZ(), view.eye()) >= clickRangeSq) {
            return false;
        }
        BlockState state = view.state(pos);
        return !isReplaceable(pos, state) && (!BaseRules.isInteractable(state) || sneaking);
    }

    /**
     * The replace-clicked test for obsidian at a position; air is answered without building the context. The
     * base-place replaceability rule with the arguments in the order the clear mask wants.
     */
    private boolean isReplaceable(BlockPos pos, BlockState state) {
        return state.isAir() || BaseRules.canBeReplacedWith(state, pos, obsidianStack);
    }

    /** Whether we may break a cell: inside the dig range, not the block we stand on, and diggable. */
    private boolean isClearable(BlockPos pos, BlockState state) {
        if (ScanGeometry.blockDistanceSq(pos.getX(), pos.getY(), pos.getZ(), view.eye()) >= digRangeSq) {
            return false;
        }
        if (pos.getX() == standing.getX() && pos.getY() == standing.getY() && pos.getZ() == standing.getZ()) {
            return false;
        }
        return MineRules.isDiggable(level, pos, state);
    }

    /** A cell that passed the geometry, state and bound checks; the heap is ordered by the bound. */
    private static final class Cell {
        private final BlockPos pos;
        private final double distSq;
        private final float bound;
        private final int mask;

        private Cell(BlockPos pos, double distSq, float bound, int mask) {
            this.pos = pos;
            this.distSq = distSq;
            this.bound = bound;
            this.mask = mask;
        }
    }

    /** A cell that passed the admission, with the plan it produced. */
    private static final class Scored {
        private final Cell cell;
        private final MineTarget target;

        private Scored(Cell cell, MineTarget target) {
            this.cell = cell;
            this.target = target;
        }
    }
}
