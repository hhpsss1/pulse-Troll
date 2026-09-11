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

package cc.aerial.client.features.impl.combat.crystalaura.state;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.Map;

/**
 * Pure bookkeeping of what we did (attacks, placements, chain removals we expect) and what the server
 * confirmed (spawns, removals, damage events). No world access, value types only, main thread only —
 * incoming packets are queued on the netty thread and drained into this ledger on the main thread.
 *
 * <p>Vanilla facts this mirrors:
 * <ul>
 *   <li>A crystal placed on a block spawns at exactly {@code (above.x + 0.5, above.y, above.z + 0.5)}
 *       per {@code EndCrystalItem.useOn}. The position travels as plain doubles, so a spawn confirms a
 *       pending placement by exact double comparison.</li>
 *   <li>Entity ids come from a global {@code AtomicInteger.incrementAndGet()}, which in 26.2 lives on
 *       {@code ServerLevel} rather than on {@code Entity}: a fresh entity's id is larger than every id
 *       seen so far, and ids are never reused while they are in use. {@link #predictNextId} therefore
 *       answers {@code maxSeenEntityId + 1}, but only while the last three observed spawns had
 *       consecutive ids — evidence that no unseen entity is consuming ids in between.
 *       <p>26.2 added a skip: {@code getNextEntityId} keeps incrementing while the chunk source
 *       already holds an entity with that id. A skip makes the real next id LARGER than the guess,
 *       never smaller, so a wrong prediction can only fail to match a spawn — the position match then
 *       confirms the placement instead, and the attack falls back to the spawned id. The
 *       consecutive-ids guard does not see a future skip, but it did not see an unseen entity in 26.1
 *       either; the prediction is a heuristic in both versions, and a miss costs one tick.</li>
 *   <li>{@code LivingEntity.hurtServer}: inside the i-frame window a hit deals only
 *       {@code damage - lastHurt} and only when {@code damage > lastHurt}; otherwise
 *       {@code lastHurt = damage; invulnerableTime = 20}. The client learns only THAT a hit happened —
 *       {@code ClientboundDamageEventPacket}, absent cause and direct ids decode to -1 — so the amount
 *       is never sent and {@link #lastHurtEstimate} is our own prediction that
 *       {@link #onDamageEvent} either confirms or discards.</li>
 *   <li>A hit crystal is removed BEFORE it explodes: {@code EndCrystal.hurtServer} calls
 *       {@code remove(KILLED)}, which synchronously sends the removal packet, and only then
 *       {@code level.explode} hurts the victims. The removal therefore reaches this ledger before the
 *       damage events that name the crystal as direct entity, so {@link #onRemoved} keeps such ids in
 *       a short grace memory that only {@link #onDamageEvent} consults — safe because ids are never
 *       reused.</li>
 * </ul>
 *
 * <p>Expiry, in client ticks, evaluated in {@link #onTickStart}: an entry stamped at tick {@code t}
 * with timeout {@code n} survives through tick {@code t + n} and is dropped at {@code t + n + 1}, so a
 * timeout of 0 expires on the next tick. Entries stamped after the current tick — the tick counter
 * went backwards without a reset — are dropped as stale. Own marks are stamped with the spawn tick and
 * expire after {@link #OWN_TICKS}; without that they would only ever shrink when a removal is
 * observed, so a crystal whose removal packet never arrives (chunk unload, dimension change, a dropped
 * removal event) would leak for the whole session.
 *
 * <p>Deliberate choices: a damage event that is not "us through one of our attacked or chain-removed
 * crystals" marks the victim's lastHurt as unknown, because the amount of a foreign hit is unknowable
 * client-side and both a too-high estimate (suppresses valid placements) and a too-low one
 * (overestimates damage) are wrong; the damage pipeline treats unknown as 0. A spawn that reuses an id
 * still marked own — its removal was never observed — clears that stale own mark, while attacked and
 * expected-removed marks survive a spawn so IdPredict attacks sent before the spawn packet keep their
 * state. Call {@link #reset} on world change, where entity ids restart.
 */
public final class CrystalLedger {
    /** Vanilla's {@code invulnerableTime = 20}. */
    private static final int LAST_HURT_TICKS = 20;

    /** How long a removed attacked or expected id still attributes damage events to us. */
    private static final int REMOVED_GRACE_TICKS = 20;

    /**
     * How long a confirmed own crystal stays own without an observed removal: 600 ticks, 30 seconds at
     * 20 TPS. The only consumer is the OnlyOwn skip of the break search, and a crystal that survived
     * half a minute is not a placement of ours worth protecting any more; without the bound the map
     * would only ever grow.
     */
    private static final int OWN_TICKS = 600;

    /** Number of consecutive spawn ids required before {@link #predictNextId} answers. */
    private static final int SPAWN_WINDOW = 3;

    private int tick;
    private int maxSeenEntityId = -1;

    /** Crystal id to the tick the attack was sent. */
    private final Int2IntOpenHashMap attacked = new Int2IntOpenHashMap();

    /** Crystal id to the tick it was marked as removed by the chain reaction of an attacked crystal. */
    private final Int2IntOpenHashMap expectedRemoved = new Int2IntOpenHashMap();

