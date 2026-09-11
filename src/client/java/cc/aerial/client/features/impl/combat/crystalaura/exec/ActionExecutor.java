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

import cc.aerial.client.features.impl.combat.crystalaura.aim.AuraRotation;
import cc.aerial.client.features.impl.combat.crystalaura.aim.RayTests;
import cc.aerial.client.features.impl.combat.crystalaura.aim.Rotation;
import cc.aerial.client.features.impl.combat.crystalaura.base.BasePlan;
import cc.aerial.client.features.impl.combat.crystalaura.base.BaseRules;
import cc.aerial.client.features.impl.combat.crystalaura.calc.ExplosionMath;
import cc.aerial.client.features.impl.combat.crystalaura.config.BasePlaceSettings;
import cc.aerial.client.features.impl.combat.crystalaura.config.BreakSettings;
import cc.aerial.client.features.impl.combat.crystalaura.config.PlaceSettings;
import cc.aerial.client.features.impl.combat.crystalaura.scan.BreakSearch;
import cc.aerial.client.features.impl.combat.crystalaura.scan.PlaceCandidate;
import cc.aerial.client.features.impl.combat.crystalaura.state.CrystalLedger;
import cc.aerial.client.features.impl.combat.crystalaura.world.WorldView;
import cc.aerial.client.scaffold.SlotSpoof;
import cc.aerial.client.mixin.MultiPlayerGameModeAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Performs a plan at the vanilla click position: the tail of the keybind handling, after the carried-item change
 * has been sent and before the movement packet goes out. Attack first, then the use — the vanilla order. Every
 * step re-verifies its preconditions against the live world and against the outgoing rotation, and rejects with a
 * reason instead of acting.
 *
 * <h2>The rotation every ray is traced against</h2>
 * <p>The outgoing rotation is what THIS tick's movement packet will carry: the aim was stepped at the head of the
 * tick, right after the plan, so by the time this runs the value is final and quantised. A rotation the plan
 * requested a few hundred microseconds ago therefore already covers the click we are about to send — which is
 * exactly the vanilla ordering, since the mouse is only applied in the render section after the tick loop, and
 * the one an anticheat evaluates, since it re-runs placement and break checks against the new rotation.
 *
 * <p>This is where the plan's ATTEMPTS become actions: the plan decides intent, the ray tests below decide the
 * hit. They are exact — no tolerance is widened to make an attempt land, because a single post-flying miss can
 * arm a path that cancels placements for the next several interactions. A miss simply sends nothing; the next
 * tick retries with the rotation by then on the wire.
 *
 * <h2>Foreign-click gates</h2>
 * <p>The hub clears the attack or use gate for the tick once a foreign use or dig packet has already gone out.
 * This runs AFTER vanilla's own attack loop and use loops, so appending our attack behind the player's own use
 * would put an attack after a use inside one tick — a packet order vanilla never produces.
 *
 * <h2>Gates copied from vanilla</h2>
 * <ul>
 *   <li>Both actions: alive and not spectating; not using an item, since the keybind handler swallows every click
 *       while an item is in use.</li>
 *   <li>Attack: the miss-time lockout; the main-hand item must be enabled, must not be below the minimum attack
 *       charge, and must not carry a piercing weapon component, which would divert the attack; an attack-range
 *       component in the main hand must accept the hit location.</li>
 *   <li>Use: not destroying a block; the item of the hand must be enabled.</li>
 * </ul>
 *
 * <h2>The manual swing</h2>
 * <p>A crystal we attacked this tick, or expect to be chain-removed, still exists client-side; the client-side
 * placement then fails because of it and vanilla would not swing. The use-on packet was sent anyway, and the
 * server — which processes the attack packet first and has already removed that crystal — places the crystal. So
 * when the ONLY obstruction is such a crystal, the hand THE RESULT CAME FROM is swung manually: the packet
 * sequence equals that of a legit client whose removal packet had already arrived. Any other client-side failure
 * is reported and not swung.
 */
public final class ActionExecutor {
    /** Reported when the hub's attack gate is closed. */
    private static final String ATTACK_BLOCKED = "attack blocked this tick";

    /** Reported when the hub's use gate is closed. */
    private static final String USE_BLOCKED = "use blocked this tick";

    /** The partial tick the vanilla attack passes to the strength scale. */
    private static final float STRENGTH_PARTIAL = 0.5f;

