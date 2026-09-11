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

import cc.aerial.client.features.impl.combat.crystalaura.aim.AuraRotation;
import cc.aerial.client.features.impl.combat.crystalaura.aim.LookPoint;
import cc.aerial.client.features.impl.combat.crystalaura.aim.LookPoints;
import cc.aerial.client.features.impl.combat.crystalaura.aim.RayTests;
import cc.aerial.client.features.impl.combat.crystalaura.aim.Rotation;
import cc.aerial.client.features.impl.combat.crystalaura.config.MineSettings;
import cc.aerial.client.features.impl.combat.crystalaura.config.PlaceSettings;
import cc.aerial.client.features.impl.combat.crystalaura.config.SwitchMode;
import cc.aerial.client.features.impl.combat.crystalaura.exec.CrystalSlot;
import cc.aerial.client.features.impl.combat.crystalaura.scan.ScanGeometry;
import cc.aerial.client.features.impl.combat.crystalaura.world.WorldView;
import cc.aerial.client.utility.InventoryUtility;
import cc.aerial.client.utility.MiningClaim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Breaks the one or two cells of a {@link MineTarget}, one cell at a time, by driving vanilla's own progressive
 * breaking. One instance per module; main thread only, and every packet it sends leaves from the module's
 * EXECUTE step.
 *
 * <h2>Packets: drive the game mode, never forge</h2>
 * <p>The dig is one {@code gameMode.continueDestroyBlock(pos, direction)} per owned tick plus the swing and the
 * breaking particles — the same three things vanilla's continue-attack does while the attack key is held, the
 * swing only when the call returned true. The game mode then does everything by itself: it sends the carried
 * item first, the start through its prediction handler on the first tick (i.e. with a correct monotonic
 * sequence), nothing at all on the middle ticks, the stop through the prediction handler when the progress
 * reaches 1.0, a start-only instant break where the block falls in one hit, and no packet whatsoever when the
 * target turned to air under us.
 *
 * <p>The immediate variant of the break helper — start and stop from one call — is never used: that is two dig
 * packets in one tick.
 *
 * <h2>Aborting, and why the next cell waits for a later tick</h2>
 * <p>Only {@code gameMode.stopDestroyBlock()} may end a dig. It sends the abort at the game mode's own destroy
 * position with a hardcoded {@code Direction.DOWN} through the three-argument action packet, i.e. with
 * sequence 0 — which is what an anticheat requires of a cancelled dig (a non-zero sequence on a cancelled dig
 * is cancelled) and what its position check reads as "no face".
 *
 * <p>Retargeting by calling continue- or start-destroy on a new position while the client is still destroying
 * is therefore never done. Vanilla's own retarget path sends the abort for the OLD position with the NEW
 * direction, which an anticheat stores as the last face and holds against the next dig packet that names a
 * different one — and our next cell is re-aimed, so its face regularly differs. It also puts two dig packets
 * into one tick for no gain. The job aborts in one tick and starts the next cell in a later one.
 *
 * <h2>The tick budget: digging is a THIRD mutually exclusive action class</h2>
 * <p>An anticheat flags an attack, an entity interact or a release-use that follows ANY dig packet — the abort
 * included — inside one tick, and a block placement or a use-item after a finished, a cancelled or a
 * non-instant start digging. Its multi-action check sets its block channel on start and finish digging, so the
 * reverse order flags symmetrically.
 *
 * <p>A tick this job owns therefore carries no aura attack and no aura use, and the middle ticks of a dig —
 * which send no action packet at all, only the swing — are owned as well, because two swings in one tick is
 * not something a vanilla client produces. The hub enforces that by planning nothing else on a tick this job
 * answers.
 *
 * <h2>Rotation</h2>
 * <p>The aim is held on the cell for the whole dig: an anticheat raytraces the full block cube from the
 * movement rotation of the tick the dig packet arrives in. The plan asks for a reachable face (any visible one
 * before the first start, the frozen face afterwards) and the execute step re-traces the ray against the
 * outgoing rotation and, failing that, against the one already on the wire — the two exact rotations the check
 * itself tries, and the same discipline the aura's own clicks apply. No tolerance is widened: a tick whose
 * exact ray misses sends nothing and the next one retries.
 *
 * <p>The face put on the wire is the direction of that very raytrace, so it is a face the eye is genuinely
 * outside of and trivially inside {@code 0..5}.
 *
 * <h2>Range, tool and arbitration</h2>
 * <p>The cell must stay inside the dig range measured eye to block box — the lower bound of the eye-to-hit-point
 * distance, the same quantity an anticheat clamps the eye into and the server checks with its own block
 * interaction range.
 *
 * <p>The tool is the best hotbar slot for the cell, chosen BEFORE the first start of that cell and never
 * changed until the cell is gone: the game mode compares the held stack when deciding whether the dig is still
 * the same one, so a mid-dig switch would drive continue-destroy into the retarget path described above.
 * {@code Player.getDestroySpeed} reads the SELECTED hotbar slot, which Aerial's inventory mixin redirects to
 * the slot spoof, so a silently selected tool is the one the client predicts with as well as the one the
 * server sees.
 *
 * <p>The mining claim is consulted before any dig packet and released when the job ends: a refused claim means
 * send nothing. The claim is also what {@link #cancelsBreaking()} answers, because vanilla's continue-attack
 * tears an unheld dig down once per tick from the last statement of the keybind handling.
 *
 * <h2>What is NOT here</h2>
 * <p>The obsidian that follows the dig is placed by the existing base-place path on a LATER tick (this job owns
 * every tick that carries a dig packet, so the placement cannot share one). It can never be clicked against a
 * cell we broke: the base-place clickable test refuses a replaceable neighbour and a broken cell is air, and
 * its replace-in-place form refuses air too, since it wants a pickable shape — which is exactly the block an
 * anticheat resyncs a placement against at cancel VL 0. {@link MineTarget#click()} is the search's proof that
 * such a neighbour will still exist; it is not re-clicked from here.
 *
 * <h2>Deviations from the reference implementation</h2>
 * <ul>
 *   <li>Aerial's mining claim takes no lifetime: it lapses one tick after the last claim call, and the plan
 *       renews it every tick, so the reference implementation's claim-ticks constant has no counterpart.</li>
 *   <li>The tool switch is filed on the plan and applied by the hub through the slot spoof; there is no
 *       silent-hotbar service that could hold it for a number of ticks by itself.</li>
 * </ul>
 */
public final class MineJob {
    /**
     * Wall-clock floor between a completed break and the next start-destroy, in milliseconds.
     *
     * <p>An anticheat's fast-break check adds {@code 300 - gapMs} to a balance for every gap below 275 ms and
     * flags at a balance above 1000 ms, so 300 is the value at which the balance decays instead of growing —
     * and it is also what vanilla's own six-tick destroy delay produces.
     */
    public static final long PACING_MS = 300L;

    /**
     * Ticks after an interrupt-abort or a give-up in which no new job may start.
     *
     * <p>One second. The case it exists for is an aura that produces an action every few ticks: without it the
     * job would start on the first quiet tick, spend a tick aiming, and abort again on the next offer, so the
     * only packets it would ever emit are start/abort pairs. A second is longer than the re-aim plus placement
     * cycle the aura runs at its default delays, so a job that does start has a real chance of reaching its
     * first stop, and it is short enough to be invisible when the aura genuinely goes quiet.
     */
    public static final int INTERRUPT_COOLDOWN_TICKS = 20;

    /** Rays one look point search may trace, the budget the tick planner gives its own searches. */
    private static final int LOOK_BUDGET = 96;

    /** Hotbar slots the tool search scans. */
    private static final int HOTBAR_SIZE = 9;

    /** Upper bound vanilla puts on the efficiency addition to a destroy speed. */
    private static final float MAX_EFFICIENCY_BONUS = 1024f;

    private final MineSettings settings;
    private final PlaceSettings place;

    /** The pure state machine; everything headlessly testable lives there. */
    private final MineFlow flow = new MineFlow();

    @Nullable
    private MineTarget target;

    /** The tool frozen for the current cell; always null with the switch mode off, dig with whatever is held. */
    @Nullable
    private CrystalSlot tool;

    /** Ticks left in which no new job may start. */
    private int cooldown;

    /** True only while the abort is being sent, so our own stop-destroy is not cancelled by the breaking gate. */
    private boolean aborting;

    public MineJob(MineSettings settings, PlaceSettings place) {
        this.settings = settings;
        this.place = place;
    }

    /** The job owns this tick; the hub plans no attack and no use while this holds. */
    public boolean isActive() {
        return flow.isActive();
    }

    /**
     * Whether vanilla's own per-tick teardown of the block breaking must be cancelled right now: while the dig
     * is ours, and never while we are the ones calling stop-destroy. The cancel event is fired at the HEAD of
     * that method, so without the second half our own abort would cancel itself.
     */
    public boolean cancelsBreaking() {
        return !aborting && MiningClaim.isHeldBy(this);
    }

    /** One-liner for the module's debug tracing. */
    public String describe() {
        MineTarget current = target;
        if (current == null) {
            return "mine idle" + (cooldown > 0 ? " cd=" + cooldown : "");
        }
        List<BlockPos> cells = current.cells();
        BlockPos cell = flow.cell() >= 0 && flow.cell() < cells.size() ? cells.get(flow.cell()) : null;
        String where = cell == null ? "-" : "(" + cell.getX() + " " + cell.getY() + " " + cell.getZ() + ")";
        Direction face = flow.face();
        return "mine " + flow + " " + where + (face == null ? "" : " " + face.name());
    }

    /**
     * Decides this tick, at the module's PLAN step. Returns null when the job does not own the tick — then the
     * aura plans normally.
     *
     * <p>{@code target} is a freshly searched plan and is only looked at when nothing is running;
     * {@code auraOffers} is whether the aura has an action ready NOW. A job never starts against an offered
     * action (digging must not displace something we can do this tick) and, with the interrupt setting, a
     * running one gives up the moment such an action appears — paying the abort its own tick and then refusing
     * to start again for {@link #INTERRUPT_COOLDOWN_TICKS}.
     */
    @Nullable
    public MinePlan plan(WorldView view, @Nullable MineTarget target, boolean auraOffers, float slack,
                         Predicate<Entity> rayIgnore) {
        if (cooldown > 0) {
            cooldown--;
        }
        if (flow.isStarted() && !isDestroying()) {
            surrender();
        }
        if (!flow.isActive()) {
            endJob();
            if (target == null || !canStart(target, auraOffers)) {
                return null;
            }
            this.target = target;
            flow.begin(target.cells().size());
        }
        return tickActive(view, auraOffers, slack, rayIgnore);
    }

    /**
     * One tick of a running job: re-validate, renew the claim, ask the flow for the move and dress it with the
     * aim and the tool. Every way out is an abort, which decides by itself whether the abort packet is owed.
     */
    @Nullable
    private MinePlan tickActive(WorldView view, boolean auraOffers, float slack, Predicate<Entity> rayIgnore) {
        BlockPos cell = currentCell();
        if (cell == null || !canDig() || !stillValid(view, cell) || !MiningClaim.claim(this, cell)) {
            return abort(0);
        }
        if (settings.interrupt.getValue() && auraOffers) {
            return abort(INTERRUPT_COOLDOWN_TICKS);
        }

        MineMove move = flow.tick(settings.maxTicks.getValue().intValue(), PACING_MS);
        if (move != MineMove.DIG && move != MineMove.HOLD) {
            return abort(INTERRUPT_COOLDOWN_TICKS);
        }

        if (flow.face() == null) {
            tool = toolFor(view, cell);
        }
        LookPoint look = lookFor(view, cell, slack, rayIgnore);
        MineTarget current = target;
        Entity entity = current == null ? null : current.candidate().target().entity();
        return new MinePlan(move, cell, flow.face(), look, tool, entity);
    }

    /**
     * Performs a plan at the module's EXECUTE step, after vanilla's own click loops and its continue-attack.
     * {@code allowDig} is the hub's foreign-click gate: a dig packet may not join a tick in which somebody else
     * already clicked, in either order.
     *
     * <p>Returns a one-line report for the module's debug log; null is never returned so the trace always says
     * why a tick sent nothing.
     */
    public String execute(MinePlan plan, WorldView view, Predicate<Entity> rayIgnore, boolean allowDig,
                          boolean foreignDig) {
        if (foreignDig) {
            return surrender();
        }
        if (plan.move() == MineMove.ABORT) {
            return sendAbort();
        }
        return plan.move() == MineMove.HOLD ? "pacing" : dig(plan, view, rayIgnore, allowDig);
    }

    /** Drops the job and its claim; called from the module's own reset (disable, world change, level mismatch). */
    public void reset() {
        endJob();
        cooldown = 0;
    }

    /**
     * Ends the job WITHOUT sending anything, because the client-side dig is no longer ours to abort.
     *
     * <p>Two ways that happens, and the mining claim stops neither: the player's own mouse, whose
     * continue-destroy on another position takes the retarget path and has already sent the abort for our cell
     * — so the game mode's destroy position is theirs and a stop-destroy of ours would abort THEIR dig — and
     * anything that simply cleared the destroying flag under us (the block turning to air, or a stop-destroy we
     * did not cancel). Releasing the claim also hands vanilla's own per-tick teardown back, which is the
     * correct abort for whatever is actually in progress.
     */
    private String surrender() {
        flow.onAborted();
        endJob();
        return "dig no longer ours";
    }

    /**
     * The one break call of an owned tick. The hit is the one traced HERE against the outgoing rotation — never
     * the one the look point promised — and its direction is what goes on the wire, so the face is always one
     * this client's own pick produced.
     */
    private String dig(MinePlan plan, WorldView view, Predicate<Entity> rayIgnore, boolean allowDig) {
        if (!allowDig) {
            return "dig blocked this tick";
        }

        BlockPos cell = plan.pos();
        if (cell == null || !canDig() || !MiningClaim.isHeldBy(this)) {
            return "dig gate closed";
        }

        Direction frozen = plan.face();
        double range = digRange();
        boolean through = throughBlocks();
        BlockHitResult hit = byAim(AuraRotation.outgoing(), rotation -> {
            BlockHitResult candidate =
                    RayTests.hitsBlock(view.eye(), rotation, cell, range, rayIgnore, through);
            return candidate != null && (frozen == null || candidate.getDirection() == frozen) ? candidate : null;
        });
        if (hit == null) {
            return "dig ray misses";
        }

        boolean destroying = isDestroying();
        if (!doBreak(hit)) {
            return "no world";
        }
        return observe(view, hit, destroying);
    }

    /** Gives the claim back and forgets the target; the flow is already in a terminal phase when this runs. */
    private void endJob() {
        MiningClaim.release(this);
        flow.reset();
        target = null;
        tool = null;
    }

    /**
     * Vanilla's own progressive break for one tick: the creative one-shot first, then the ordinary
     * continue-destroy with the swing and the breaking particles it earns when it returns true.
     *
     * <p>Returns false only when the client is not in a state that could dig at all.
     */
    private static boolean doBreak(BlockHitResult hit) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        MultiPlayerGameMode gameMode = minecraft.gameMode;
        ClientLevel level = minecraft.level;
        if (player == null || gameMode == null || level == null) {
            return false;
        }
        BlockPos pos = hit.getBlockPos();
        Direction direction = hit.getDirection();
        if (player.isCreative()) {
            if (gameMode.startDestroyBlock(pos, direction)) {
                player.swing(InteractionHand.MAIN_HAND);
                return true;
            }
        }
        if (gameMode.continueDestroyBlock(pos, direction)) {
            player.swing(InteractionHand.MAIN_HAND);
            level.addBreakingBlockEffect(pos, direction);
        }
        return true;
    }

    /**
     * Reads back what the break call did, from the two things it changes observably: the game mode's destroying
     * flag and the client-side block, which the stop and the instant break both clear.
     *
     * <ul>
     *   <li>not destroying to destroying: the start went out, the dig is live.</li>
     *   <li>destroying to not destroying: the stop went out and the cell is gone. The one other way out of the
     *       destroying flag is the target having turned to air under us, which sends nothing — the cell is gone
     *       either way and the pacing floor costs 300 ms we did not have to pay, never the other way round.</li>
     *   <li>neither, but the cell is now air: the instant break, a start with no stop and, crucially, without
     *       vanilla's destroy delay.</li>
     *   <li>neither, and the cell still stands: the destroy delay swallowed the call; only the swing went
     *       out.</li>
     * </ul>
     */
    private String observe(WorldView view, BlockHitResult hit, boolean destroyingBefore) {
        boolean destroyingNow = isDestroying();
        boolean gone = view.level().getBlockState(hit.getBlockPos()).isAir();
        if (destroyingBefore && !destroyingNow) {
            flow.onFinished(false);
            return "finished " + hit.getBlockPos().toShortString();
        }
        if (destroyingNow) {
            if (!destroyingBefore) {
                flow.onStarted(hit.getDirection());
            }
            return "digging " + hit.getBlockPos().toShortString() + " " + hit.getDirection().name();
        }
        if (gone) {
            flow.onStarted(hit.getDirection());
            flow.onFinished(true);
            return "instant " + hit.getBlockPos().toShortString();
        }
        flow.onSwallowed();
        return "delay";
    }

    /**
     * The game mode's stop-destroy, which sends the abort only while it is destroying, with the aborting flag
     * set so the breaking gate lets our own call through. Then the job is over.
     */
    private String sendAbort() {
        MultiPlayerGameMode gameMode = Minecraft.getInstance().gameMode;
        boolean owed = gameMode != null && gameMode.isDestroying();
        aborting = true;
        try {
            if (gameMode != null) {
                gameMode.stopDestroyBlock();
            }
        } finally {
            aborting = false;
        }
        flow.onAborted();
        endJob();
        return owed ? "aborted" : "abort not owed";
    }

    /**
     * Ends the job. When a start is outstanding the abort owes its own tick — it may not share one with the
     * action that displaced us — so the tick is claimed with {@link MineMove#ABORT}; otherwise nothing is owed,
     * the job stops here and the aura still gets this tick.
     */
    @Nullable
    private MinePlan abort(int cooldownTicks) {
        cooldown = Math.max(cooldown, cooldownTicks);
        boolean owed = flow.isStarted();
        flow.onAborted();
        if (!owed) {
            endJob();
            return null;
        }
        return new MinePlan(MineMove.ABORT, null, null, null, null, null);
    }

    /** A new job may start: a plan, nothing offered by the aura, no cooldown, the gates open, the dig is ours. */
    private boolean canStart(MineTarget target, boolean auraOffers) {
        if (target.cells().isEmpty() || auraOffers || cooldown > 0) {
            return false;
        }
        return canDig() && MiningClaim.claim(this, target.cells().get(0));
    }

    /**
     * The gates a vanilla client applies before it may continue an attack on a block: alive, not a spectator, no
     * item in use, the hands not busy and no piercing weapon in the main hand — vanilla's continue-attack runs
     * only while the miss time is spent and the player is not using an item, and only for a hand without a
     * piercing weapon, and an anticheat mirrors both.
     *
     * <p>The main-hand item is read AFTER the tool switch of the previous tick took effect, because the
     * inventory's selected slot follows the slot spoof.
     */
    private static boolean canDig() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return false;
        }
        if (!player.isAlive() || player.isSpectator() || player.isUsingItem() || player.isHandsBusy()) {
            return false;
        }
        if (minecraft.missTime > 0) {
            return false;
        }
        return !player.getItemInHand(InteractionHand.MAIN_HAND).has(DataComponents.PIERCING_WEAPON);
    }

    /**
     * Re-validation of the running plan against the live world: the cell is still inside the dig range of the
     * eye, it is still something that can be mined away into air — which refuses air, any non-empty fluid and
     * hardness {@code -1} — and every cell this job already cleared is still air.
     *
     * <p>The plan's WORTH is deliberately not re-scored here. The search prices a mined base against the best
     * placement available right now, and re-running it every tick while the aura is barred from placing would
     * abort the job on the first candidate the search itself made unreachable. "Something better is available"
     * is what the interrupt setting is for, and "this is taking too long" is what MaxTicks is for.
     */
    private boolean stillValid(WorldView view, BlockPos cell) {
        if (ScanGeometry.blockDistanceSq(cell.getX(), cell.getY(), cell.getZ(), view.eye()) >= digRangeSq()) {
            return false;
        }
        if (!MineRules.isDiggable(view.level(), cell, view.state(cell))) {
            return false;
        }
        MineTarget current = target;
        if (current == null) {
            return false;
        }
        List<BlockPos> cells = current.cells();
        for (int index = 0; index < flow.cell(); index++) {
            if (!view.state(cells.get(index)).isAir()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The aim for this tick: any visible face while no start has gone out yet (a placement look point with no
     * crystal to serve, which samples every face the eye lies beyond), and the frozen face afterwards. Null
     * when no sampled ray reaches the cell — the tick is still ours, the hub holds the current rotation and
     * MaxTicks prices the wait.
     */
    @Nullable
    private LookPoint lookFor(WorldView view, BlockPos cell, float slack, Predicate<Entity> rayIgnore) {
        Rotation wire = AuraRotation.wire();
        Direction frozen = flow.face();
        double range = digRange();
        if (frozen == null) {
            return LookPoints.forPlacement(view.eye(), wire, cell, range, null, range, rayIgnore, slack,
                    LOOK_BUDGET, throughBlocks(), null);
        }
        return LookPoints.forFace(view.eye(), wire, cell, frozen, range, rayIgnore, slack, LOOK_BUDGET,
                throughBlocks());
    }

    /**
     * The best hotbar tool for a cell, or null with the switch mode off and in creative. Resolved once per
     * cell, before its first start.
     */
    @Nullable
    private CrystalSlot toolFor(WorldView view, BlockPos cell) {
        if (settings.tool.getValue() == SwitchMode.NONE) {
            return null;
        }
        return bestTool(view.level().getBlockState(cell));
    }

    /**
     * The fastest of the nine hotbar slots for a state, ties going to the slot the server already believes is
     * selected and then to the nearest index.
     *
     * <p>Speed is the stack's own destroy speed plus vanilla's efficiency addition — {@code level * level + 1},
     * clamped, and only for a stack that is faster than bare hands to begin with, which is exactly the term
     * {@code Player.getDestroySpeed} adds through the mining-efficiency attribute. An empty hand scores 1, so
     * the search always answers a slot in survival; creative answers null, where the dig is instant anyway.
     */
    @Nullable
    private static CrystalSlot bestTool(BlockState state) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.isCreative()) {
            return null;
        }
        int selected = CrystalSlot.serverSelectedSlot();
        int bestIndex = -1;
        float bestSpeed = 0f;
        int bestDistance = 0;
        for (int index = 0; index < HOTBAR_SIZE; index++) {
            float speed = destroySpeed(player.getInventory().getItem(index), state);
            int distance = index == selected ? Integer.MIN_VALUE : Math.abs(selected - index);
            if (bestIndex < 0 || speed > bestSpeed || (speed == bestSpeed && distance < bestDistance)) {
                bestIndex = index;
                bestSpeed = speed;
                bestDistance = distance;
            }
        }
        return bestIndex < 0 ? null : CrystalSlot.hotbar(bestIndex);
    }

    /** The stack's destroy speed on a state with vanilla's efficiency addition folded in. */
    private static float destroySpeed(ItemStack stack, BlockState state) {
        float speed = stack.getDestroySpeed(state);
        int level = InventoryUtility.calculateEnchantmentLevel(stack, Enchantments.EFFICIENCY);
        if (speed > 1f && level != 0) {
            speed += Mth.clamp((float) level * level + 1f, 0f, MAX_EFFICIENCY_BONUS);
        }
        return speed;
    }

    @Nullable
    private BlockPos currentCell() {
        MineTarget current = target;
        if (current == null) {
            return null;
        }
        List<BlockPos> cells = current.cells();
        return flow.cell() >= 0 && flow.cell() < cells.size() ? cells.get(flow.cell()) : null;
    }

    /** Whether the client's own dig state machine is running. */
    private static boolean isDestroying() {
        MultiPlayerGameMode gameMode = Minecraft.getInstance().gameMode;
        return gameMode != null && gameMode.isDestroying();
    }

    /** The placement setting for digging a cell the eye cannot see; see that setting for why it is legal. */
    private boolean throughBlocks() {
        return place.throughBlocks.getValue();
    }

    private double digRange() {
        return settings.range.getValue();
    }

    private double digRangeSq() {
        double range = digRange();
        return range * range;
    }

    /**
     * The aim discipline of the dig: the rotation this tick's movement packet will carry first, the one already
     * on the wire second. Both are exact and both are raytrace candidates of the post-flying check that
     * decides, so nothing is loosened — an action that reaches from neither is refused.
     */
    @Nullable
    private static <T> T byAim(Rotation aim, Function<Rotation, T> test) {
        T fromAim = test.apply(aim);
        if (fromAim != null) {
            return fromAim;
        }
        Rotation wire = AuraRotation.wire();
        return wire.equals(aim) ? null : test.apply(wire);
    }
}
