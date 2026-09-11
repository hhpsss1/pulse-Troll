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

import cc.aerial.client.features.impl.combat.crystalaura.calc.BlockReduction;
import cc.aerial.client.features.impl.combat.crystalaura.calc.BlockingState;
import cc.aerial.client.features.impl.combat.crystalaura.calc.EnchantProtection;
import cc.aerial.client.features.impl.combat.crystalaura.calc.LevelShapeSource;
import cc.aerial.client.features.impl.combat.crystalaura.calc.PositionExtrapolation;
import cc.aerial.client.features.impl.combat.crystalaura.calc.VictimState;
import cc.aerial.client.features.impl.combat.crystalaura.config.ArmorAssumption;
import cc.aerial.client.features.impl.combat.crystalaura.state.CrystalLedger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The per-call constants and victim snapshot helpers of one world capture. Builds
 * {@link VictimState}s from live entities; every field is documented on {@link VictimState}, this
 * class documents where the value is read from.
 *
 * <h2>Skip conditions</h2>
 * <p>Vanilla refuses damage when the victim is invulnerable to the source, when
 * {@code abilities.invulnerable} is set without a bypass, or when it is dead or dying.
 * {@code player_explosion} is in none of the bypass tags, {@code abilities.invulnerable} is set by
 * the game-mode update for CREATIVE and SPECTATOR, and the creative-attacker exception reads the
 * CAUSING player's instabuild flag — which is us. Remote players expose no abilities, so their game
 * mode is read from the player info entry. Data-driven enchantment immunity is not mirrored: no
 * vanilla enchantment grants immunity to explosions.
 *
 * <h2>I-frame latency</h2>
 * <p>A target's invulnerableTime was set to 20 when its damage event reached us, one-way latency
 * after the server hit, and the explosion we trigger lands one-way latency after we act; when it
 * lands the server's counter is therefore about {@code reading - RTT}, hence the subtraction floored
 * at zero. Under-compensating keeps the target inside the window, i.e. errs towards less damage.
 *
 * <p>The local player's counter is passed uncompensated: the server sends the local player its own
 * damage events through the same path that keeps this field, so it is the client's own copy of its
 * own state. With an unknown lastHurt, treated as 0 by the pipeline, the window can never lower the
 * self estimate — self damage is never under-estimated whatever the counter says.
 */
final class VictimCapture {
    private final ClientLevel level;
    private final LocalPlayer player;
    private final CrystalLedger ledger;
    private final int placePredictTicks;
    private final int breakPredictTicks;
    private final int latencyTicks;
    private final int assumeResistance;
    private final ArmorAssumption assumeArmor;

    private final Holder<DamageType> damageType;
    private final Difficulty difficulty;

    /** Our own protection points, resolved once per capture; every victim may need them. */
    private Float selfProtection;

    VictimCapture(ClientLevel level, LocalPlayer player, CrystalLedger ledger,
                  int placePredictTicks, int breakPredictTicks, int latencyTicks,
                  int assumeResistance, ArmorAssumption assumeArmor, Difficulty difficulty) {
        this.level = level;
        this.player = player;
        this.ledger = ledger;
        this.placePredictTicks = placePredictTicks;
        this.breakPredictTicks = breakPredictTicks;
        this.latencyTicks = latencyTicks;
        this.assumeResistance = assumeResistance;
        this.assumeArmor = assumeArmor;
        this.damageType = EnchantProtection.playerExplosionType(level);
        this.difficulty = difficulty;
    }

    Difficulty difficulty() {
        return difficulty;
    }

    /** The local player at its current position, with an uncompensated i-frame counter. */
    VictimState self() {
        return victimOf(player, player.position(), selfSkip(), player.invulnerableTime, null);
    }

