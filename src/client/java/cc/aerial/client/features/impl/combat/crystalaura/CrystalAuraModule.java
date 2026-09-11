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

package cc.aerial.client.features.impl.combat.crystalaura;

import cc.aerial.client.event.impl.game.JoinWorldEvent;
import cc.aerial.client.event.impl.game.PreGameTickEvent;
import cc.aerial.client.event.impl.game.input.PostHandleInputEvent;
import cc.aerial.client.event.impl.game.packet.ReceivePacketEvent;
import cc.aerial.client.event.impl.game.packet.SendPacketEvent;
import cc.aerial.client.event.impl.game.player.interaction.CancelBlockBreakingEvent;
import cc.aerial.client.event.impl.game.server.ServerDisconnectEvent;
import cc.aerial.client.event.impl.game.world.WorldEntityRemoveEvent;
import cc.aerial.client.event.impl.render.Render2DEvent;
import cc.aerial.client.event.impl.render.Render3DEvent;
import cc.aerial.client.event.subscriber.Subscribe;
import cc.aerial.client.features.Module;
import cc.aerial.client.features.ModuleCategory;
import cc.aerial.client.features.impl.combat.crystalaura.aim.AuraRotation;
import cc.aerial.client.rotation.SilentAim;
import cc.aerial.client.features.impl.combat.crystalaura.aim.LookPoint;
import cc.aerial.client.features.impl.combat.crystalaura.aim.Rotation;
import cc.aerial.client.features.impl.combat.crystalaura.base.BasePlaceSearch;
import cc.aerial.client.features.impl.combat.crystalaura.base.BasePlan;
import cc.aerial.client.features.impl.combat.crystalaura.calc.DamageCalculator;
import cc.aerial.client.features.impl.combat.crystalaura.calc.ExplosionMath;
import cc.aerial.client.features.impl.combat.crystalaura.config.CrystalSettings;
import cc.aerial.client.features.impl.combat.crystalaura.config.IFrameMode;
import cc.aerial.client.features.impl.combat.crystalaura.config.LookThrough;
import cc.aerial.client.features.impl.combat.crystalaura.config.PredictMode;
import cc.aerial.client.features.impl.combat.crystalaura.config.SwitchMode;
import cc.aerial.client.features.impl.combat.crystalaura.debug.AuraDebug;
import cc.aerial.client.features.impl.combat.crystalaura.exec.ActionExecutor;
import cc.aerial.client.features.impl.combat.crystalaura.exec.CrystalSlot;
import cc.aerial.client.features.impl.combat.crystalaura.exec.ExecReport;
import cc.aerial.client.features.impl.combat.crystalaura.exec.TickPlan;
import cc.aerial.client.features.impl.combat.crystalaura.exec.TickPlanner;
import cc.aerial.client.features.impl.combat.crystalaura.mine.MineJob;
import cc.aerial.client.features.impl.combat.crystalaura.mine.MinePlan;
import cc.aerial.client.features.impl.combat.crystalaura.mine.MineSearch;
import cc.aerial.client.features.impl.combat.crystalaura.mine.MineTarget;
import cc.aerial.client.features.impl.combat.crystalaura.net.PacketIntake;
import cc.aerial.client.features.impl.combat.crystalaura.render.AuraRenderer;
import cc.aerial.client.features.impl.combat.crystalaura.render.AuraTargetRenderer;
import cc.aerial.client.features.impl.combat.crystalaura.render.AuraVisuals;
import cc.aerial.client.features.impl.combat.crystalaura.scan.BreakCandidate;
import cc.aerial.client.features.impl.combat.crystalaura.scan.BreakSearch;
import cc.aerial.client.features.impl.combat.crystalaura.scan.PlaceCandidate;
import cc.aerial.client.features.impl.combat.crystalaura.scan.PlaceSearch;
import cc.aerial.client.features.impl.combat.crystalaura.state.CrystalLedger;
import cc.aerial.client.features.impl.combat.crystalaura.world.WorldView;
import cc.aerial.client.scaffold.SlotSpoof;
import cc.aerial.client.utility.Stopwatch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Predicate;

/**
 * Places and detonates end crystals.
 *
 * <p>The hub. Every event handler in the module lives here, for two reasons: Aerial's event registry has no
 * unsubscribe, so a helper that subscribed itself would stay subscribed for the life of the process; and the
 * module's handling flag is final and equal to its enabled state, so listeners on the module are gated by the
 * toggle for free.
 *
 * <h2>The tick contract, in the order the game runs it</h2>
 * <ul>
 *   <li>PLAN, at the head of the client tick: drain the packet queue, refresh the world view, search for
 *       placements and breaks, decide what this tick will do, file the rotation that decision needs, and step
 *       the aim so the movement packet this tick sends carries it.</li>
 *   <li>EXECUTE, at the tail of the keybind handling: carry out the plan, but only against the rotation this
 *       tick's movement packet will actually carry. Between the two, vanilla has run its crosshair pick and its
 *       slot sync; after both comes the entity tick, which sends the movement packet.</li>
 * </ul>
 */
public final class CrystalAuraModule extends Module {
    public static final CrystalAuraModule INSTANCE = new CrystalAuraModule();

    private static final Logger LOGGER = LoggerFactory.getLogger("Aerial");

