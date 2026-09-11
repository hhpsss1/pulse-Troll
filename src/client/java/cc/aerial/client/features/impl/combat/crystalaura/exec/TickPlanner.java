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
import cc.aerial.client.features.impl.combat.crystalaura.aim.LookPoint;
import cc.aerial.client.features.impl.combat.crystalaura.aim.LookPoints;
import cc.aerial.client.features.impl.combat.crystalaura.aim.RayTests;
import cc.aerial.client.features.impl.combat.crystalaura.aim.Rotation;
import cc.aerial.client.features.impl.combat.crystalaura.base.BasePlan;
import cc.aerial.client.features.impl.combat.crystalaura.calc.ExplosionMath;
import cc.aerial.client.features.impl.combat.crystalaura.config.BasePlaceSettings;
import cc.aerial.client.features.impl.combat.crystalaura.config.BreakSettings;
import cc.aerial.client.features.impl.combat.crystalaura.config.PlaceSettings;
import cc.aerial.client.features.impl.combat.crystalaura.config.RotationSettings;
import cc.aerial.client.features.impl.combat.crystalaura.config.SwitchMode;
import cc.aerial.client.features.impl.combat.crystalaura.scan.BreakCandidate;
import cc.aerial.client.features.impl.combat.crystalaura.scan.PlaceCandidate;
import cc.aerial.client.features.impl.combat.crystalaura.state.CrystalLedger;
import cc.aerial.client.features.impl.combat.crystalaura.state.PendingPlacement;
import cc.aerial.client.features.impl.combat.crystalaura.world.WorldView;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.function.Predicate;

/**
 * Turns this tick's search results into a plan: which actions are offered to the executor for THIS tick, which
 * wait, and the ONE rotation to request. Pure decision logic over ray tests; no packets, no world mutation.
 *
 * <h2>The plan decides intent, the executor decides the hit</h2>
 * <p>The wire tests below choose the candidate and the look point; they no longer veto the action. A planned
 * action is marked as an attempt and the executor re-traces its ray against the outgoing rotation — the one THIS
 * tick's movement packet carries, final and quantised by the time execute runs. A rotation requested here
 * therefore serves the action in the SAME tick instead of the next one; when the exact ray misses, because the
 * dither pushed it off the point, the executor sends nothing and the next tick retries with the rotation by then
 * on the wire. No tolerance is widened anywhere to make an attempt land.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>At most one attack and one use per tick, attack first — the vanilla click order.</li>
 *   <li>The delay gates come from the hub; without same-tick, a tick with an attack has no use.</li>
 *   <li>The use is the crystal placement unless a base plan exists; a base without an obsidian slot falls back to
 *       the placement. With no switching, the needed slot must already be the one the server holds.</li>
 *   <li>The placement is a DEMOTION CHAIN, not a single candidate: the ranked candidates are walked past
 *       everything the own-blast guard refuses and everything no rotation reaches. Before that the tick simply
 *       did nothing in both cases and the next tick re-produced the same rejected block, which stalled the aura
 *       for as long as the rejection lasted.</li>
 *   <li>Id prediction: when the newest pending placement carries a predicted id, is fresh, no spawn matched it
 *       and the entity does not exist client-side, a phantom replaces the real attack — only when no real
 *       candidate exists and the id was not attacked already. Attacking a wrong id is a DISCONNECT, not a
 *       miss.</li>
 * </ul>
 *
 * <h2>Decision table</h2>
 * <ol>
 *   <li>The attack passes, or there is none, and the use passes, or there is none: both are offered and no look
 *       is requested. The aim is held, so the outgoing rotation equals the wire one and the executor's exact test
 *       reproduces the verdict taken here.</li>
 *   <li>The attack fails: it is offered anyway and the look is requested for it — the request lands in THIS
 *       tick's movement packet, so the executor's ray decides. A crystal no sampled ray can reach is dropped for
 *       this tick and the use is decided alone; it can never deadlock the placement. The use is deliberately NOT
 *       offered while the attack is being re-aimed: should the attack's ray miss while the use's hits, a crystal
 *       placed within the blast of one that is attacked a tick later is chain-removed WITHOUT exploding, i.e.
 *       wasted.</li>
 *   <li>The attack passes, the use fails: both are offered and the look is the use's point. This is what keeps a
 *       placement from costing the tick its re-aim takes. The price is that the tick is aimed at the use: when
 *       the turn leaves the crystal the executor drops the attack and rule 2 re-proposes it next tick. The trade
 *       exists only with same-tick on, which is the only way the two share a tick at all.</li>
 * </ol>
 *
 * <p>The all-or-nothing form of rule 3 — wait for one rotation serving both — would deadlock whenever no single
 * rotation can pick both the crystal and the block.
 */
