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

package cc.aerial.client.features.impl.combat.crystalaura.calc;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side victim damage pipeline of a crystal explosion, replicated stage by stage:
 * {@code Player.hurtServer} to {@code LivingEntity.hurtServer} to {@code actuallyHurt}, with
 * {@code getDamageAfterArmorAbsorb} and {@code getDamageAfterMagicAbsorb}.
 *
 * <p>The damage source is {@code damageSources().explosion(crystal, attacker)}, i.e.
 * {@code DamageTypes.PLAYER_EXPLOSION} when both entities are known, registered with
 * {@code DamageScaling.ALWAYS} so {@code scalesWithDifficulty()} is true. Tag membership of
 * {@code player_explosion}: in {@code is_explosion}; NOT in {@code bypasses_armor},
 * {@code bypasses_effects}, {@code bypasses_resistance}, {@code bypasses_enchantments},
 * {@code bypasses_invulnerability}, {@code damages_helmet}, {@code is_freezing};
 * {@code bypasses_cooldown} has no data file at all. Hence every armor, effect, enchantment and
 * cooldown branch below applies. {@code source.getWeaponItem()} is
 * {@code directEntity.getWeaponItem()}, null for a crystal, so the armor-effectiveness branch of
 * {@code CombatRules.getDamageAfterAbsorb} never runs.
 *
 * <p>Deliberate deviations: none in arithmetic. The side effects vanilla performs, namely storing
 * {@code lastHurt} and {@code invulnerableTime}, item durability, statistics and knockback, are not
 * modelled; the ledger owns the i-frame bookkeeping.
 */
public final class VictimPipeline {
    private VictimPipeline() {
    }

    /**
     * {@code Player.hurtServer} to {@code LivingEntity.hurtServer} to {@code Player.actuallyHurt},
     * exact order and arithmetic: skip, difficulty (players only), clamp below zero, shield
     * ({@code BlocksAttacks} arc), i-frames (when {@code respectIFrames} and
     * {@code invulnerableTime > 10}: gate or delta against {@code lastHurt}, defaulting to 0),
     * armor ({@code CombatRules}), resistance, protection cap, absorption.
     *
     * <p>{@code sourcePosition} is the crystal position: {@code DamageSource.getSourcePosition()}
     * falls back to {@code directEntity.position()} and the crystal is the direct entity.
     *
     * <p>Early exits: {@code victim.skip()} mirrors the {@code return false} guards of
     * {@code Player.hurtServer}. A post-scaling {@code damage == 0.0F} mirrors
     * {@code return damage == 0.0F ? false : super.hurtServer(...)}, which fires before
     * {@code lastHurt} and {@code invulnerableTime} are touched. For non-players a zero {@code raw}
     * can only come from the {@code dist > 1.0} skip of {@code ServerExplosion.hurtEntities} — the
     * formula otherwise yields at least 1 — where {@code hurtServer} is never called, so the zero
     * result is exact for them too.
     *
     * <p>Branches of {@code LivingEntity.hurtServer} that are dead for {@code player_explosion} and
     * therefore omitted: {@code IS_FREEZING}, {@code DAMAGES_HELMET} and the NaN / infinity guard,
     * whose inputs here are always finite.
     */
    public static DamageResult apply(VictimState victim, float raw, float exposure,
                                     Vec3 sourcePosition, boolean respectIFrames) {
        if (victim.skip()) {
            return DamageResult.none(exposure, raw);
        }
        float afterDifficulty = scaleByDifficulty(raw, victim);
        if (afterDifficulty == 0f) {
            return DamageResult.none(exposure, raw);
        }

        // LivingEntity: if (damage < 0.0F) damage = 0.0F;
        float clamped = afterDifficulty < 0f ? 0f : afterDifficulty;
        // LivingEntity: float damageBlocked = applyItemBlocking(...); damage -= damageBlocked;
        float afterBlocking = clamped - blockedDamage(victim.blocking(), clamped, sourcePosition);

        boolean inWindow = respectIFrames && victim.invulnerableTime() > 10;
        float lastHurt = victim.lastHurt() == null ? 0f : victim.lastHurt();
        if (inWindow && afterBlocking <= lastHurt) {
            return new DamageResult(exposure, raw, afterDifficulty, afterBlocking,
                    true, 0f, 0f, 0f, 0f, 0f);
        }
        float dealt = inWindow ? afterBlocking - lastHurt : afterBlocking;

        float afterArmor = damageAfterArmorAbsorb(victim, dealt);
        float afterMagic = damageAfterMagicAbsorb(victim, afterArmor);
        float healthLoss = Math.max(afterMagic - victim.absorption(), 0.0f);
        return new DamageResult(exposure, raw, afterDifficulty, afterBlocking,
                false, dealt, afterArmor, afterMagic, afterMagic - healthLoss, healthLoss);
    }