    private static final double MS_PER_TICK = 50.0;
    private static final int ATTACK_TIMEOUT_EXTRA = 2;
    private static final int PLACE_TIMEOUT_EXTRA = 1;
    private static final double PLACE_PREDICT_FACTOR = 1.5;
    private static final double BREAK_PREDICT_FACTOR = 0.5;
    private static final int TAG_WINDOW_TICKS = 20;

    /** Squared per-tick movement below which the player counts as standing. */
    private static final double STILL_EPSILON_SQ = 1.0E-6;

    /** Calm ticks required before the same-tick safe mode allows a shared tick again. */
    private static final int SAME_TICK_CALM_TICKS = 3;

    private static final int ATTACK_RING = 32;

    private final CrystalSettings settings = new CrystalSettings();

    private static final Predicate<Entity> IGNORE_NONE = entity -> false;
    private static final Predicate<Entity> IGNORE_CRYSTALS = entity -> entity instanceof EndCrystal;
    private static final Predicate<Entity> IGNORE_ALL = entity -> true;

    private final PacketIntake intake = new PacketIntake();
    private final CrystalLedger ledger = new CrystalLedger();
    private final Stopwatch breakClock = new Stopwatch(0);
    private final Stopwatch placeClock = new Stopwatch(0);
    private final TickPlanner planner =
            new TickPlanner(settings.place, settings.brk, settings.rotation, settings.basePlace);
    private final ActionExecutor executor =
            new ActionExecutor(settings.place, settings.brk, settings.basePlace);
    private final MineJob mineJob = new MineJob(settings.basePlace.mine, settings.place);

    private int tick;
    private int calmTicks;

    /** The victim the plan is aimed at; read by other modules and by the overlay. */
    @Nullable
    private LivingEntity target;

    /** Wall-clock time of the last action that really went out. */
    private volatile long lastActionMs;

    @Nullable
    private TickPlan plan;
    @Nullable
    private MinePlan minePlan;
    @Nullable
    private AuraVisuals visuals;
    @Nullable
    private WorldView lastView;
    @Nullable
    private BreakSearch breakSearch;
    private Predicate<Entity> rayIgnore = IGNORE_NONE;

    private final int[] attackTicks = new int[ATTACK_RING];
    private int attackCursor;

    @Nullable
    private WeakReference<ClientLevel> levelRef;

    private boolean foreignUseSeen;
    private boolean foreignDigSeen;
    private boolean foreignEntitySeen;

    /** Outgoing packet names of the current tick, for the debug trace. */
    private final ConcurrentLinkedQueue<String> packetTrace = new ConcurrentLinkedQueue<>();

    /** Set while the executor is sending, so our own packets are not read back as foreign clicks. */
    private boolean ownSend;

    private int attackPackets;

    @Nullable
    private Integer restoreSlot;
    @Nullable
    private Integer forcedSlot;

    /** Crystals that still exist client-side but are gone, or about to be, server-side. */
    private final Predicate<Entity> columnIgnore = entity -> entity instanceof EndCrystal crystal
            && (ledger.isAttacked(crystal.getId()) || ledger.isExpectedRemoved(crystal.getId()));

    private CrystalAuraModule() {
        super("Crystal Aura", "Places and detonates end crystals", ModuleCategory.COMBAT);
        addProperties(settings.getProperties());
        Arrays.fill(attackTicks, Integer.MIN_VALUE);
    }

    public CrystalSettings getSettings() {
        return settings;
    }

    /** The victim the plan is aimed at, or null. */
    @Nullable
    public LivingEntity getTarget() {
        return target;
    }

    /** Wall-clock time of the last action that really went out. */
    public long getLastActionMs() {
        return lastActionMs;
    }

    /** Attacks in the last second, shown in the array list. */
    @Override
    public String getSuffix() {
        if (!isEnabled()) {
            return null;
        }
        return String.format(Locale.ROOT, "%.1f/s", (float) recentAttacks());
    }

    @Override
    protected void onEnable() {
        resetState();
    }

    @Override
    protected void onDisable() {
        resetState();
        // The hold must not survive the module: an expiry counted in ticks stops being counted at all once
        // the tick handler is gone, and the hand would stay spoofed for good.
        spoofHeldUntil = Integer.MIN_VALUE;
        SlotSpoof.reset(this);
    }

    // ---------------------------------------------------------------- events

    /** Incoming packets arrive on the netty thread and are parked for the plan to drain. */
    @Subscribe
    public void onReceivePacket(ReceivePacketEvent event) {
        intake.offer(event.getPacket());
    }

    /**
     * Outgoing packets, observed for the foreign-click gates.
     *
     * <p>Only this event is watched, never the instantaneous one: a packet sent the ordinary way fires BOTH,
     * because the one-argument send delegates to the two-argument one and the client's mixin hooks each at its
     * head. Counting both would double every attack.
     */
    @Subscribe
    public void onSendPacket(SendPacketEvent event) {
        observeOutgoing(event.getPacket());
    }

    @Subscribe
    public void onEntityRemoved(WorldEntityRemoveEvent event) {
        ledger.onRemoved(event.entityId());
    }

    @Subscribe
    public void onJoinWorld(JoinWorldEvent event) {
        resetState();
    }

    @Subscribe
    public void onServerDisconnect(ServerDisconnectEvent event) {
        resetState();
    }

    /** PLAN. */
    @Subscribe(priority = 1)
    public void onPreGameTick(PreGameTickEvent event) {
        planTick();
    }