public final class TickPlanner {
    /** Rays one look point search may trace. */
    private static final int BUDGET = 96;

    /**
     * How many placement look point searches one tick may spend: the primary candidate plus at most three
     * demotions. The demotion only runs when the wire misses AND the search found no reachable point, which is
     * rare; the bound exists so a pathological tick — a whole ranked list of blocks behind the same wall —
     * cannot turn one tick into the full ray budget times the finalist cap.
     */
    private static final int MAX_PLACE_LOOKS = 4;

    /**
     * How many ticks old the pending placement of a phantom may be: this tick or the previous one, the window in
     * which our own spawn packet can still be in flight.
     */
    private static final int PENDING_MAX_AGE = 1;

    private final PlaceSettings place;
    private final BreakSettings brk;
    private final RotationSettings rotation;
    private final BasePlaceSettings base;

    public TickPlanner(PlaceSettings place, BreakSettings brk, RotationSettings rotation, BasePlaceSettings base) {
        this.place = place;
        this.brk = brk;
        this.rotation = rotation;
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

    /** The block rays stop caring what stands between the eye and the target. */
    private boolean throughBlocks() {
        return place.throughBlocks.getValue();
    }

    /**
     * Decides this tick.
     *
     * <p>The candidate list is the ranked placement chain and is LAZY: its head has already been paid for by the
     * search, each further element costs one 45-ray self-damage evaluation, and this method pulls exactly as far
     * as it must — not at all when the tick cannot use a crystal anyway, one element in the common case, further
     * ones only while the own-blast guard keeps refusing. The same iterator is carried on into the look-point
     * demotion, so a tick can never pull the chain twice.
     */
    public TickPlan decide(WorldView view, CrystalLedger ledger,
                           @Nullable BreakCandidate breakBest,
                           Iterable<PlaceCandidate> placeCandidates,
                           @Nullable BasePlan basePlan,
                           Rotation wire, boolean canAttack, boolean canUse, boolean sameTick,
                           @Nullable CrystalSlot crystalSlot, @Nullable CrystalSlot obsidianSlot,
                           Predicate<Entity> rayIgnore) {
        BreakCandidate attack = canAttack ? breakBest : null;
        PhantomAttack phantom = canAttack && attack == null ? phantomAttack(ledger) : null;
        boolean hasAttack = attack != null || phantom != null;
        boolean useAllowed = canUse && (sameTick || !hasAttack);
        BasePlan plannedBase = useAllowed && usable(obsidianSlot) ? basePlan : null;

        Iterator<PlaceCandidate> places;
        if (useAllowed && usable(crystalSlot)) {
            EndCrystal detonating = attack == null ? null : attack.crystal();
            places = filtered(placeCandidates.iterator(), view, ledger, detonating, phantom);
        } else {
            places = java.util.Collections.emptyIterator();
        }
        PlaceCandidate plannedPlace = places.hasNext() ? places.next() : null;
        CrystalSlot slot = plannedBase != null ? obsidianSlot : (plannedPlace != null ? crystalSlot : null);
        Draft draft = new Draft(attack, phantom, plannedPlace, plannedBase, slot);
        return resolve(view, wire, draft, rayIgnore, crystalSlot, places);
    }

    /** The candidate chain with everything the own-blast guard refuses skipped, still lazy. */
    private Iterator<PlaceCandidate> filtered(Iterator<PlaceCandidate> source, WorldView view,
                                              CrystalLedger ledger, @Nullable EndCrystal detonating,
                                              @Nullable PhantomAttack phantom) {
        return new Iterator<>() {
            private PlaceCandidate next;

            @Override
            public boolean hasNext() {
                while (next == null && source.hasNext()) {
                    PlaceCandidate candidate = source.next();
                    if (!wastedByOwnBlast(view, ledger, candidate, detonating, phantom)) {
                        next = candidate;
                    }
                }
                return next != null;
            }

            @Override
            public PlaceCandidate next() {
                if (!hasNext()) {
                    throw new java.util.NoSuchElementException();
                }
                PlaceCandidate result = next;
                next = null;
                return result;
            }
        };
    }

    /**
     * The decision table. Recurses at most once, with the attack dropped when it has no reachable aim; the
     * candidate chain is handed on and never restarted.
     *
     * <h3>Why the base falls back to the direct placement</h3>
     * <p>A base wins the tick's single use by the gain rule, but it only pays off once it is actually clicked.
     * When its click face cannot be aimed at right now, keeping it would spend the tick's re-aim on a click that
     * has to wait while a crystal placement the current wire rotation ALREADY hits was ready — and with a base
     * that stays unaimable the crystal cycle would starve for good. So the base is dropped for this tick, the
     * placement takes the use, and the base is re-proposed next tick with its rotation already on the way.
     *
     * <p>That fallback keys on the wire, not on the outgoing rotation, and it has to: the plan runs before the
     * aim is stepped, so the only rotation it can compare two candidates with is the one already sent.
     */
    private TickPlan resolve(WorldView view, Rotation wire, Draft draft, Predicate<Entity> rayIgnore,
                             @Nullable CrystalSlot crystalSlot, Iterator<PlaceCandidate> places) {
        Vec3 eye = view.eye();
        if (draft.hasAttack() && !attackHits(eye, wire, draft, rayIgnore)) {
            LookPoint look = attackLook(eye, wire, draft, rayIgnore);
            if (look == null) {
                return resolve(view, wire, draft.withoutAttack(), rayIgnore, crystalSlot, places);
            }
            // Rule 2: the attack is offered and the executor decides it against the rotation this look
            // produces. The use waits — see the chain-removal note above.
            return plan(draft, true, false, look, null);
        }

        BlockHitResult useHit = useHit(eye, wire, draft, rayIgnore);
        if (useHit == null && draft.base() != null && draft.place() != null && crystalSlot != null) {
            Draft withoutBase = draft.withoutBase(crystalSlot);
            BlockHitResult placeHit = useHit(eye, wire, withoutBase, rayIgnore);
            if (placeHit != null) {
                // The base's aim is still worth starting now, so it stays the look point of the tick.
                LookPoint baseLook = useLook(eye, wire, draft, rayIgnore);
                return plan(withoutBase, withoutBase.hasAttack(), true, baseLook, placeHit);
            }
        }

        // Rules 1 and 3: both planned actions are offered; a use the wire does not reach yet gets the look
        // point, and the executor decides it against the rotation that look produces, in THIS tick.
        if (!draft.hasUse() || useHit != null) {
            return plan(draft, draft.hasAttack(), draft.hasUse(), null, useHit);
        }
        return aimUse(eye, wire, draft, rayIgnore, places);
    }

    /**
     * Rule 3 for a use the wire does not reach yet: the look point search, with the CRYSTAL PLACEMENT demoted to
     * the next ranked candidate whenever no rotation reaches the current one.
     *
     * <p>A null look means no sampled ray on the block is both reachable and robust — the block is behind a
     * wall, edge-on, or simply out of the rotation's way. That verdict does not change by itself: without a
     * demotion the tick plans a use it cannot aim at, sends nothing, and the next tick's search answers with the
     * very same block, which is a stall for as long as the geometry lasts. Demoting spends the tick on the
     * runner-up instead, and the wire test is redone for it first — the demoted block may already be hit, in
     * which case the tick needs no re-aim at all.
     *
     * <p>A base look is never demoted: there is one base plan, and the caller already falls back from an
     * unaimable base to the direct placement.
     */
    private TickPlan aimUse(Vec3 eye, Rotation wire, Draft draft, Predicate<Entity> rayIgnore,
                            Iterator<PlaceCandidate> places) {
        Draft current = draft;
        int searches = 1;
        while (true) {
            LookPoint look = useLook(eye, wire, current, rayIgnore);
            if (look != null || current.base() != null || searches >= MAX_PLACE_LOOKS) {
                return plan(current, current.hasAttack(), current.hasUse(), look, null);
            }
            if (!places.hasNext()) {
                return plan(current, current.hasAttack(), current.hasUse(), null, null);
            }
            current = current.withPlace(places.next());
            searches++;
            BlockHitResult hit = useHit(eye, wire, current, rayIgnore);
            if (hit != null) {
                return plan(current, current.hasAttack(), current.hasUse(), null, hit);
            }
        }
    }

    /**
     * True when a crystal placed at this candidate would be thrown away by one of ours that is still waiting to
     * be detonated.
     *
     * <p>An explosion removes every crystal within the blast WITHOUT detonating it: a crystal only explodes when
     * the damage source is not itself an explosion. So while one of our crystals is in flight or standing
     * unbroken, every further crystal placed inside its blast is simply lost — the aura would stack two or three
     * crystals per detonation and pay for all of them.
     *
     * <p>Not counted, because they are gone before the placement is handled — the attack packet precedes the use
     * packet inside one tick: the crystal detonating this tick, the phantom's pending placement, and anything
     * the ledger already marks attacked or chain-removed. Anything out of break range is not counted either, a
     * standing crystal and the pending placement alike, because we cannot detonate it, so waiting on it would
     * stall the aura instead of protecting it.
     */
    private boolean wastedByOwnBlast(WorldView view, CrystalLedger ledger, PlaceCandidate candidate,
                                     @Nullable EndCrystal detonating, @Nullable PhantomAttack phantom) {
        double reachSq = breakRange() * breakRange();
        PendingPlacement pending = ledger.pendingPlacement();
        if (pending != null && phantom == null) {
            BlockPos obsidian = pending.above().below();
            if (ExplosionMath.crystalBox(obsidian).distanceToSqr(view.eye()) < reachSq
                    && inBlast(ExplosionMath.crystalCenter(obsidian), candidate.center())) {
                return true;
            }
        }

        for (EndCrystal crystal : view.crystals()) {
            if (crystal == detonating) {
                continue;
            }
            int id = crystal.getId();
            if (ledger.isOwn(id) && !ledger.isAttacked(id) && !ledger.isExpectedRemoved(id)
                    && crystal.getBoundingBox().distanceToSqr(view.eye()) < reachSq
                    && inBlast(crystal.position(), candidate.center())) {
                return true;
            }
        }
        return false;
    }

    /** Both points inside one explosion radius, using the vanilla feet-distance cut-off. */
    private static boolean inBlast(Vec3 a, Vec3 b) {
        return ExplosionMath.distanceFraction(a, b) <= 1.0;
    }

    /**
     * The phantom for the newest pending placement, or null when disabled, nothing is pending, no id was
     * predicted, the placement is no longer FRESH, the id is already attacked, or the entity already exists
     * client-side — then it is a real candidate.
     *
     * <h3>Why freshness, and why it is the last guard against a kick</h3>
     * <p>A predicted id is a guess, and the only other check is that no entity holds it — trivially true for an
     * id no entity ever gets. A guess that lands on an item, an experience orb, the player itself or a
     * non-attackable arrow does not miss, it DISCONNECTS us: the server answers such a target with an
     * invalid-entity-attacked kick, after the reach test has already passed.
     *
     * <p>The window in which the guess is defensible is the one where OUR crystal's spawn packet is still in
     * flight, i.e. the tick right after the placement went out. Older pendings are exactly the ones whose id
     * most likely belongs to a foreign entity by now, and they keep drifting further away with every entity the
     * server spawns.
     */
    @Nullable
    private PhantomAttack phantomAttack(CrystalLedger ledger) {
        if (!brk.idPredict.getValue()) {
            return null;
        }
        PendingPlacement pending = ledger.pendingPlacement();
        if (pending == null || pending.predictedId() == null) {
            return null;
        }
        int id = pending.predictedId();
        int age = ledger.tick() - pending.tick();
        if (age < 0 || age > PENDING_MAX_AGE || ledger.isAttacked(id)) {
            return null;
        }
        if (Minecraft.getInstance().level == null || Minecraft.getInstance().level.getEntity(id) != null) {
            return null;
        }
        BlockPos obsidian = pending.above().below();
        return new PhantomAttack(id, ExplosionMath.crystalBox(obsidian), ExplosionMath.crystalCenter(obsidian));
    }

    /** A slot can be used: present and, with no switching, already held server-side. */
    private boolean usable(@Nullable CrystalSlot slot) {
        return slot != null && (place.switchMode.getValue() != SwitchMode.NONE || slot.isHeld());
    }

    private boolean attackHits(Vec3 eye, Rotation wire, Draft draft, Predicate<Entity> rayIgnore) {
        EndCrystal crystal = draft.attack() == null ? null : draft.attack().crystal();
        if (crystal != null) {
            return RayTests.hitsCrystal(eye, wire, crystal, breakRange(),
                    ignoreExcept(crystal, rayIgnore)) != null;
        }
        PhantomAttack phantom = draft.phantom();
        if (phantom == null) {
            return false;
        }
        return RayTests.hitsPhantomBox(eye, wire, phantom.box(), breakRange(), rayIgnore) != null;
    }

    /**
     * The wire hit of the planned use — base click or placement block — null when there is none or it misses.
     *
     * <p>A base with a null face is the replace-in-place form: vanilla ignores the hit direction once the
     * clicked block is replaced, so the face must NOT be constrained.
     */
    @Nullable
    private BlockHitResult useHit(Vec3 eye, Rotation wire, Draft draft, Predicate<Entity> rayIgnore) {
        Predicate<Entity> ignore = ignoreAlso(draft.attack() == null ? null : draft.attack().crystal(), rayIgnore);
        BasePlan basePlan = draft.base();
        if (basePlan != null) {
            BlockHitResult hit = RayTests.hitsBlock(eye, wire, basePlan.clickPos(), baseRange(),
                    ignore, throughBlocks());
            if (hit == null) {
                return null;
            }
            return basePlan.face() == null || hit.getDirection() == basePlan.face() ? hit : null;
        }
        PlaceCandidate placeCandidate = draft.place();
        if (placeCandidate == null) {
            return null;
        }
        return RayTests.hitsBlock(eye, wire, placeCandidate.pos(), placeRange(), ignore, throughBlocks());
    }

    /** Rule 2: a rotation that performs the attack, serving the placement as well when possible. */
    @Nullable
    private LookPoint attackLook(Vec3 eye, Rotation wire, Draft draft, Predicate<Entity> rayIgnore) {
        float slack = AuraRotation.slackDegrees(rotation);
        EndCrystal crystal = draft.attack() == null ? null : draft.attack().crystal();
        PlaceCandidate placeCandidate = draft.place();
        if (crystal != null && placeCandidate != null) {
            LookPoint serving = LookPoints.forPlacement(eye, wire, placeCandidate.pos(), placeRange(),
                    crystal, breakRange(), ignoreAlso(crystal, rayIgnore), slack, BUDGET,
                    throughBlocks(), null);
            if (serving != null && serving.servesAttack()) {
                return serving;
            }
        }
        if (crystal != null) {
            return LookPoints.forCrystal(eye, wire, crystal, breakRange(), rayIgnore, slack, BUDGET);
        }
        PhantomAttack phantom = draft.phantom();
        if (phantom == null) {
            return null;
        }
        return LookPoints.forPhantomBox(eye, wire, phantom.box(), breakRange(), rayIgnore, slack, BUDGET);
    }

    /**
     * Rule 3: a rotation for the deferred use. The attack, if any, runs this tick, so no crystal has to be
     * served, but the attacked crystal is ignored by the block ray — it is gone when the rotation lands.
     */
    @Nullable
    private LookPoint useLook(Vec3 eye, Rotation wire, Draft draft, Predicate<Entity> rayIgnore) {
        float slack = AuraRotation.slackDegrees(rotation);
        Predicate<Entity> ignore = ignoreAlso(draft.attack() == null ? null : draft.attack().crystal(), rayIgnore);
        BasePlan basePlan = draft.base();
        if (basePlan != null) {
            return baseLook(eye, wire, basePlan, ignore, slack);
        }
        PlaceCandidate placeCandidate = draft.place();
        if (placeCandidate == null) {
            return null;
        }
        return LookPoints.forPlacement(eye, wire, placeCandidate.pos(), placeRange(), null, breakRange(),
                ignore, slack, BUDGET, throughBlocks(), null);
    }

    /**
     * The base half of the use look, in whichever of vanilla's two placement forms the plan carries.
     *
     * <p>Relative form: only that face of the neighbour will do. Replace-in-place form: the obsidian goes into
     * the clicked cell whatever direction comes back, so ANY visible face serves — sampled on the outline
     * actually standing there, because a block that does not fill its cell would otherwise be aimed at through
     * empty air.
     */
    @Nullable
    private LookPoint baseLook(Vec3 eye, Rotation wire, BasePlan basePlan, Predicate<Entity> ignore, float slack) {
        if (basePlan.face() != null) {
            return LookPoints.forFace(eye, wire, basePlan.clickPos(), basePlan.face(), baseRange(),
                    ignore, slack, BUDGET, throughBlocks());
        }
        AABB box = RayTests.pickBox(basePlan.clickPos());
        if (box == null) {
            return null;
        }
        return LookPoints.forPlacement(eye, wire, basePlan.clickPos(), baseRange(), null, breakRange(),
                ignore, slack, BUDGET, throughBlocks(), box);
    }

    private static TickPlan plan(Draft draft, boolean attackAttempt, boolean useAttempt,
                                 @Nullable LookPoint look, @Nullable BlockHitResult placeHit) {
        BlockHitResult hit = placeHit != null ? placeHit : toBlockHit(look);
        InteractionHand hand = draft.slot() == null ? InteractionHand.MAIN_HAND : draft.slot().useHand();
        return new TickPlan(draft.attack(), draft.phantom(), draft.place(), draft.base(),
                attackAttempt, useAttempt, look, draft.slot(), hand, hit);
    }

    /** The block hit a block look point promises. */
    @Nullable
    private static BlockHitResult toBlockHit(@Nullable LookPoint look) {
        if (look == null || look.face() == null || look.blockPos() == null) {
            return null;
        }
        return new BlockHitResult(look.point(), look.face(), look.blockPos(), false);
    }

    /** The filter with one entity always pickable — a ray meant to hit it must be allowed to. */
    private static Predicate<Entity> ignoreExcept(EndCrystal entity, Predicate<Entity> ignore) {
        return other -> other != entity && ignore.test(other);
    }

    /** The filter extended by this tick's attack crystal, unchanged when there is none. */
    private static Predicate<Entity> ignoreAlso(@Nullable EndCrystal entity, Predicate<Entity> ignore) {
        return entity == null ? ignore : other -> other == entity || ignore.test(other);
    }

    /** The actions under consideration before the wire tests decide what the tick aims at. */
    private record Draft(@Nullable BreakCandidate attack, @Nullable PhantomAttack phantom,
                         @Nullable PlaceCandidate place, @Nullable BasePlan base,
                         @Nullable CrystalSlot slot) {

        boolean hasAttack() {
            return attack != null || phantom != null;
        }

        boolean hasUse() {
            return place != null || base != null;
        }

        Draft withoutAttack() {
            return new Draft(null, null, place, base, slot);
        }

        /** The same tick with the base dropped, so the direct placement it was shadowing can run instead. */
        Draft withoutBase(@Nullable CrystalSlot crystalSlot) {
            return new Draft(attack, phantom, place, null, crystalSlot);
        }

        /** The same tick with the placement demoted to the next ranked candidate. */
        Draft withPlace(PlaceCandidate next) {
            return new Draft(attack, phantom, next, base, slot);
        }
    }
}
