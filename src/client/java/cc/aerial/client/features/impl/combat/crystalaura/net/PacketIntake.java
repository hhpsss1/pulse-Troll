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

package cc.aerial.client.features.impl.combat.crystalaura.net;

import cc.aerial.client.features.impl.combat.crystalaura.state.CrystalLedger;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.world.entity.EntityTypes;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Hands the incoming packets the aura consumes from the netty thread to the main thread.
 *
 * <p>{@code ReceivePacketEvent} fires at the HEAD of {@code Connection.channelRead0} on the netty
 * thread, before vanilla's handler hops to the main thread. The {@link CrystalLedger} is main-thread
 * only, so {@link #offer} parks the packets in a {@link ConcurrentLinkedQueue} and {@link #drainInto}
 * replays them, in wire order, into the ledger. Wire order matters: the ledger relies on a hit
 * crystal's removal arriving before the damage events that name it, and the queue is FIFO while
 * {@link #offer} is called synchronously from the single netty read path.
 *
 * <h2>Bundles</h2>
 * <p>Unlike LiquidBounce, whose own connection mixin cancels a {@code ClientboundBundlePacket} and
 * re-enters the read path once per sub-packet, Aerial dispatches the bundle itself and nothing else.
 * Entity spawns are ALWAYS bundled — {@code ServerEntity.sendPairingData} wraps
 * {@code getAddEntityPacket} in a {@code ClientboundBundlePacket} — so without the unwrapping below,
 * {@code ClientboundAddEntityPacket} would never reach this queue and the ledger would silently never
 * learn about a single crystal. The unwrap is recursive purely as a guard; a bundle inside a bundle is
 * not legal on the wire.
 *
 * <p>Packets consumed, everything else dropped without touching the queue:
 * <ul>
 *   <li>{@code ClientboundAddEntityPacket} — the position is decoded as raw doubles, so a placed
 *       crystal arrives at exactly {@code (above.x + 0.5, above.y, above.z + 0.5)} — feeding
 *       {@link CrystalLedger#onSpawn}.</li>
 *   <li>{@code ClientboundRemoveEntitiesPacket} feeding {@link CrystalLedger#onRemoved} per id.</li>
 *   <li>{@code ClientboundDamageEventPacket} feeding {@link CrystalLedger#onDamageEvent}; an absent
 *       entity is encoded as -1.</li>
 *   <li>{@code ClientboundExplodePacket}, sent to every player within 64 blocks of the centre, only
 *       counted in {@link #explosions()}.</li>
 * </ul>
 *
 * <p>Because the event precedes vanilla's own handling, a spawn drained at the start of a tick may
 * describe an entity {@code level.getEntity(id)} does not return yet. The ledger is id and position
 * bookkeeping and does not care; callers that resolve entities must.
 *
 * <p>Threading: {@link #offer} is called on the netty thread; {@link #drainInto},
 * {@link #explosions()} and {@link #reset} belong to the main thread.
 */
public final class PacketIntake {
    private final ConcurrentLinkedQueue<Packet<?>> queue = new ConcurrentLinkedQueue<>();

    private int explosions;

    /** Total explode packets drained since the last {@link #reset}; main thread, statistics only. */
    public int explosions() {
        return explosions;
    }

    /** NETTY THREAD. Enqueues only the packet types {@link #drainInto} consumes; touches nothing else. */
    public void offer(Packet<?> packet) {
        if (packet instanceof BundlePacket<?> bundle) {
            for (Packet<?> sub : bundle.subPackets()) {
                offer(sub);
            }
            return;
        }
        if (isConsumed(packet)) {
            queue.add(packet);
        }
    }

    /**
     * MAIN THREAD. Replays every queued packet, in arrival order, into the ledger. {@code selfId} is
     * the local player's entity id, compared against the damage event's cause. Call it once per tick
     * right after {@link CrystalLedger#onTickStart} so the ledger stamps the packets with the current
     * tick.
     */
    public void drainInto(CrystalLedger ledger, int selfId) {
        Packet<?> packet;
        while ((packet = queue.poll()) != null) {
            if (packet instanceof ClientboundAddEntityPacket spawn) {
                ledger.onSpawn(spawn.getId(), spawn.getType() == EntityTypes.END_CRYSTAL,
                        spawn.getX(), spawn.getY(), spawn.getZ());
            } else if (packet instanceof ClientboundRemoveEntitiesPacket removed) {
                removeAll(ledger, removed);
            } else if (packet instanceof ClientboundDamageEventPacket damage) {
                ledger.onDamageEvent(damage.entityId(), damage.sourceCauseId(), damage.sourceDirectId(), selfId);
            } else if (packet instanceof ClientboundExplodePacket) {
                explosions++;
            }
        }
    }

    /** Drops everything still queued and zeroes the explosion count; call on world change and on disable. */
    public void reset() {
        queue.clear();
        explosions = 0;
    }

    /** The id list is a fastutil {@code IntList}; indexed access avoids boxing an iterator per id. */
    private static void removeAll(CrystalLedger ledger, ClientboundRemoveEntitiesPacket packet) {
        IntList ids = packet.getEntityIds();
        for (int index = 0; index < ids.size(); index++) {
            ledger.onRemoved(ids.getInt(index));
        }
    }

    private static boolean isConsumed(Packet<?> packet) {
        return packet instanceof ClientboundAddEntityPacket
                || packet instanceof ClientboundRemoveEntitiesPacket
                || packet instanceof ClientboundDamageEventPacket
                || packet instanceof ClientboundExplodePacket;
    }
}