    /** EXECUTE. */
    @Subscribe
    public void onPostHandleInput(PostHandleInputEvent event) {
        executeTick();
    }

    /**
     * Keeps vanilla from tearing a dig down between two of its own swings: the last statement of the keybind
     * handling stops the current destroy whenever the attack key is not held on a block. Only the mining job
     * ever wants that suppressed.
     */
    @Subscribe
    public void onCancelBlockBreaking(CancelBlockBreakingEvent event) {
        if (cancelsBreaking()) {
            event.setCancelled();
        }
    }

    @Subscribe
    public void onRender3D(Render3DEvent event) {
        AuraRenderer.render(event, visuals, settings.render);
    }

    /**
     * The HUD half of the overlay: the damage figures over the planned placement.
     *
     * <p>Self-gating on the damage-text setting and on the marker's own fade, so it needs no further guard. It
     * reads tweens the world pass advances, so the text trails by a frame if 2D is dispatched before 3D.
     */
    @Subscribe
    public void onRender2D(Render2DEvent event) {
        AuraRenderer.renderOverlay(event, settings.render);
    }

    /** Whether a running dig wants vanilla's own stop suppressed; the mining job owns this. */
    private boolean cancelsBreaking() {
        return mineJob.cancelsBreaking();
    }

    // ---------------------------------------------------------------- plan