    /** A full-strength attack is one above this scale. */
    private static final float FULL_STRENGTH_SCALE = 0.9f;

    /** The knockback a sprinting full-strength attack adds. */
    private static final float SPRINT_KNOCKBACK_BONUS = 0.5f;

    /** The knockback attribute is halved on the way in. */
    private static final float KNOCKBACK_HALVING = 2.0f;

    private final PlaceSettings place;
    private final BreakSettings brk;
    private final BasePlaceSettings base;

    private ItemStack obsidianStack;

    public ActionExecutor(PlaceSettings place, BreakSettings brk, BasePlaceSettings base) {
        this.place = place;
        this.brk = brk;
        this.base = base;
    }

    private double placeRange() {
        return place.range.getValue();
    }

    private double breakRange() {
        return brk.range.getValue();
    }

    private double baseRange() {
        return base.range.getValue();
    }

    /**
     * Runs the executable part of the plan, attack first and then the use, and reports what happened.
     *
     * <p>An attempt whose exact ray reaches neither the outgoing nor the wire rotation is reported as a miss and
     * nothing is sent; the hub leaves its delay clock untouched and the next tick tries again.
     */
    public ExecReport execute(TickPlan plan, WorldView view, CrystalLedger ledger, BreakSearch breakSearch,
                              Predicate<Entity> rayIgnore, boolean allowAttack, boolean allowUse) {
        String common = commonGate();
        if (common != null) {
            return ExecReport.rejected(common);
        }

        // The rotation the SERVER will judge these clicks with, which is the last one it was actually sent.
        //
        // Neither ServerboundUseItemOnPacket nor ServerboundAttackPacket carries a rotation: the server uses
        // whatever the last movement packet gave it. Those clicks leave in `handleKeybinds`, and the movement
        // packet only leaves later, in `tickEntities` — so this tick's aim reaches the server AFTER this tick's
        // clicks. Validating against the outgoing rotation therefore checks the ray against a rotation the
        // server does not have yet, and a placement made while the aim is still travelling is judged by the
        // server against the previous one.
        //
        // A DELIBERATE divergence from LiquidBounce, which validates against the outgoing rotation. The cost
        // is one tick of latency after the aim moves; the gain is that what we verify is what the server sees.
        Rotation aim = AuraRotation.wire();
        String attackReason = null;
        EndCrystal attackedCrystal = null;
        if (plan.attackAttempt()) {
            attackReason = allowAttack
                    ? attack(plan, view, ledger, breakSearch, aim, rayIgnore)
                    : ATTACK_BLOCKED;
            if (attackReason == null && plan.attack() != null) {
                attackedCrystal = plan.attack().crystal();
            }
        }
        boolean attacked = plan.attackAttempt() && attackReason == null;

        String useReason;
        if (!plan.useAttempt()) {
            useReason = null;
        } else if (!allowUse) {
            useReason = USE_BLOCKED;
        } else {
            useReason = use(plan, view, ledger, aim, ignoreAlso(attackedCrystal, rayIgnore));
        }
        boolean used = plan.useAttempt() && useReason == null;
        return new ExecReport(attacked, used && plan.base() == null, used && plan.base() != null,
                reason(attackReason, useReason));
    }

    /**
     * Runs the test against the outgoing rotation — the one this tick's movement packet will carry — and, when
     * that misses, against the rotation already on the wire.
     *
     * <p>Both are EXACT rotations, and both are raytrace candidates of the post-flying check that actually
     * decides: the current and the previous rotation, with the check passing as soon as ANY candidate reaches
     * the target. So nothing here is loosened: each attempt is the same exact ray test from a rotation the
     * server is known to accept, and an action that reaches from neither is still refused.
     *
     * <p>Why both rather than the outgoing one alone: that would drop an action whenever our rotation request
     * lost to a higher-priority module, whenever a smooth step is still in flight, and — with same-tick — on the
     * tick where the turn towards the placement leaves the crystal the same tick's attack needs.
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

    /** Gates shared by both actions, null when open. */
    @Nullable
    private static String commonGate() {
        LocalPlayer player = player();
        if (player == null) {
            return "no player";
        }
        if (!player.isAlive() || player.isSpectator()) {
            return "dead or spectator";
        }
        if (player.isUsingItem()) {
            return "using an item";
        }
        return null;
    }