    /**
     * {@code Player.hurtServer}; players only, because {@code LivingEntity.hurtServer} has no
     * difficulty step.
     *
     * <pre>{@code
     * if (source.scalesWithDifficulty()) {
     *     if (difficulty == PEACEFUL) damage = 0.0F;
     *     if (difficulty == EASY) damage = Math.min(damage / 2.0F + 1.0F, damage);
     *     if (difficulty == HARD) damage = damage * 3.0F / 2.0F;
     * }
     * }</pre>
     */
    private static float scaleByDifficulty(float damage, VictimState victim) {
        if (!victim.isPlayer()) {
            return damage;
        }
        return switch (victim.difficulty()) {
            case PEACEFUL -> 0.0f;
            case EASY -> Math.min(damage / 2.0f + 1.0f, damage);
            case NORMAL -> damage;
            case HARD -> damage * 3.0f / 2.0f;
        };
    }

    /**
     * {@code LivingEntity.applyItemBlocking} plus {@code BlocksAttacks.resolveBlockedDamage}.
     *
     * <pre>{@code
     * if (damage <= 0.0F) return 0.0F;
     * ItemStack blockingWith = getItemBlockingWith();
     * if (blockingWith == null) return 0.0F;
     * BlocksAttacks blocksAttacks = blockingWith.get(DataComponents.BLOCKS_ATTACKS);
     * if (blocksAttacks != null
     *         && !blocksAttacks.bypassedBy().map(t -> t.contains(source.typeHolder())).orElse(false)) {
     *     if (source.getDirectEntity() instanceof AbstractArrow a && a.getPierceLevel() > 0) return 0.0F;
     *     Vec3 sourcePosition = source.getSourcePosition();
     *     double angle;
     *     if (sourcePosition != null) {
     *         Vec3 viewVector = calculateViewVector(0.0F, getYHeadRot());
     *         Vec3 vectorTo = sourcePosition.subtract(position());
     *         vectorTo = new Vec3(vectorTo.x, 0.0, vectorTo.z).normalize();
     *         angle = Math.acos(vectorTo.dot(viewVector));
     *     } else {
     *         angle = (float) Math.PI;
     *     }
     *     return blocksAttacks.resolveBlockedDamage(source, damage, angle);
     * }
     * return 0.0F;
     * }</pre>
     *
     * <p>{@code resolveBlockedDamage} sums {@code reduction.resolve(...)} over every reduction and
     * clamps the total into {@code [0, dealtDamage]}.
     *
     * <p>The crystal is never an {@code AbstractArrow}, and {@code getSourcePosition()} is never null
     * for it, so the {@code (float) Math.PI} fallback is not modelled. {@code Vec3.normalize()}
     * returns ZERO for a length below 1.0E-5F, so a source straight above or below gives
     * {@code acos(0.0)}.
     */
    private static float blockedDamage(BlockingState blocking, float damage, Vec3 sourcePosition) {
        if (blocking == null || damage <= 0.0f || blocking.bypassed()) {
            return 0.0f;
        }
        Vec3 vectorTo = sourcePosition.subtract(blocking.position());
        Vec3 flat = new Vec3(vectorTo.x, 0.0, vectorTo.z).normalize();
        double angle = Math.acos(flat.dot(blocking.viewVector()));
        float blocked = 0.0f;
        for (BlockReduction reduction : blocking.reductions()) {
            blocked += resolveReduction(reduction, damage, angle);
        }
        return Mth.clamp(blocked, 0.0f, damage);
    }