    private void planTick() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }
        guardLevel();
        tick++;
        int pingTicks = (int) Math.round(ping() / MS_PER_TICK);
        int retry = settings.brk.retryTicks.getValue().intValue();
        int attackTimeout = retry > 0 ? retry : pingTicks + ATTACK_TIMEOUT_EXTRA;
        // The ledger stamps everything with its own clock, so it has to advance BEFORE the drain, or the
        // drained packets are dated one tick early and every timeout they touch is one tick short.
        ledger.onTickStart(tick, attackTimeout, pingTicks + PLACE_TIMEOUT_EXTRA);
        intake.drainInto(ledger, player.getId());
        tracePreviousTick();
        foreignUseSeen = false;
        foreignDigSeen = false;
        foreignEntitySeen = false;

        if (minecraft.gui.screen() != null) {
            pauseForScreen();
            AuraDebug.bail(settings.debug.getValue(), "a screen is open");
            return;
        }

        WorldView view = WorldView.capture(tick, settings.target.collect(), ledger,
                placePredictTicks(pingTicks), breakPredictTicks(pingTicks), pingTicks,
                settings.place.assumeResistance.getValue().intValue() - 1,
                settings.place.assumeArmor.getValue(),
                settings.place.assumeDifficulty.getValue().resolve(minecraft.level));
        lastView = view;
        if (view.targets().isEmpty()) {
            AuraDebug.bail(settings.debug.getValue(),
                    "no target in range " + settings.target.range() + " | " + settings.target.lastScanSummary());
            plan = null;
            minePlan = null;
            mineJob.reset();
            target = null;
            visuals = null;
            restoreNormalSlot();
            AuraRotation.update(settings.rotation);
            return;
        }

        rayIgnore = rayIgnoreFor(settings.place.lookThrough.getValue());
        boolean canAttack = breakClock.hasTimeElapsed(settings.brk.delay.getValue().longValue());
        Searches searches = searchTick(view, canAttack);
        TickPlan decided = planner.decide(view, ledger, searches.breakBest, searches.placeCandidates,
                searches.basePlan, searches.wire, canAttack,
                placeClock.hasTimeElapsed(settings.place.delay.getValue().longValue()),
                sameTickAllowed(player), crystalSlot(), hotbarSlot(Items.OBSIDIAN), rayIgnore);

        // The dig is asked last, with the aura's own verdict as its "something is available now" input, and
        // it owns the whole tick when it answers: an anticheat flags any click that shares a tick with a dig
        // packet, in either order.
        MinePlan mining = mineJob.plan(view, searches.mineTarget, decided.attempting(),
                AuraRotation.slackDegrees(settings.rotation), rayIgnore);
        minePlan = mining;
        plan = mining == null ? decided : null;

        // The target and the overlay follow the aura's own decision even while a dig runs: the dig exists to
        // open a placement for that very victim, so blanking them would make the overlay flicker for as long
        // as the pocket takes to carve.
        target = decided.place() != null ? decided.place().target().entity()
                : (decided.attack() != null ? decided.attack().target().entity() : null);
        visuals = buildVisuals(decided);

        if (mining != null) {
            selectMineTool(mining);
            requestMineRotation(mining);
            AuraRotation.update(settings.rotation);
            AuraDebug.log(settings.debug.getValue(), mineJob::describe);
            return;
        }

        selectSlot(decided);
        requestRotation(decided, searches.breakBest);
        AuraRotation.update(settings.rotation);
        debugPlan(decided, searches);
    }

    /** Holds the aim on the cell being dug; the victim is the reset-detection entity of a smooth step. */
    private void requestMineRotation(MinePlan mining) {
        LookPoint look = mining.look();
        if (look == null) {
            AuraRotation.hold(settings.rotation);
            return;
        }
        AuraRotation.request(look.rotation(), mining.entity(), settings.rotation);
    }

    /**
     * Brings the mining tool into the hand at the tick HEAD, exactly as the crystal slot is brought in, so the
     * game emits its single carried-item change before the dig packet.
     */
    private void selectMineTool(MinePlan mining) {
        CrystalSlot slot = mining.slot();
        if (slot == null) {
            restoreNormalSlot();
            return;
        }
        switch (settings.basePlace.mine.tool.getValue()) {
            case SILENT -> SlotSpoof.set(slot.hotbarIndex(), this);
            case NORMAL -> normalSwitch(slot.hotbarIndex());
            case NONE -> {
            }
        }
    }

    /**
     * Runs the three searches; the column filter goes to the placement searches, the look-through filter to the
     * rays.
     *
     * <p>The placement search hands out its whole accepted ranking so the planner can demote past a candidate
     * it cannot use. Only the PRIMARY one is pulled here — that is the cost a single best-of search had, and it
     * is the candidate the base search needs: its gain floor is defined against the best placement DIRECTLY
     * available, not against whatever the planner ends up settling for. The tail stays lazy.
     */
    private Searches searchTick(WorldView view, boolean canAttack) {
        DamageCalculator calc =
                new DamageCalculator(settings.brk.iFrames.getValue() == IFrameMode.RESPECT);
        BreakSearch breaks = new BreakSearch(view, calc, settings.place, settings.brk, ledger);
        breakSearch = breaks;
        BreakCandidate breakBest = breaks.best();
        Predicate<Entity> columns = placeColumnIgnore(breakBest, canAttack);
        PlaceSearch placeSearch = new PlaceSearch(view, calc, settings.place, ledger, columns,
                settings.brk.range.getValue().floatValue());
        Iterator<PlaceCandidate> places = placeSearch.candidates().iterator();
        PlaceCandidate placeBest = places.hasNext() ? places.next() : null;
        Rotation wire = AuraRotation.wire();

        float slack = AuraRotation.slackDegrees(settings.rotation);
        BasePlan basePlan = null;
        if (settings.basePlace.isEnabled()) {
            basePlan = new BasePlaceSearch(view, placeSearch, settings.basePlace, columns, wire, slack,
                    settings.place.throughBlocks.getValue()).best(placeBest);
        }

        // The mined base is priced against the best placement available WITHOUT digging: the direct one or,
        // when the base search found a plan this tick, whichever of the two is worth more.
        MineTarget mineTarget = null;
        if (settings.basePlace.isMining()) {
            PlaceCandidate bestExisting =
                    betterOf(placeBest, basePlan == null ? null : basePlan.candidate());
            mineTarget = new MineSearch(view, placeSearch, settings.basePlace.mine, settings.basePlace,
                    rayIgnore, wire, slack, settings.place.throughBlocks.getValue()).best(bestExisting);
        }

        Iterable<PlaceCandidate> candidates = placeBest == null
                ? List.of()
                : prepend(placeBest, places);
        return new Searches(breakBest, candidates, basePlan, mineTarget, wire, placeSearch, placeBest);
    }

    /** The better of two candidates by effective damage; the gain floor the mining search is held to. */
    @Nullable
    private static PlaceCandidate betterOf(@Nullable PlaceCandidate left, @Nullable PlaceCandidate right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return right.damage().effective() > left.damage().effective() ? right : left;
    }

    /** The already-materialised head followed by the lazy tail, iterable once. */
    private static Iterable<PlaceCandidate> prepend(PlaceCandidate head, Iterator<PlaceCandidate> tail) {
        return () -> new Iterator<>() {
            private boolean headTaken;

            @Override
            public boolean hasNext() {
                return !headTaken || tail.hasNext();
            }

            @Override
            public PlaceCandidate next() {
                if (!headTaken) {
                    headTaken = true;
                    return head;
                }
                return tail.next();
            }
        };
    }

    /**
     * Column filter for the PLACEMENT searches: the dying crystals plus the ones this tick's own attack is about
     * to remove.
     *
     * <p>Inside one tick the attack packet precedes the use packet, and the server removes the crystal and runs
     * its explosion while handling that attack — which also removes every other crystal within the blast,
     * without exploding them. By the time the placement is handled the whole column is therefore free.
     *
     * <p>Without this the aura blocked its own best position for a full cycle: at plan time the crystal it was
     * about to break still counted as an obstruction, so the search moved to the next-best block and moved back
     * once the removal was observed — the target visibly bounced between two blocks every crystal.
     *
     * <p>Only applied while the break delay is open; if the attack is then dropped anyway, the planner
     * suppresses the use with it, and the executor's own column re-check refuses the placement rather than
     * sending a doomed one.
     */
    private Predicate<Entity> placeColumnIgnore(@Nullable BreakCandidate breakBest, boolean canAttack) {
        if (breakBest == null || !canAttack) {
            return columnIgnore;
        }
        Vec3Holder center = new Vec3Holder(breakBest.crystal().position());
        return entity -> columnIgnore.test(entity)
                || (entity instanceof EndCrystal crystal
                    && ExplosionMath.distanceFraction(crystal.position(), center.value) <= 1.0);
    }

    /** A tiny holder so the lambda above captures one final reference rather than the candidate. */
    private record Vec3Holder(net.minecraft.world.phys.Vec3 value) {
    }

    /**
     * The render snapshot of the decided plan. The placement wins over the attack — it is what the aura is
     * aiming at — and the victim snapshot is the one the winning candidate was scored against.
     */
    private AuraVisuals buildVisuals(TickPlan decided) {
        PlaceCandidate candidate = decided.place();
        var attack = decided.attack();
        var entry = candidate != null ? candidate.target() : (attack != null ? attack.target() : null);
        var state = candidate != null ? candidate.target().placeState()
                : (attack != null ? attack.target().breakState() : null);
        var damage = candidate != null ? candidate.damage() : (attack != null ? attack.damage() : null);
        var self = candidate != null ? candidate.selfDamage() : (attack != null ? attack.selfDamage() : null);
        return new AuraVisuals(
                entry == null ? null : entry.entity(),
                state,
                state == null ? 0f : AuraTargetRenderer.killZoneRadius(state,
                        settings.place.minDamage.getValue().floatValue()),
                damage == null ? 0f : damage.effective(),
                self == null ? 0f : self.effective(),
                state != null && damage != null && damage.isLethalFor(state),
                decided.placePos(),
                decided.basePos(),
                decided.attackBox());
    }

    // ---------------------------------------------------------------- execute

    /** A plan is executed at most once. */
    private void executeTick() {
        MinePlan mining = minePlan;
        minePlan = null;
        if (mining != null) {
            plan = null;
            executeMine(mining);
            return;
        }

        TickPlan current = plan;
        plan = null;
        WorldView view = lastView;
        BreakSearch breaks = breakSearch;
        if (current == null || !current.attempting() || view == null || breaks == null) {
            return;
        }

        // Vanilla emits all attacks, then all uses, then the continue-attack, and we are behind all of them.
        // So an attack may not follow a foreign use or dig, and a use may not follow a dig.
        boolean allowAttack = !foreignUseSeen && !foreignDigSeen;
        // A block interaction must not follow an entity interaction inside one tick window either, and the
        // window is closed by our own movement packet, which goes out AFTER the clicks. So a foreign attack
        // this tick blocks our placement exactly like a foreign dig does.
        boolean allowUse = !foreignDigSeen && !foreignEntitySeen;

        int attacksBefore = attackPackets;
        ownSend = true;
        ExecReport report;
        try {
            report = executor.execute(current, view, ledger, breaks, rayIgnore, allowAttack, allowUse);
        } finally {
            ownSend = false;
        }

        // The attack helper silently sends nothing when another module cancels the attack event, yet the report
        // still claims it; only the packet counter can tell. The ledger's own mark needs no undo — it self-heals
        // through the attack timeout.
        boolean cancelled = report.attacked() && attackPackets == attacksBefore;
        if (report.attacked() || report.placed() || report.basePlaced()) {
            lastActionMs = System.currentTimeMillis();
        }
        if (report.attacked() && !cancelled) {
            breakClock.reset();
            recordAttack();
            recordShockwave(current);
        }
        if (report.placed() || report.basePlaced()) {
            placeClock.reset();
        }
        boolean cancelledAttack = cancelled;
        AuraDebug.log(settings.debug.getValue(), () ->
                report + (cancelledAttack ? " | attack cancelled by another module" : ""));
    }

    /**
     * The dig's own execute step. A dig packet may not join a tick in which somebody else already clicked, in
     * either order, so every foreign click of this tick closes the gate.
     */
    private void executeMine(MinePlan mining) {
        WorldView view = lastView;
        if (view == null) {
            return;
        }
        boolean allowDig = !foreignUseSeen && !foreignDigSeen && !foreignEntitySeen;
        ownSend = true;
        String report;
        try {
            report = mineJob.execute(mining, view, rayIgnore, allowDig, foreignDigSeen);
        } finally {
            ownSend = false;
        }
        AuraDebug.log(settings.debug.getValue(), () -> "mine: " + report);
    }

    // ---------------------------------------------------------------- rotation and slots

    /**
     * Requests the plan's look point, with the victim as the reset-detection entity, keeps the current wire
     * rotation alive while a cycle is in progress, or lets the rotation return otherwise.
     *
     * <p>The request is filed BEFORE the aim is stepped, so it is this tick's movement packet that carries it,
     * and the outgoing rotation already holds it when execute verifies the plan's attempts.
     */
    private void requestRotation(TickPlan decided, @Nullable BreakCandidate breakBest) {
        LookPoint look = decided.look();
        if (look != null) {
            Entity entity = decided.place() != null ? decided.place().target().entity()
                    : (decided.attack() != null ? decided.attack().target().entity() : null);
            AuraRotation.request(look.rotation(), entity, settings.rotation);
            return;
        }
        if (decided.attempting() || ledger.pendingPlacement() != null || breakBest != null) {
            AuraRotation.hold(settings.rotation);
        }
    }

    /**
     * Brings the item of a use that is OFFERED this tick into the hand now, so the game emits the single
     * carried-item change at its vanilla position. The offhand needs no switch.
     *
     * <p>It keys on the ATTEMPT, not on the hit: the hit is decided at execute, long after the game had its
     * only chance to emit the slot change. Waiting for the verdict would put the carried-item change behind the
     * click, and a held-item change between an interaction and the flying packet makes an anticheat drain its
     * queue early without a look — that is, it would forfeit the same-tick rotation. A missed attempt costs one
     * carried-item change and nothing else.
     */
    private void selectSlot(TickPlan decided) {
        if (!decided.useAttempt()) {
            restoreNormalSlot();
            return;
        }
        CrystalSlot slot = decided.slot();
        if (slot == null || slot.offHand()) {
            return;
        }
        switch (settings.place.switchMode.getValue()) {
            case SILENT -> {
                SlotSpoof.set(slot.hotbarIndex(), this);
                spoofHeldUntil = tick + settings.place.swapBack.getValue().intValue();
            }
            case NORMAL -> normalSwitch(slot.hotbarIndex());
            case NONE -> {
            }
        }
    }

    /**
     * Last tick the silent slot is kept even with nothing to place.
     *
     * <p>The spoof is a held state, not a per-tick one: dropping it the moment a tick has no use puts a
     * {@code SetCarriedItem} on the wire towards the crystal and another one straight back for every single
     * placement, which is both a packet the player never sent and twice the switching rate of anyone real.
     * {@code SwapBack} is how long the hand stays where it was put, and Aerial's slot spoof has no expiry of
     * its own, so the hold is counted here.
     */
    private int spoofHeldUntil = Integer.MIN_VALUE;

    /**
     * The vanilla-visible switch: remembers the slot the player had before the FIRST switch — a later switch
     * keeps that original — and writes the field only when it differs.
     */
    private void normalSwitch(int index) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        int selected = player.getInventory().getSelectedSlot();
        dropRestoreIfPlayerMoved(selected);
        if (selected == index) {
            return;
        }
        if (restoreSlot == null) {
            restoreSlot = selected;
        }
        player.getInventory().setSelectedSlot(index);
        forcedSlot = index;
    }

    /**
     * Gives a visible switch back, and drops the silent one. Nothing anywhere else restores the selected slot,
     * so without this the module would leave the player on the crystal slot for good.
     */
    private void restoreNormalSlot() {
        if (tick > spoofHeldUntil) {
            SlotSpoof.reset(this);
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        int selected = player.getInventory().getSelectedSlot();
        dropRestoreIfPlayerMoved(selected);
        if (restoreSlot == null) {
            return;
        }
        int target = restoreSlot;
        restoreSlot = null;
        forcedSlot = null;
        if (selected != target) {
            player.getInventory().setSelectedSlot(target);
        }
    }

    /** Never fight the player: once their own selection left the slot we forced, the pending restore is void. */
    private void dropRestoreIfPlayerMoved(int selected) {
        if (restoreSlot != null && (forcedSlot == null || selected != forcedSlot)) {
            restoreSlot = null;
            forcedSlot = null;
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The end crystal slot. A HOTBAR slot beats the offhand: with any other item in the main hand, vanilla's
     * hand loop consumes the use with that item first, so the executor rejects the offhand placement outright,
     * while a hotbar crystal is one carried-item change away. With no switching allowed, only the slot the
     * server already holds — or the offhand, which needs none — can actually be used.
     */
    @Nullable
    private CrystalSlot crystalSlot() {
        CrystalSlot hotbar = hotbarSlot(Items.END_CRYSTAL);
        CrystalSlot offhand = offhandSlot(Items.END_CRYSTAL);
        if (settings.place.switchMode.getValue() != SwitchMode.NONE) {
            return hotbar != null ? hotbar : offhand;
        }
        if (hotbar != null && hotbar.hotbarIndex() == CrystalSlot.serverSelectedSlot()) {
            return hotbar;
        }
        return offhand != null ? offhand : hotbar;
    }

    /** The first hotbar slot holding the item, or null. */
    @Nullable
    private static CrystalSlot hotbarSlot(net.minecraft.world.item.Item item) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return null;
        }
        for (int index = 0; index < 9; index++) {
            if (player.getInventory().getItem(index).is(item)) {
                return CrystalSlot.hotbar(index);
            }
        }
        return null;
    }

    /** The offhand when it holds the item, or null. */
    @Nullable
    private static CrystalSlot offhandSlot(net.minecraft.world.item.Item item) {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.getOffhandItem().is(item) ? CrystalSlot.offhand() : null;
    }

    /**
     * Records the click packets of this tick. No self-filter is needed for the aura itself, which reads the
     * flags before it sends anything, but the flag is honoured so a future dig from the same execute step does
     * not read its own packets back as foreign.
     */
    private void observeOutgoing(Packet<?> packet) {
        if (settings.debug.getValue()) {
            packetTrace.add((ownSend ? "*" : "") + packet.getClass().getSimpleName()
                    .replaceFirst("^Serverbound", "").replaceFirst("Packet$", ""));
        }
        // Counted for OURS as well — execute brackets the executor with it to tell a real attack from one
        // another module cancelled.
        if (packet instanceof ServerboundAttackPacket) {
            attackPackets++;
        }
        if (ownSend) {
            return;
        }
        if (packet instanceof ServerboundUseItemOnPacket || packet instanceof ServerboundUseItemPacket) {
            foreignUseSeen = true;
        } else if (packet instanceof ServerboundAttackPacket || packet instanceof ServerboundInteractPacket) {
            foreignEntitySeen = true;
        } else if (packet instanceof ServerboundPlayerActionPacket action && isDigAction(action.getAction())) {
            foreignDigSeen = true;
        }
    }

    /**
     * Only the three dig actions belong to vanilla's attack loop. The offhand swap and the drops are sent
     * BEFORE the click loops, and the release-use comes from elsewhere, so none of them says anything about the
     * order of the clicks.
     */
    private static boolean isDigAction(ServerboundPlayerActionPacket.Action action) {
        return action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                || action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK
                || action == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK;
    }

    private int placePredictTicks(int pingTicks) {
        return switch (settings.place.predict.getValue()) {
            case OFF -> 0;
            case AUTO -> (int) Math.round(PLACE_PREDICT_FACTOR * pingTicks) + 1;
            case FIXED -> settings.place.predictTicks.getValue().intValue();
        };
    }

    private int breakPredictTicks(int pingTicks) {
        return switch (settings.place.predict.getValue()) {
            case OFF -> 0;
            case AUTO -> (int) Math.round(BREAK_PREDICT_FACTOR * pingTicks);
            case FIXED -> settings.place.predictTicks.getValue().intValue();
        };
    }

    /** Round-trip time to the server in milliseconds, 0 when unknown. */
    private static int ping() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientPacketListener connection = minecraft.getConnection();
        if (player == null || connection == null) {
            return 0;
        }
        var info = connection.getPlayerInfo(player.getUUID());
        return info == null ? 0 : info.getLatency();
    }

    /**
     * Resets everything when the level is no longer the one the state was built on.
     *
     * <p>The world-change handler cannot carry this alone: it is gated on the module handling events, and the
     * level may already be null by the time it would fire, so stale ledger ids would leak into a server whose
     * entity ids restart at zero.
     */
    private void guardLevel() {
        ClientLevel level = Minecraft.getInstance().level;
        if (levelRef == null || levelRef.get() != level) {
            resetState();
            levelRef = level == null ? null : new WeakReference<>(level);
        }
    }

    /**
     * A screen or overlay makes vanilla skip the keybind handling and with it execute, so a plan made now could
     * never be clicked. Drop it and give the hand back instead of rotating the player server-side and holding a
     * slot for a whole inventory session.
     */
    private void pauseForScreen() {
        plan = null;
        minePlan = null;
        mineJob.reset();
        visuals = null;
        restoreNormalSlot();
        AuraRotation.update(settings.rotation);
    }

    /**
     * Whether an attack and a use may share this tick: the same-tick setting, narrowed by its safe mode to
     * ticks in which the player is standing still.
     *
     * <p>Movement is measured as the per-tick position delta; sprinting counts as moving even when the delta is
     * small, for instance into a wall. Asymmetric on purpose: movement disables the mode instantly, and it only
     * returns after several calm ticks in a row.
     */
    private boolean sameTickAllowed(LocalPlayer player) {
        if (!settings.place.sameTick.getValue()) {
            return false;
        }
        if (!settings.place.sameTickSafe.getValue()) {
            return true;
        }
        double dx = player.getX() - player.xo;
        double dy = player.getY() - player.yo;
        double dz = player.getZ() - player.zo;
        boolean moved = dx * dx + dy * dy + dz * dz > STILL_EPSILON_SQ || player.isSprinting();
        calmTicks = moved ? 0 : calmTicks + 1;
        return calmTicks >= SAME_TICK_CALM_TICKS;
    }

    private void recordAttack() {
        attackTicks[attackCursor] = tick;
        attackCursor = (attackCursor + 1) % ATTACK_RING;
    }

    /**
     * Reports the detonation whose attack really went out — the crystal's live position, or the centre
     * predicted for a phantom. Rendering only: nothing here is read back by the plan.
     */
    private void recordShockwave(TickPlan decided) {
        if (decided.attack() != null) {
            AuraRenderer.recordShockwave(decided.attack().crystal().position());
        } else if (decided.phantomAttack() != null) {
            AuraRenderer.recordShockwave(decided.phantomAttack().center());
        }
    }

    private int recentAttacks() {
        int count = 0;
        for (int stamp : attackTicks) {
            int age = tick - stamp;
            if (age >= 0 && age < TAG_WINDOW_TICKS) {
                count++;
            }
        }
        return count;
    }

    /**
     * Dumps the previous tick's outgoing packets as one line. This is the ground truth for every tick-window
     * check: an anticheat groups packets between tick packets, and the movement packet is the delimiter
     * whenever the tick sent one.
     *
     * <p>Only ticks that can actually trip such a check are logged: one carrying at least two click packets.
     * Everything else is the ordinary one-action tick and would drown the interesting lines.
     */
    private void tracePreviousTick() {
        if (packetTrace.isEmpty()) {
            return;
        }
        List<String> names = new ArrayList<>();
        String name;
        while ((name = packetTrace.poll()) != null) {
            names.add(name);
        }
        if (!settings.debug.getValue() || !worthTracing(names)) {
            return;
        }
        LOGGER.info("[CrystalAura] packets: {}", String.join(" ", names));
    }

    private static boolean worthTracing(List<String> names) {
        int clicks = 0;
        for (String name : names) {
            if (isClickName(name)) {
                clicks++;
            }
        }
        return clicks >= 2;
    }

    private static boolean isClickName(String name) {
        String bare = name.startsWith("*") ? name.substring(1) : name;
        return bare.equals("Attack") || bare.equals("Interact") || bare.equals("UseItemOn")
                || bare.equals("UseItem") || bare.equals("PlayerAction");
    }

    /**
     * Whether the silent aim is actually reaching the wire.
     *
     * <p>"the module acts but does not turn" and "the module turns but the server is not told" look the same
     * from inside the game. The stamp counter settles it: it only moves when a movement packet has actually
     * been rewritten.
     */
    private String aimNote() {
        Minecraft minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        var out = SilentAim.INSTANCE.outgoing();
        var wire = SilentAim.INSTANCE.wire();
        return " [aim stamped=" + SilentAim.INSTANCE.appliedCount()
                + " active=" + SilentAim.INSTANCE.isActive()
                + " out=" + String.format(java.util.Locale.ROOT, "%.1f/%.1f", out.x, out.y)
                + " wire=" + String.format(java.util.Locale.ROOT, "%.1f/%.1f", wire.x, wire.y)
                + " real=" + (player == null ? "?" : String.format(java.util.Locale.ROOT, "%.1f/%.1f",
                        player.getYRot(), player.getXRot()))
                + " corr=" + settings.rotation.movementCorrection.getValue()
                + " srvYaw=" + String.format(java.util.Locale.ROOT, "%.1f",
                        cc.aerial.client.rotation.ServerRotation.getYawOr(Float.NaN))
                + "]";
    }

    /**
     * What an idle tick was missing, appended to the plan line.
     *
     * <p>"idle" has half a dozen causes that look identical: no crystal in the bag, nowhere to stand one, a
     * spot that exists but is not worth the damage, or nothing to hit. Built only when the line is actually
     * printed, and only for an idle tick.
     */
    private String idleReason(TickPlan decided, Searches searches) {
        if (decided.attack() != null || decided.place() != null || decided.base() != null) {
            return "";
        }
        WorldView view = lastView;
        return " | crystal=" + (crystalSlot() != null)
                + " obsidian=" + (hotbarSlot(Items.OBSIDIAN) != null)
                + " cells=" + searches.placeSearch().cellsFound()
                + " placeBest=" + (searches.placeBest() != null)
                + " breakBest=" + (searches.breakBest() != null)
                + " base=" + (searches.basePlan() != null)
                + " crystalsInWorld=" + (view == null ? -1 : view.crystals().size())
                + " " + searches.placeSearch().peaks()
                + " min=" + settings.place.minDamage.getValue()
                + " maxSelf=" + settings.place.maxSelfDamage.getValue()
                + victimNote(view);
    }

    /**
     * The first victim's state, for an idle tick whose upper bound came out at zero.
     *
     * <p>A zero bound before any ray is cast can only come from the victim-side pipeline, and there are
     * exactly three ways in: the victim is skipped outright, the difficulty zeroes player damage, or the
     * predicted feet are nowhere near the crystal. All three are invisible in the plan line.
     */
    private String victimNote(@Nullable WorldView view) {
        if (view == null || view.targets().isEmpty()) {
            return "";
        }
        var first = view.targets().getFirst();
        var state = first.placeState();
        return " || victim=" + (first.entity() == null ? "?" : first.entity().getName().getString())
                + " skip=" + state.skip()
                + " player=" + state.isPlayer()
                + " diff=" + state.difficulty()
                + " armor=" + state.armor()
                + " res=" + state.resistanceAmplifier()
                + " prot=" + state.protectionPoints()
                + " feet=" + fmtVec(state.feet())
                + " eyeDist=" + String.format(java.util.Locale.ROOT, "%.2f",
                        state.feet().distanceTo(view.eye()));
    }

    private static String fmtVec(net.minecraft.world.phys.Vec3 vec) {
        return String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.1f", vec.x, vec.y, vec.z);
    }

    /**
     * The plan line, plus the exactness cross-check of the damage model against vanilla's own code. The check
     * runs before the tick's actions have changed the world, which is what its shape-source memo assumes.
     */
    private void debugPlan(TickPlan decided, Searches searches) {
        boolean on = settings.debug.getValue();
        AuraDebug.log(on, () -> decided.describe() + aimNote() + idleReason(decided, searches));
        if (!on) {
            return;
        }
        if (decided.attack() != null) {
            AuraDebug.crossCheck(true, decided.attack().center(), decided.attack().target(),
                    decided.attack().damage(), true);
        }
        if (decided.place() != null) {
            AuraDebug.crossCheck(true, decided.place().center(), decided.place().target(),
                    decided.place().damage(), false);
        }
    }

    /**
     * Forgets everything and hands a visible switch back. The clocks are set to zero rather than to now, so the
     * first action after a reset is immediate.
     */
    private void resetState() {
        restoreNormalSlot();
        ledger.reset();
        intake.reset();
        plan = null;
        minePlan = null;
        mineJob.reset();
        target = null;
        visuals = null;
        AuraRenderer.reset();
        lastView = null;
        breakSearch = null;
        rayIgnore = IGNORE_NONE;
        tick = 0;
        calmTicks = 0;
        Arrays.fill(attackTicks, Integer.MIN_VALUE);
        attackCursor = 0;
        foreignUseSeen = false;
        foreignDigSeen = false;
        foreignEntitySeen = false;
        packetTrace.clear();
        breakClock.reset();
        placeClock.reset();
        AuraRotation.reset();
        AuraDebug.reset();
    }

    private static Predicate<Entity> rayIgnoreFor(LookThrough mode) {
        return switch (mode) {
            case OFF -> IGNORE_NONE;
            case CRYSTALS -> IGNORE_CRYSTALS;
            case ALL -> IGNORE_ALL;
        };
    }

    /**
     * Results of one tick's searches plus the wire rotation they were made against. The candidate list is the
     * accepted placement ranking, best first, with only its head materialised; it may be iterated once.
     */
    private record Searches(
            @Nullable BreakCandidate breakBest,
            Iterable<PlaceCandidate> placeCandidates,
            @Nullable BasePlan basePlan,
            @Nullable MineTarget mineTarget,
            Rotation wire,
            PlaceSearch placeSearch,
            @Nullable PlaceCandidate placeBest) {
    }
}