    /** Ids that were attacked or expected-removed when their removal arrived, to the removal tick. */
    private final Int2IntOpenHashMap removedOurs = new Int2IntOpenHashMap();

    /** Crystal id to the tick its spawn confirmed it as ours. */
    private final Int2IntOpenHashMap own = new Int2IntOpenHashMap();

    /** Unconfirmed placements keyed by {@code above.asLong()}. */
    private final Long2ObjectOpenHashMap<PendingPlacement> pending = new Long2ObjectOpenHashMap<>();

    /** Victim entity id to the lastHurt estimate and the tick it was last updated. */
    private final Int2ObjectOpenHashMap<LastHurt> lastHurt = new Int2ObjectOpenHashMap<>();

    /** The last three spawned ids, oldest first, and how many spawns have been seen. */
    private int spawnId0;
    private int spawnId1;
    private int spawnId2;
    private int spawnsSeen;

    /** Current client tick; 0 before the first {@link #onTickStart} and after a {@link #reset}. */
    public int tick() {
        return tick;
    }

    /** Highest entity id seen in any spawn, -1 when nothing has been seen. */
    public int maxSeenEntityId() {
        return maxSeenEntityId;
    }

    /**
     * Called once per tick before anything else. Expires attacked and expected-removed marks older
     * than {@code attackTimeoutTicks}, pending placements older than {@code placeTimeoutTicks},
     * lastHurt estimates older than 20 ticks, and own marks older than {@link #OWN_TICKS}.
     */
    public void onTickStart(int tick, int attackTimeoutTicks, int placeTimeoutTicks) {
        this.tick = tick;
        expireMarks(attacked, attackTimeoutTicks);
        expireMarks(expectedRemoved, attackTimeoutTicks);
        expireMarks(removedOurs, REMOVED_GRACE_TICKS);
        expireMarks(own, OWN_TICKS);
        expirePending(placeTimeoutTicks);
        expireLastHurt();
    }

    /**
     * Every {@code ClientboundAddEntityPacket}, of any entity type. Tracks {@link #maxSeenEntityId}
     * and the consecutive-id window of {@link #predictNextId}.
     *
     * <p>A crystal spawn confirms a pending placement when it appears at exactly
     * {@code (above.x + 0.5, above.y, above.z + 0.5)} or carries the placement's predicted id: the id
     * becomes own and the placement is no longer pending. Attacked and expected-removed marks for the
     * id are kept.
     */
    public void onSpawn(int id, boolean isCrystal, double x, double y, double z) {
        maxSeenEntityId = Math.max(maxSeenEntityId, id);
        pushSpawnId(id);
        PendingPlacement matched = isCrystal ? matchPending(id, x, y, z) : null;
        if (matched == null) {
            own.remove(id);
            return;
        }
        pending.remove(matched.above().asLong());
        own.put(id, tick);
    }

    /**
     * Entity removed client-side, by packet or by the world removal event. Clears the attacked,
     * expected-removed and own marks for the id, and drops the id as the predicted id of pending
     * placements — the position match still applies.
     */
    public void onRemoved(int id) {
        boolean wasOurs = attacked.containsKey(id) || expectedRemoved.containsKey(id);
        attacked.remove(id);
        expectedRemoved.remove(id);
        own.remove(id);
        if (wasOurs) {
            removedOurs.put(id, tick);
        }
        forgetPrediction(id);
    }

    /**
     * A {@code ClientboundDamageEventPacket}, with -1 for an absent cause or direct entity.
     *
     * <p>When the cause is us and the direct entity is a crystal we attacked, expect to be
     * chain-removed, or that was just removed while marked so, the hit is the one we predicted: the
     * stored estimate is kept and its tick refreshed. Any other hit — foreign explosion, melee, fall —
     * has an unknown amount, so the victim's estimate is dropped and treated as 0 downstream.
     */
    public void onDamageEvent(int entityId, int causeId, int directId, int selfId) {
        if (causeId != selfId || !isOurCrystalHit(directId)) {
            lastHurt.remove(entityId);
            return;
        }
        LastHurt entry = lastHurt.get(entityId);
        if (entry != null) {
            entry.tick = tick;
        }
    }

    /**
     * We sent an attack for {@code id}. {@code predictedLastHurt} maps a victim entity id to the value
     * vanilla will store in {@code lastHurt} — the post-shield damage — which is stored as the
     * estimate when positive.
     */
    public void markAttacked(int id, Map<Integer, Float> predictedLastHurt) {
        attacked.put(id, tick);
        for (Map.Entry<Integer, Float> entry : predictedLastHurt.entrySet()) {
            if (entry.getValue() > 0f) {
                storeLastHurt(entry.getKey(), entry.getValue());
            }
        }
    }

    /** A crystal that the chain reaction of an attacked one will remove; treated like attacked. */
    public void markExpectedRemoved(int id) {
        expectedRemoved.put(id, tick);
    }