    @Nullable
    private String attack(TickPlan plan, WorldView view, CrystalLedger ledger, BreakSearch breakSearch,
                          Rotation aim, Predicate<Entity> rayIgnore) {
        String gate = attackGate();
        if (gate != null) {
            return gate;
        }
        if (plan.attack() != null) {
            return attackCrystal(plan.attack().crystal(), view, ledger, breakSearch, aim, rayIgnore);
        }
        PhantomAttack phantom = plan.phantomAttack();
        if (phantom == null) {
            return "nothing to attack";
        }
        return attackPhantom(phantom, view, ledger, breakSearch, aim, rayIgnore);
    }

    /** The vanilla attack-key gates, null when open. */
    @Nullable
    private String attackGate() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return "no world";
        }
        if (minecraft.missTime > 0 && !brk.ignoreMissTime.getValue()) {
            return "miss time";
        }
        ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!held.isItemEnabled(level.enabledFeatures())) {
            return "main-hand item disabled";
        }
        if (player.cannotAttackWithItem(held, 0)) {
            return "attack charge too low";
        }
        if (held.has(DataComponents.PIERCING_WEAPON)) {
            return "piercing weapon in hand";
        }
        return null;
    }

    /**
     * The attack-range branch of the vanilla attack key: with such a component in the main hand, vanilla only
     * attacks while the component accepts the hit location, and otherwise merely swings.
     *
     * <p>In practice a dead gate, because the vanilla spear family carries the range component together with the
     * piercing one and the gate above already refuses a piercing weapon. It exists for data-pack or mod items
     * that carry the range alone, where sending the attack would be a packet vanilla could not have produced.
     */
    @Nullable
    private static String attackRangeGate(Vec3 location) {
        LocalPlayer player = player();
        if (player == null) {
            return "no player";
        }
        var range = player.getItemInHand(InteractionHand.MAIN_HAND).get(DataComponents.ATTACK_RANGE);
        if (range == null) {
            return null;
        }
        return range.isInRange(player, location) ? null : "outside the item attack range";
    }

    @Nullable
    private String attackCrystal(EndCrystal crystal, WorldView view, CrystalLedger ledger, BreakSearch breakSearch,
                                 Rotation aim, Predicate<Entity> rayIgnore) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return "no world";
        }
        if (!crystal.isAlive() || level.getEntity(crystal.getId()) != crystal) {
            return "crystal gone";
        }
        if (ledger.isAttacked(crystal.getId())) {
            return "crystal already attacked";
        }
        Predicate<Entity> ignoreOther = ignoreExcept(crystal, rayIgnore);
        var hit = byAim(aim, rot -> RayTests.hitsCrystal(view.eye(), rot, crystal, breakRange(), ignoreOther));
        if (hit == null) {
            return "attack ray misses";
        }
        String rangeGate = attackRangeGate(hit.getLocation());
        if (rangeGate != null) {
            return rangeGate;
        }

        CrystalInteract.attack(crystal);
        bookAttack(crystal.getId(), crystal.position(), view, ledger, breakSearch);
        return null;
    }

    /**
     * The predicted attack. Freshness of the pending placement was decided by the planner; what is left here is
     * the client-side proof that the id is still unused, that it was not attacked yet, that the rotation reaches
     * the predicted box, and that an attack-range component accepts the point.
     */
    @Nullable
    private String attackPhantom(PhantomAttack phantom, WorldView view, CrystalLedger ledger,
                                 BreakSearch breakSearch, Rotation aim, Predicate<Entity> rayIgnore) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return "no world";
        }
        if (level.getEntity(phantom.id()) != null) {
            return "predicted id already spawned";
        }
        if (ledger.isAttacked(phantom.id())) {
            return "phantom already attacked";
        }
        Vec3 point = byAim(aim,
                rot -> RayTests.hitsPhantomBox(view.eye(), rot, phantom.box(), breakRange(), rayIgnore));
        if (point == null) {
            return "phantom ray misses";
        }
        String rangeGate = attackRangeGate(point);
        if (rangeGate != null) {
            return rangeGate;
        }

        sendPhantomAttack(phantom.id());
        bookAttack(phantom.id(), phantom.center(), view, ledger, breakSearch);
        return null;
    }

    /**
     * The vanilla attack for an id with no client-side entity: announce the carried item, send the attack
     * packet, apply the reproducible part of the client-side prediction — kept in vanilla's slot BEFORE the
     * ticker reset, because the sprint condition reads the attack strength — reset the ticker, then swing.
     */
    private static void sendPhantomAttack(int id) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientPacketListener connection = minecraft.getConnection();
        if (player == null || connection == null || minecraft.gameMode == null) {
            return;
        }
        ((MultiPlayerGameModeAccessor) minecraft.gameMode).aerial$ensureHasSentCarriedItem();
        connection.send(new ServerboundAttackPacket(id));
        phantomSprintReset(player);
        player.resetAttackStrengthTicker();
        CrystalInteract.swing(InteractionHand.MAIN_HAND);
    }

    /**
     * The ONE side effect of the client-side attack prediction that is observable on the wire and reproducible
     * without an entity: a sprinting full-strength attack stops the sprint, and the stop is announced by the
     * movement code later in the same tick — which is why the flag is flipped rather than the packet sent by
     * hand.
     *
     * <p>Two assumptions, both harmless because they can only ADD a sprint stop: that the attack deals damage at
     * all, and that the target was hurt, which holds for a crystal.
     */
    private static void phantomSprintReset(LocalPlayer player) {
        if (!player.isSprinting()) {
            return;
        }
        boolean knockbackAttack = player.getAttackStrengthScale(STRENGTH_PARTIAL) > FULL_STRENGTH_SCALE;
        float bonus = knockbackAttack ? SPRINT_KNOCKBACK_BONUS : 0.0f;
        float knockback = (float) player.getAttributeValue(Attributes.ATTACK_KNOCKBACK) / KNOCKBACK_HALVING + bonus;
        if (knockback > 0.0f) {
            player.setSprinting(false);
        }
    }

    /**
     * Ledger bookkeeping of a sent attack: the lastHurt estimates, and every other crystal inside the explosion
     * radius as expected-removed.
     */
    private static void bookAttack(int id, Vec3 center, WorldView view, CrystalLedger ledger,
                                   BreakSearch breakSearch) {
        ledger.markAttacked(id, breakSearch.predictedLastHurt(center));
        for (EndCrystal other : view.crystals()) {
            if (other.getId() == id || ExplosionMath.distanceFraction(other.position(), center) > 1.0) {
                continue;
            }
            ledger.markExpectedRemoved(other.getId());
        }
    }

    @Nullable
    private String use(TickPlan plan, WorldView view, CrystalLedger ledger, Rotation aim,
                       Predicate<Entity> ignore) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameMode == null || minecraft.gameMode.isDestroying()) {
            return "destroying a block";
        }
        CrystalSlot slot = plan.slot();
        if (slot == null) {
            return "no slot";
        }
        BasePlan basePlan = plan.base();
        String gate = slotGate(slot, basePlan != null ? Items.OBSIDIAN : Items.END_CRYSTAL);
        if (gate != null) {
            return gate;
        }
        if (basePlan != null) {
            return placeBase(basePlan, view, aim, ignore);
        }
        PlaceCandidate candidate = plan.place();
        if (candidate == null) {
            return "nothing to use";
        }
        return placeCrystal(candidate, slot, view, ledger, aim, ignore);
    }

    /**
     * The stack the SERVER believes is in {@code hand}.
     *
     * <p>Every rule replicated here — which hand takes the use, whether the main-hand item consumes it — is
     * one the server resolves against the slot it was told to hold. Silent switching changes only that, so
     * reading the real hand would replicate the rules against the wrong item and reach the opposite verdict.
     */
    private static ItemStack serverHeld(LocalPlayer player, InteractionHand hand) {
        if (hand == InteractionHand.OFF_HAND) {
            return player.getOffhandItem();
        }
        return SlotSpoof.isActive() ? SlotSpoof.getStack() : player.getItemInHand(hand);
    }

    /** The slot still holds the expected item, the server holds the slot, and the item is enabled. */
    @Nullable
    private static String slotGate(CrystalSlot slot, Item expected) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return "no world";
        }
        InteractionHand hand = slot.useHand();
        // The PLANNED slot, not the hand. Silent switching moves only the server's idea of the selected slot,
        // and Aerial's spoof reaches that through `Inventory.getSelectedSlot()` — while `getItemInHand` goes
        // through `getSelectedItem`, which reads the `selected` FIELD and never calls the getter (verified in
        // 26.2 bytecode). Asking the hand therefore returns whatever the player is really holding, so the gate
        // refused every silent placement unless the item happened to be in the visible hand already.
        ItemStack held = slot.stack();
        if (!slot.isHeld()) {
            return "slot not held server-side";
        }
        if (held.getItem() != expected) {
            return "item no longer in the planned slot";
        }
        if (!held.isItemEnabled(level.enabledFeatures())) {
            return "item disabled";
        }
        if (hand == InteractionHand.OFF_HAND
                && mainHandConsumesUse(serverHeld(player, InteractionHand.MAIN_HAND))) {
            return "main-hand item would consume the use";
        }
        return null;
    }

    @Nullable
    private String placeCrystal(PlaceCandidate candidate, CrystalSlot slot, WorldView view, CrystalLedger ledger,
                                Rotation aim, Predicate<Entity> ignore) {
        BlockPos pos = candidate.pos();
        BlockPos above = pos.above();
        String gate = crystalGate(pos, above, view, ledger);
        if (gate != null) {
            return gate;
        }
        BlockHitResult hit = byAim(aim, rot -> RayTests.hitsBlock(view.eye(), rot, pos, placeRange(),
                ignore, place.throughBlocks.getValue()));
        if (hit == null) {
            return "use ray misses";
        }

        InteractionOutcome outcome;
        if (slot.offHand()) {
            outcome = useOnLikeVanilla(hit);
        } else {
            InteractionResult result = CrystalInteract.useOn(hit, InteractionHand.MAIN_HAND);
            outcome = result == null ? null
                    : new InteractionOutcome(InteractionHand.MAIN_HAND,
                            InteractionOutcome.Source.USE_ITEM_ON, result);
        }
        String reason = crystalOutcome(outcome, above, view, ledger);
        if (reason == null) {
            ledger.markPlaced(above, ledger.predictNextId());
        }
        return reason;
    }

    /**
     * The vanilla placement preconditions minus the crystals that are about to vanish, plus the world border
     * check that would otherwise drop the packet.
     */
    @Nullable
    private static String crystalGate(BlockPos pos, BlockPos above, WorldView view, CrystalLedger ledger) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return "no world";
        }
        if (!view.isPlaceable(pos)) {
            return "block no longer placeable";
        }
        if (!view.isColumnFree(above, dyingCrystals(ledger))) {
            return "column obstructed";
        }
        if (!level.getWorldBorder().isWithinBounds(pos)) {
            return "outside the world border";
        }
        return null;
    }

    /**
     * A success means placed. A client-side failure whose only cause is a crystal we attacked or expect to be
     * removed means a manual swing and also placed. Anything else is reported — the packet still went out.
     *
     * <p>The swung hand is the one the result actually came from, NOT the hand holding the crystal: the
     * vanilla-like offhand loop runs the MAIN hand first and can end there, and swinging the offhand for a
     * main-hand result would put a swing on the wire that no client would send.
     *
     * <p>The failure branch is deliberately narrow: the column must hold at least one entity, and every entity
     * in it must be a dying crystal, so a failure from the block half of the placement rule or from any foreign
     * entity is reported, never swung.
     */
    @Nullable
    private static String crystalOutcome(@Nullable InteractionOutcome outcome, BlockPos above, WorldView view,
                                         CrystalLedger ledger) {
        if (outcome == null) {
            return "use not consumed (null)";
        }
        if (outcome.result() instanceof InteractionResult.Success) {
            return null;
        }
        if (!(outcome.result() instanceof InteractionResult.Fail)) {
            return "use not consumed (" + outcome.result() + ")";
        }

        boolean occupied = !view.isColumnFree(above, entity -> false);
        if (!occupied || !view.isColumnFree(above, dyingCrystals(ledger))) {
            return "client-side fail";
        }
        CrystalInteract.swing(outcome.hand());
        return null;
    }

    /**
     * The vanilla hand loop for a block hit — main hand, then offhand: a disabled item ends the loop, the
     * use-on ends it on a success or a failure, and a non-empty stack that passed tries the in-air use and ends
     * it on a success. Returns the consuming result together with the hand and the call it came from, or null
     * when no hand consumed the click.
     *
     * <p>Neither in-air leg can consume here, so the outcome check never mistakes one for a placement: the
     * main-hand one is refused up front by the slot gate, and the offhand holds the crystal, whose item does not
     * override the in-air use and therefore passes.
     */
    @Nullable
    private static InteractionOutcome useOnLikeVanilla(BlockHitResult hit) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return null;
        }
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = serverHeld(player, hand);
            if (!held.isItemEnabled(level.enabledFeatures())) {
                return null;
            }
            InteractionResult onBlock = CrystalInteract.useOn(hit, hand);
            if (onBlock instanceof InteractionResult.Success || onBlock instanceof InteractionResult.Fail) {
                return new InteractionOutcome(hand, InteractionOutcome.Source.USE_ITEM_ON, onBlock);
            }
            if (held.isEmpty()) {
                continue;
            }
            InteractionResult used = CrystalInteract.useItem(hand);
            if (used instanceof InteractionResult.Success) {
                return new InteractionOutcome(hand, InteractionOutcome.Source.USE_ITEM, used);
            }
        }
        return null;
    }

    /**
     * Conservative static answer to whether the main-hand pass of the vanilla use would consume the click on
     * obsidian.
     *
     * <p>Obsidian and bedrock have no block use, so the pass consumes exactly when the item's own use-on or
     * in-air use does: an empty hand never; a plain item — swords and pickaxes are registered as plain items —
     * passes on use-on and only consumes in the air with a consumable, a swappable equippable, a blocking or a
     * kinetic component; every item subclass is treated as consuming.
     */
    private static boolean mainHandConsumesUse(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getItem().getClass() != Item.class) {
            return true;
        }
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        return stack.has(DataComponents.CONSUMABLE)
                || (equippable != null && equippable.swappable())
                || stack.has(DataComponents.BLOCKS_ATTACKS)
                || stack.has(DataComponents.KINETIC_WEAPON);
    }

    /**
     * Re-verifies the base the way the search admitted it, re-traces the click against the outgoing rotation and
     * clicks with the obsidian in the main hand.
     *
     * <p>The face is constrained only for the RELATIVE form. It is null for the replace-in-place form, where
     * vanilla puts the obsidian into the clicked cell whatever the hit direction was — so whatever direction the
     * ray really produced goes on the wire, exactly as vanilla does.
     */
    @Nullable
    private String placeBase(BasePlan basePlan, WorldView view, Rotation aim, Predicate<Entity> ignore) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return "no world";
        }
        BlockState state = view.state(basePlan.block());
        if (!state.isAir() && !BaseRules.canBeReplacedWith(state, basePlan.block(), obsidianStack())) {
            return "base block no longer replaceable";
        }
        if (!level.getWorldBorder().isWithinBounds(basePlan.clickPos())) {
            return "outside the world border";
        }
        BlockHitResult hit = byAim(aim, rot -> {
            BlockHitResult candidate = RayTests.hitsBlock(view.eye(), rot, basePlan.clickPos(), baseRange(),
                    ignore, place.throughBlocks.getValue());
            if (candidate == null) {
                return null;
            }
            return basePlan.face() == null || candidate.getDirection() == basePlan.face() ? candidate : null;
        });
        if (hit == null) {
            return "base ray misses";
        }

        InteractionResult result = CrystalInteract.useOn(hit, InteractionHand.MAIN_HAND);
        return result instanceof InteractionResult.Success ? null : "base not placed (" + result + ")";
    }

    /** The stack the replaceability test builds its placement context from; registry-backed, hence lazy. */
    private ItemStack obsidianStack() {
        if (obsidianStack == null) {
            obsidianStack = new ItemStack(Items.OBSIDIAN);
        }
        return obsidianStack;
    }

    /** Crystals that still exist client-side but are gone, or about to be, server-side. */
    private static Predicate<Entity> dyingCrystals(CrystalLedger ledger) {
        return entity -> entity instanceof EndCrystal crystal
                && (ledger.isAttacked(crystal.getId()) || ledger.isExpectedRemoved(crystal.getId()));
    }

    /** The filter with one entity always pickable — a ray meant to hit it must be allowed to. */
    private static Predicate<Entity> ignoreExcept(EndCrystal entity, Predicate<Entity> ignore) {
        return other -> other != entity && ignore.test(other);
    }

    /** The filter extended by the crystal attacked this tick, unchanged when there is none. */
    private static Predicate<Entity> ignoreAlso(@Nullable EndCrystal entity, Predicate<Entity> ignore) {
        return entity == null ? ignore : other -> other == entity || ignore.test(other);
    }

    @Nullable
    private static String reason(@Nullable String attackReason, @Nullable String useReason) {
        List<String> parts = new ArrayList<>(2);
        if (attackReason != null) {
            parts.add("attack: " + attackReason);
        }
        if (useReason != null) {
            parts.add("use: " + useReason);
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    @Nullable
    private static LocalPlayer player() {
        return Minecraft.getInstance().player;
    }
}
