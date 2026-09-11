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

import net.minecraft.advancements.predicates.DamageSourcePredicate;
import net.minecraft.advancements.predicates.TagPredicate;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.enchantment.ConditionalEffect;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.effects.EnchantmentValueEffect;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.predicates.DamageSourceCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * Client-side replica of {@code EnchantmentHelper.getDamageProtection(ServerLevel, LivingEntity,
 * DamageSource)}: the enchantment protection points that {@code LivingEntity.getDamageAfterMagicAbsorb}
 * hands to {@code CombatRules.getDamageAfterMagicAbsorb}.
 *
 * <h2>Why vanilla cannot be asked on the client</h2>
 * <p>{@code getDamageAfterMagicAbsorb} consults the enchantments only on a server level and otherwise
 * silently reports zero protection. The {@code ServerLevel} is required because every effect is
 * filtered through a {@code LootContext} built by {@code Enchantment.damageContext}. A
 * {@code ClientLevel} is no {@code ServerLevel}, and the {@code DamageSource} of a crystal that does
 * not exist yet cannot be built, so this class re-evaluates the requirements directly against the
 * damage type holder.
 *
 * <h2>Vanilla, mirrored step by step</h2>
 * <p>{@code EnchantmentHelper.getDamageProtection} accumulates into a {@code MutableFloat} while
 * {@code runIterationOnEquipment} walks every equipment slot, skipping empty and unenchanted stacks
 * and invoking the visitor only when the enchantment matches the slot. This class calls that very
 * iteration, so the slot filtering is vanilla's own.
 *
 * <p>{@code Enchantment.modifyDamageProtection} applies the {@code DAMAGE_PROTECTION} effects in list
 * order, each guarded by {@code ConditionalEffect.matches}, folding the running value through
 * {@code EnchantmentValueEffect.process(enchantmentLevel, random, value)}.
 *
 * <h2>Requirement evaluation without a LootContext</h2>
 * <p>Vanilla data only uses {@code minecraft:damage_source_properties}: blast protection adds a linear
 * {@code base 2.0, per_level_above_first 2.0} gated on tags {@code is_explosion = true} and
 * {@code bypasses_invulnerability = false}; plain protection adds {@code base 1.0,
 * per_level_above_first 1.0} gated on {@code bypasses_invulnerability = false}. Projectile protection
 * and feather falling follow the same shape, and fire protection wraps it in {@code all_of}.
 *
 * <p>Deliberate deviations, the only ones:
 * <ul>
 *   <li>No {@code LootContext}: {@code source.typeHolder()} is the holder passed in and
 *       {@code source.isDirect()} the flag passed in. A crystal explosion has direct entity = crystal
 *       and causing entity = the attacking player, hence {@code isDirect = false}.</li>
 *   <li>{@code direct_entity} / {@code source_entity} predicates need a {@code ServerLevel} and cannot
 *       be evaluated here: the effect is skipped and counted in {@link #lastSkippedConditions()}.</li>
 *   <li>Every other {@code LootItemCondition} implementation ({@code all_of}, {@code any_of},
 *       {@code inverted}, {@code random_chance}, data-pack predicates) is skipped and counted as well;
 *       {@code CompositeLootItemCondition.terms} is protected, so even {@code all_of} cannot be
 *       unwrapped without an accessor. For explosion damage this changes nothing with vanilla data —
 *       fire protection never applies to explosions.</li>
 *   <li>The random source is the client entity's {@code getRandom()}; it only matters for random value
 *       effects ({@code remove_binomial}), which no vanilla protection enchantment uses.</li>
 * </ul>
 *
 * <p>Main-thread only, because of {@link #lastSkippedConditions()}.
 */
public final class EnchantProtection {
    private EnchantProtection() {
    }

    private static int lastSkippedConditions;

    /** Level-identity cache for the damage type holder; weak so a left world is not kept alive. */
    private static WeakReference<Level> cachedLevel;
    private static Holder<DamageType> cachedPlayerExplosion;

    /**
     * Number of requirements the last {@link #protectionPoints} call could not evaluate client-side;
     * their effects were skipped. For the debug output, main thread only.
     */
    public static int lastSkippedConditions() {
        return lastSkippedConditions;
    }

    /**
     * Protection points of the victim's equipment against a damage source of the given type: what
     * {@code EnchantmentHelper.getDamageProtection} returns on the server.
     *
     * <p>For crystal explosions pass {@link #playerExplosionType} and {@code isDirect = false} —
     * {@code DamageSources.explosion(entity, cause)} picks {@code DamageTypes.PLAYER_EXPLOSION} when
     * both the direct entity and the cause are known. The caller applies vanilla's cap:
     * {@code CombatRules.getDamageAfterMagicAbsorb} clamps the points into {@code [0, 20]}.
     *
     * <p>Updates {@link #lastSkippedConditions()}.
     */
    public static float protectionPoints(LivingEntity victim, Holder<DamageType> damageType, boolean isDirect) {
        // Boxed in arrays because the visitor is a lambda and cannot write locals.
        float[] total = {0.0f};
        int[] skipped = {0};
        RandomSource random = victim.getRandom();

        EnchantmentHelper.runIterationOnEquipment(victim, (enchantment, enchantmentLevel, item) -> {
            List<ConditionalEffect<EnchantmentValueEffect>> effects =
                    enchantment.value().getEffects(EnchantmentEffectComponents.DAMAGE_PROTECTION);
            for (ConditionalEffect<EnchantmentValueEffect> conditionalEffect : effects) {
                Boolean verdict = applies(conditionalEffect.requirements().orElse(null), damageType, isDirect);
                if (verdict == null) {
                    skipped[0]++;
                } else if (verdict) {
                    total[0] = conditionalEffect.effect().process(enchantmentLevel, random, total[0]);
                }
            }
        });

        lastSkippedConditions = skipped[0];
        return total[0];
    }

    /** Convenience overload for the crystal case, where the explosion is never direct. */
    public static float protectionPoints(LivingEntity victim, Holder<DamageType> damageType) {
        return protectionPoints(victim, damageType, false);
    }

    /**
     * The {@code Holder<DamageType>} of {@code DamageTypes.PLAYER_EXPLOSION}, resolved through the
     * level's synced registry and cached per level instance — the holder belongs to that level's
     * registry and must not outlive it.
     */
    public static Holder<DamageType> playerExplosionType(Level level) {
        Holder<DamageType> cached = cachedPlayerExplosion;
        if (cached != null && cachedLevel != null && cachedLevel.get() == level) {
            return cached;
        }

        Holder<DamageType> holder = level.registryAccess()
                .lookupOrThrow(Registries.DAMAGE_TYPE)
                .getOrThrow(DamageTypes.PLAYER_EXPLOSION);
        cachedLevel = new WeakReference<>(level);
        cachedPlayerExplosion = holder;
        return holder;
    }

    /**
     * {@code ConditionalEffect.matches} without a {@code LootContext}: TRUE when the requirement
     * holds, FALSE when it does not, null when it cannot be evaluated client-side.
     */
    @Nullable
    private static Boolean applies(@Nullable LootItemCondition condition,
                                   Holder<DamageType> damageType, boolean isDirect) {
        if (condition == null) {
            return Boolean.TRUE;
        }
        if (condition instanceof DamageSourceCondition damageSourceCondition) {
            // DamageSourceCondition.test: an absent predicate always passes.
            DamageSourcePredicate predicate = damageSourceCondition.predicate().orElse(null);
            return predicate == null ? Boolean.TRUE : matches(predicate, damageType, isDirect);
        }
        return null;
    }

    /**
     * {@code DamageSourcePredicate.matches(ServerLevel, Vec3, DamageSource)} with
     * {@code source.typeHolder()} replaced by the holder and {@code source.isDirect()} by the flag,
     * in vanilla's order: a failing tag decides FALSE before the entity predicates are looked at;
     * present entity predicates are not evaluable (null); otherwise
     * {@code !isDirect.isPresent() || isDirect.get() == source.isDirect()}.
     */
    @Nullable
    private static Boolean matches(DamageSourcePredicate predicate,
                                   Holder<DamageType> damageType, boolean isDirect) {
        for (TagPredicate<DamageType> tag : predicate.tags()) {
            if (!tag.matches(damageType)) {
                return Boolean.FALSE;
            }
        }
        if (predicate.directEntity().isPresent() || predicate.sourceEntity().isPresent()) {
            return null;
        }
        Boolean required = predicate.isDirect().orElse(null);
        return required == null ? Boolean.TRUE : required == isDirect;
    }
}
