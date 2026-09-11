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

package cc.aerial.client.features.impl.combat.crystalaura.world;

import cc.aerial.client.features.impl.combat.crystalaura.calc.LevelShapeSource;
import cc.aerial.client.features.impl.combat.crystalaura.calc.ShapeSource;
import cc.aerial.client.features.impl.combat.crystalaura.calc.VictimState;
import cc.aerial.client.features.impl.combat.crystalaura.config.ArmorAssumption;
import cc.aerial.client.features.impl.combat.crystalaura.state.CrystalLedger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Everything one crystal-aura tick looks at, captured on the main thread by {@link #capture}: the
 * local player as a victim, the tracked targets, every live crystal and the level difficulty. The
 * eye is the origin of every range test, and the tick is the client tick the view belongs to.
 *
 * <p>The victim snapshots are frozen for the tick. The block and entity queries deliberately read
 * the live client level instead, so a placement executed earlier in the same tick is observed by the
 * next query. Main thread only.
 *
 * <h2>What the client knows about remote victims</h2>
 * <ul>
 *   <li>Armour and toughness: both attributes are syncable, so the readings are the server's.</li>
 *   <li>Health: synced entity data.</li>
 *   <li>Absorption: players sync it; every other living entity keeps a plain field that is never
 *       sent, so a remote mob reports 0.</li>
 *   <li>Resistance: NOT knowable for a remote victim. The effect packet's only senders are the
 *       affected player's own connection, passengers of the entity, and the bulk re-send on join,
 *       respawn, dimension change and mounting. The entity tracker never sends it. So the reading is
 *       authoritative for the local player and for a mount we ride, and always null for everybody
 *       else — an over-estimate of 1.25x per level, up to full damage predicted against a victim who
 *       takes zero at Resistance V. The assumed-resistance setting fills that hole.</li>
 *   <li>Armour and protection: both are read off the victim's equipment, which vanilla does sync, so
 *       on a vanilla server both are exact. A server may strip the enchantment components from that
 *       packet, and then every opponent reads as unenchanted while their armour points still look
 *       right. Such a server usually leaves a DECOY enchantment on the stack so the item still
 *       glints, so the visible list is false rather than empty. A server that hides the equipment
 *       outright also zeroes the armour value. The armour-assumption setting fills both holes.</li>
 *   <li>Blocking: the using-item flag is synced and the client counts the use duration down every
 *       tick, so the blocking query works for remote entities, with the remote count starting one-way
 *       latency after the server's.</li>
 *   <li>I-frames: the damage event sets invulnerableTime to 20 client-side and the base tick
 *       decrements it. The amount is never sent, so lastHurt comes from the ledger estimate.</li>
 *   <li>Game mode: read from the player info entry, which is what the creative and spectator checks
 *       consult on the client anyway.</li>
 *   <li>The entity invulnerable flag is NBT only and never synced, so it is false for every remote
 *       entity. It is still consulted, for exactness.</li>
 * </ul>
 */
public final class WorldView {
    /** Footprint of the crystal obstruction box: x + 1.0 and z + 1.0. */
    private static final double COLUMN_WIDTH = 1.0;

    /** Height of the crystal obstruction box: y + 2.0. */
    private static final double COLUMN_HEIGHT = 2.0;

    private final int tick;
    private final Vec3 eye;
    private final VictimState self;
    private final ShapeSource selfSource;
    private final List<TargetEntry> targets;
    private final List<EndCrystal> crystals;
    private final Difficulty difficulty;
    private final ClientLevel level;

    /**
     * Direct construction. {@link #capture} is the normal entry point; this stays open so a test can
     * stand a view up from constructed victims without a running game.
     */
    public WorldView(int tick, Vec3 eye, VictimState self, ShapeSource selfSource,
                      List<TargetEntry> targets, List<EndCrystal> crystals,
                      Difficulty difficulty, ClientLevel level) {
        this.tick = tick;
        this.eye = eye;
        this.self = self;
        this.selfSource = selfSource;
        this.targets = targets;
        this.crystals = crystals;
        this.difficulty = difficulty;
        this.level = level;
    }

    public int tick() {
        return tick;
    }

    public Vec3 eye() {
        return eye;
    }

    public VictimState self() {
        return self;
    }

    public ShapeSource selfSource() {
        return selfSource;
    }

    public List<TargetEntry> targets() {
        return targets;
    }

    public List<EndCrystal> crystals() {
        return crystals;
    }

    public Difficulty difficulty() {
        return difficulty;
    }

    public ClientLevel level() {
        return level;
    }

    /** The live client level's block state. */
    public BlockState state(BlockPos pos) {
        return level.getBlockState(pos);
    }

    /**
     * The block part of the crystal item's placement rule: the clicked state is obsidian or bedrock,
     * and the block above it is air.
     */
    public boolean isPlaceable(BlockPos pos) {
        BlockState base = state(pos);
        return (base.is(Blocks.OBSIDIAN) || base.is(Blocks.BEDROCK)) && state(pos.above()).isAir();
    }

    /**
     * The entity part of the crystal item's placement rule: the 1 x 2 x 1 column above the base must
     * be clear.
     *
     * <p>Vanilla queries the entities in that box; the two-argument overload applies the
     * no-spectators selector, and the query also reports intersecting ender dragon parts. Vanilla
     * fails when the list is non-empty; here every listed entity must satisfy the predicate instead —
     * crystals we already attacked or expect to be chain-removed are still present client-side while
     * the server has removed them.
     */
    public boolean isColumnFree(BlockPos above, Predicate<Entity> ignore) {
        double x = above.getX();
        double y = above.getY();
        double z = above.getZ();
        AABB box = new AABB(x, y, z, x + COLUMN_WIDTH, y + COLUMN_HEIGHT, z + COLUMN_WIDTH);
        for (Entity entity : level.getEntities((Entity) null, box)) {
            if (!ignore.test(entity)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Captures the world for one client tick. Main thread, in game.
     *
     * <p>{@code assumeResistance} is an AMPLIFIER: -1 assumes no effect, 0 is Resistance I. It is
     * used only as a fallback for victims whose real Resistance the client cannot see, and can never
     * lower an effect that is actually visible. Callers translate a 1-based level setting by
     * subtracting one.
     */
    public static WorldView capture(int tick, List<LivingEntity> targets, CrystalLedger ledger,
                                    int placePredictTicks, int breakPredictTicks, int latencyTicks,
                                    int assumeResistance, ArmorAssumption assumeArmor,
                                    Difficulty difficulty) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null) {
            throw new IllegalStateException("WorldView.capture requires a level and a player");
        }

        VictimCapture victims = new VictimCapture(
                level, player, ledger, placePredictTicks, breakPredictTicks,
                latencyTicks, assumeResistance, assumeArmor, difficulty);

        List<EndCrystal> crystals = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof EndCrystal crystal && crystal.isAlive()) {
                crystals.add(crystal);
            }
        }

        List<TargetEntry> entries = new ArrayList<>(targets.size());
        for (LivingEntity target : targets) {
            entries.add(victims.target(target));
        }

        return new WorldView(
                tick,
                player.getEyePosition(),
                victims.self(),
                new LevelShapeSource(level, CollisionContext.of(player)),
                List.copyOf(entries),
                List.copyOf(crystals),
                victims.difficulty(),
                level);
    }
}