    /**
     * A target entry: the break snapshot at the break horizon, the place snapshot at the place
     * horizon — the same object when both horizons are equal.
     */
    TargetEntry target(LivingEntity entity) {
        Vec3 breakFeet = predictedFeet(entity, breakPredictTicks);
        VictimState breakState = victimOf(
                entity,
                breakFeet,
                skipOf(entity),
                Math.max(entity.invulnerableTime - latencyTicks, 0),
                ledger.lastHurtEstimate(entity.getId()));

        VictimState placeState;
        if (placePredictTicks == breakPredictTicks) {
            placeState = breakState;
        } else {
            Vec3 placeFeet = predictedFeet(entity, placePredictTicks);
            // The shield arc is measured from the SAME position the damage is: vanilla's blocking
            // check uses position() at explosion time, which is the position the distance sees too.
            // Keeping the break position here would score a victim predicted to run past the crystal
            // as fully shielded, or the reverse.
            BlockingState blocking = breakState.blocking() == null ? null : new BlockingState(
                    breakState.blocking().reductions(),
                    breakState.blocking().bypassed(),
                    breakState.blocking().viewVector(),
                    placeFeet);
            placeState = new VictimState(
                    placeFeet, boxAt(entity, placeFeet), breakState.isPlayer(), breakState.skip(),
                    breakState.armor(), breakState.toughness(), breakState.resistanceAmplifier(),
                    breakState.protectionPoints(), breakState.absorption(), breakState.health(),
                    blocking, breakState.invulnerableTime(), breakState.lastHurt(), breakState.difficulty());
        }

        return new TargetEntry(entity, placeState, breakState,
                new LevelShapeSource(level, CollisionContext.of(entity)));
    }

    /** Zero or fewer ticks means the current position; otherwise the best predictor for the entity. */
    private Vec3 predictedFeet(LivingEntity entity, int ticks) {
        if (ticks <= 0) {
            return entity.position();
        }
        return PositionExtrapolation.getBestForEntity(entity).getPositionInTicks(ticks);
    }

    /** The entity's own bounding box moved onto the given feet position. */
    private static AABB boxAt(LivingEntity entity, Vec3 feet) {
        return entity.getBoundingBox().move(feet.subtract(entity.position()));
    }

    /**
     * Assembles the snapshot.
     *
     * <p>Health is the synced value, i.e. exactly the {@code getHealth()} that {@code hurtServer} and
     * {@code setHealth} work on. A below-name scoreboard objective is deliberately NOT preferred: it
     * is an int in whatever scale the server picked — hearts, 0-100, 0-1000 — which is unusable as
     * the right-hand side of the lethality test. A 0-100 scale would disable the lethal branch
     * outright, and a hearts scale would claim kills that are not there.
     */
    private VictimState victimOf(LivingEntity entity, Vec3 feet, boolean skip,
                                 int invulnerableTime, @Nullable Float lastHurt) {
        return new VictimState(
                feet,
                boxAt(entity, feet),
                entity instanceof Player,
                skip,
                armorOf(entity),
                toughnessOf(entity),
                resistanceOf(entity),
                protectionOf(entity),
                entity.getAbsorptionAmount(),
                entity.getHealth(),
                blockingOf(entity, feet),
                invulnerableTime,
                lastHurt,
                difficulty);
    }

    /**
     * The Resistance amplifier, with the assumed fallback mixed in.
     *
     * <p>The client only ever receives the effect packet for itself and for a mount it rides, so the
     * reading is authoritative for the local player and always absent for everybody else. For any
     * non-local victim the assumed amplifier therefore stands in when nothing is visible, and the max
     * makes sure it can never lower an effect we actually see.
     */
    private int resistanceOf(LivingEntity entity) {
        MobEffectInstance effect = entity.getEffect(MobEffects.RESISTANCE);
        int actual = effect == null ? -1 : effect.getAmplifier();
        return entity == player ? actual : Math.max(actual, assumeResistance);
    }

    /** The armour value, substituted by ours when the full armour assumption hides the victim's. */
    private int armorOf(LivingEntity entity) {
        if (entity == player || assumeArmor != ArmorAssumption.FULL) {
            return entity.getArmorValue();
        }
        return player.getArmorValue();
    }

    /** The toughness attribute, substituted like the armour value. */
    private float toughnessOf(LivingEntity entity) {
        LivingEntity source = (entity == player || assumeArmor != ArmorAssumption.FULL) ? entity : player;
        return (float) source.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
    }