    /** We sent a placement whose crystal will stand at {@code above}; replaces any older one there. */
    public void markPlaced(BlockPos above, @Nullable Integer predictedId) {
        pending.put(above.asLong(), new PendingPlacement(above, tick, predictedId));
    }

    public boolean isAttacked(int id) {
        return attacked.containsKey(id);
    }

    public boolean isExpectedRemoved(int id) {
        return expectedRemoved.containsKey(id);
    }

    public boolean isPending(BlockPos above) {
        return pending.containsKey(above.asLong());
    }

    /** Whether the id is a crystal we placed whose spawn is at most {@link #OWN_TICKS} ticks old. */
    public boolean isOwn(int id) {
        return own.containsKey(id);
    }

    /** Our lastHurt estimate, or null when unknown: never predicted, discarded, or expired. */
    @Nullable
    public Float lastHurtEstimate(int entityId) {
        LastHurt entry = lastHurt.get(entityId);
        return entry == null ? null : entry.damage;
    }

    /** {@code maxSeenEntityId + 1} when the last three observed spawns had consecutive ids, else null. */
    @Nullable
    public Integer predictNextId() {
        if (spawnsSeen < SPAWN_WINDOW) {
            return null;
        }
        boolean consecutive = spawnId1 == spawnId0 + 1 && spawnId2 == spawnId1 + 1;
        return consecutive ? maxSeenEntityId + 1 : null;
    }

    /** Newest unconfirmed placement, by send tick, or null. */
    @Nullable
    public PendingPlacement pendingPlacement() {
        PendingPlacement newest = null;
        for (PendingPlacement placement : pending.values()) {
            if (newest == null || placement.tick() > newest.tick()) {
                newest = placement;
            }
        }
        return newest;
    }

    /** Forgets everything, including the tick, the max seen id and the spawn window. */
    public void reset() {
        tick = 0;
        maxSeenEntityId = -1;
        attacked.clear();
        expectedRemoved.clear();
        removedOurs.clear();
        own.clear();
        pending.clear();
        lastHurt.clear();
        spawnId0 = 0;
        spawnId1 = 0;
        spawnId2 = 0;
        spawnsSeen = 0;
    }

    private boolean isOurCrystalHit(int directId) {
        return attacked.containsKey(directId)
                || expectedRemoved.containsKey(directId)
                || removedOurs.containsKey(directId);
    }

    private void storeLastHurt(int victimId, float damage) {
        LastHurt entry = lastHurt.get(victimId);
        if (entry == null) {
            lastHurt.put(victimId, new LastHurt(damage, tick));
        } else {
            entry.damage = damage;
            entry.tick = tick;
        }
    }

    private void pushSpawnId(int id) {
        spawnId0 = spawnId1;
        spawnId1 = spawnId2;
        spawnId2 = id;
        if (spawnsSeen < SPAWN_WINDOW) {
            spawnsSeen++;
        }
    }

    /** An exact position match wins over the predicted-id match. */
    @Nullable
    private PendingPlacement matchPending(int id, double x, double y, double z) {
        PendingPlacement byId = null;
        for (PendingPlacement placement : pending.values()) {
            BlockPos above = placement.above();
            if (x == above.getX() + 0.5 && y == (double) above.getY() && z == above.getZ() + 0.5) {
                return placement;
            }
            if (byId == null && placement.predictedId() != null && placement.predictedId() == id) {
                byId = placement;
            }
        }
        return byId;
    }

    private void forgetPrediction(int id) {
        for (Long2ObjectMap.Entry<PendingPlacement> entry : pending.long2ObjectEntrySet()) {
            PendingPlacement placement = entry.getValue();
            if (placement.predictedId() != null && placement.predictedId() == id) {
                entry.setValue(new PendingPlacement(placement.above(), placement.tick(), null));
            }
        }
    }

    /** An entry stamped in the future, or older than the timeout, is gone. */
    private boolean isExpired(int stampedTick, int timeoutTicks) {
        int age = tick - stampedTick;
        return age < 0 || age > timeoutTicks;
    }

    private void expireMarks(Int2IntOpenHashMap marks, int timeoutTicks) {
        Iterator<Int2IntMap.Entry> iterator = marks.int2IntEntrySet().iterator();
        while (iterator.hasNext()) {
            if (isExpired(iterator.next().getIntValue(), timeoutTicks)) {
                iterator.remove();
            }
        }
    }

    private void expirePending(int timeoutTicks) {
        Iterator<Long2ObjectMap.Entry<PendingPlacement>> iterator = pending.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            if (isExpired(iterator.next().getValue().tick(), timeoutTicks)) {
                iterator.remove();
            }
        }
    }

    private void expireLastHurt() {
        Iterator<Int2ObjectMap.Entry<LastHurt>> iterator = lastHurt.int2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            if (isExpired(iterator.next().getValue().tick, LAST_HURT_TICKS)) {
                iterator.remove();
            }
        }
    }

    /** Mutable so a confirmed hit refreshes the tick and a new prediction overwrites the damage in place. */
    private static final class LastHurt {
        private float damage;
        private int tick;

        private LastHurt(float damage, int tick) {
            this.damage = damage;
            this.tick = tick;
        }
    }
}