    /**
     * {@code BlocksAttacks.DamageReduction.resolve}.
     *
     * <pre>{@code
     * if (angle > (float) (Math.PI / 180.0) * this.horizontalBlockingAngle) return 0.0F;
     * return this.type.isPresent() && !this.type.get().contains(source.typeHolder())
     *         ? 0.0F
     *         : Mth.clamp(this.base + this.factor * dealtDamage, 0.0F, dealtDamage);
     * }</pre>
     *
     * <p>The right-hand side of the angle test is a {@code float * float} product that Java widens to
     * double for the comparison, hence the explicit cast. {@link BlockReduction#typeMatches()}
     * carries the pre-resolved type test.
     */
    private static float resolveReduction(BlockReduction reduction, float dealtDamage, double angle) {
        float maxAngle = (float) (Math.PI / 180.0) * reduction.horizontalBlockingAngle();
        if (angle > (double) maxAngle) {
            return 0.0f;
        }
        if (!reduction.typeMatches()) {
            return 0.0f;
        }
        return Mth.clamp(reduction.base() + reduction.factor() * dealtDamage, 0.0f, dealtDamage);
    }

    /**
     * {@code LivingEntity.getDamageAfterArmorAbsorb} into {@code CombatRules.getDamageAfterAbsorb}.
     *
     * <pre>{@code
     * float toughness = 2.0F + armorToughness / 4.0F;
     * float realArmor = Mth.clamp(totalArmor - damage / toughness, totalArmor * 0.2F, 20.0F);
     * float armorFraction = realArmor / 25.0F;
     * // weaponItem is null for a crystal, so modifiedArmorFraction == armorFraction
     * return damage * (1.0F - armorFraction);
     * }</pre>
     *
     * <p>The int armor value is widened to the {@code float totalArmor} parameter.
     */
    private static float damageAfterArmorAbsorb(VictimState victim, float damage) {
        float totalArmor = victim.armor();
        float toughness = 2.0f + victim.toughness() / 4.0f;
        float realArmor = Mth.clamp(totalArmor - damage / toughness, totalArmor * 0.2f, 20.0f);
        float armorFraction = realArmor / 25.0f;
        return damage * (1.0f - armorFraction);
    }

    /**
     * {@code LivingEntity.getDamageAfterMagicAbsorb}.
     *
     * <pre>{@code
     * if (source.is(BYPASSES_EFFECTS)) return damage;              // not player_explosion
     * if (hasEffect(RESISTANCE) && !source.is(BYPASSES_RESISTANCE)) {
     *     int absorbValue = (getEffect(RESISTANCE).getAmplifier() + 1) * 5;
     *     int absorb = 25 - absorbValue;
     *     damage = Math.max(damage * absorb / 25.0F, 0.0F);
     * }
     * if (damage <= 0.0F) return 0.0F;
     * if (source.is(BYPASSES_ENCHANTMENTS)) return damage;         // not player_explosion
     * float enchantmentArmor = EnchantmentHelper.getDamageProtection(serverLevel, this, source);
     * if (enchantmentArmor > 0.0F) damage = CombatRules.getDamageAfterMagicAbsorb(damage, enchantmentArmor);
     * return damage;
     * }</pre>
     *
     * <p>{@code CombatRules.getDamageAfterMagicAbsorb} is
     * {@code damage * (1.0F - Mth.clamp(totalMagicArmor, 0.0F, 20.0F) / 25.0F)}. A
     * {@code resistanceAmplifier} below zero means no Resistance effect, and
     * {@code protectionPoints} replaces the server-only {@code getDamageProtection}.
     */
    private static float damageAfterMagicAbsorb(VictimState victim, float afterArmor) {
        float damage = afterArmor;
        if (victim.resistanceAmplifier() >= 0) {
            int absorbValue = (victim.resistanceAmplifier() + 1) * 5;
            int absorb = 25 - absorbValue;
            float v = damage * absorb;
            damage = Math.max(v / 25.0f, 0.0f);
        }
        if (damage <= 0.0f) {
            return 0.0f;
        }
        if (victim.protectionPoints() > 0.0f) {
            float realArmor = Mth.clamp(victim.protectionPoints(), 0.0f, 20.0f);
            damage *= 1.0f - realArmor / 25.0f;
        }
        return damage;
    }
}