    /**
     * The protection points for the explosion damage type, SUBSTITUTED by ours for a remote victim
     * unless the assumption is off.
     *
     * <p>This is the channel that breaks on an obfuscating server: the points are computed from the
     * enchantment components of the victim's equipment stacks, so a server that rewrites them
     * controls this number entirely.
     *
     * <p>Substituted rather than maxed, which is the difference to the Resistance fallback. A hidden
     * effect is genuinely absent from the client's view, so assuming a minimum for it can only ever
     * correct an over-estimate. A rewritten enchantment list is not a missing reading but a false
     * one: the usual obfuscation leaves a decoy enchantment on the stack so the item still glints,
     * and that decoy may well be a Protection level nobody has. Treating it as a lower bound would
     * let a fake Protection IV survive a max and suppress the aura outright, so once this is on the
     * victim's own enchantments are not evidence and are dropped.
     */
    private float protectionOf(LivingEntity entity) {
        if (entity == player || assumeArmor == ArmorAssumption.OFF) {
            return EnchantProtection.protectionPoints(entity, damageType);
        }
        if (selfProtection == null) {
            selfProtection = EnchantProtection.protectionPoints(player, damageType);
        }
        return selfProtection;
    }

    /** The local player's own skip guards. */
    private boolean selfSkip() {
        return player.isCreative() || player.isSpectator() || player.getAbilities().invulnerable
                || player.isInvulnerable() || player.isDeadOrDying();
    }

    /**
     * Skip guards for a remote victim: dead or dying; the invulnerable flag unless we are the
     * creative attacker; and, for players, a player-info game mode of CREATIVE or SPECTATOR — the
     * client-side stand-in for {@code abilities.invulnerable}, which the game-mode update sets for
     * exactly those two modes. An unknown player, with no info entry, is not skipped.
     */
    private boolean skipOf(LivingEntity entity) {
        if (entity.isDeadOrDying() || (entity.isInvulnerable() && !player.getAbilities().instabuild)) {
            return true;
        }
        if (!(entity instanceof Player)) {
            return false;
        }
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(entity.getUUID());
        GameType gameMode = info == null ? null : info.getGameMode();
        return gameMode == GameType.CREATIVE || gameMode == GameType.SPECTATOR;
    }

    /**
     * Snapshot of the blocking inputs, null when the victim is not blocking — not using an item, no
     * blocks-attacks component, or the block delay has not elapsed.
     *
     * <p>{@code position} is the snapshot's feet, NOT the live entity position: vanilla evaluates the
     * shield arc and the blast distance against the same position at explosion time, so a predicted
     * state has to carry the predicted position in both places, or a victim predicted to run past the
     * crystal is scored with the arc it has right now.
     *
     * <p>The head yaw stays the client's interpolated current value — there is no way to predict
     * where the victim will be looking, so it is an unavoidable prediction error of the arc, bounded
     * by the blocking angle itself, 90 degrees for a vanilla shield.
     */
    @Nullable
    private BlockingState blockingOf(LivingEntity entity, Vec3 feet) {
        ItemStack stack = entity.getItemBlockingWith();
        if (stack == null) {
            return null;
        }
        BlocksAttacks blocksAttacks = stack.get(DataComponents.BLOCKS_ATTACKS);
        if (blocksAttacks == null) {
            return null;
        }
        List<BlockReduction> reductions = new ArrayList<>();
        for (BlocksAttacks.DamageReduction reduction : blocksAttacks.damageReductions()) {
            reductions.add(new BlockReduction(
                    reduction.horizontalBlockingAngle(),
                    reduction.type().map(set -> set.contains(damageType)).orElse(true),
                    reduction.base(),
                    reduction.factor()));
        }
        return new BlockingState(
                List.copyOf(reductions),
                blocksAttacks.bypassedBy().map(set -> set.contains(damageType)).orElse(false),
                entity.calculateViewVector(0.0f, entity.getYHeadRot()),
                feet);
    }
}
