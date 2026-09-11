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

package cc.aerial.client.features.impl.combat.crystalaura.config;

import cc.aerial.client.features.impl.combat.AntiBotModule;
import cc.aerial.client.property.GroupProperty;
import cc.aerial.client.property.ModeProperty;
import cc.aerial.client.property.NumberProperty;
import cc.aerial.client.rotation.RotationUtility;
import cc.aerial.client.target.TargetFlags;
import cc.aerial.client.target.TargetProperty;
import cc.aerial.client.utility.PlayerUtility;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Who the aura is trying to kill: the search radius, the field of view, the hurt-time gate, the order
 * candidates are considered in, and the entity-kind filter.
 *
 * <p>Deliberately not Killaura's targeting. That one is wired to Killaura's own settings and to the
 * Piercing module, and it picks a single target; the damage pipeline here needs the whole sorted
 * list, because a placement is scored against every victim it can reach. The entity-kind rules are
 * shared with Killaura all the same, so a friend or a bot is one thing to both modules.
 *
 * <p>One deviation from LiquidBounce: its priority is a multi-select comparator chain whose default
 * is TYPE followed by the chosen order. Aerial's mode property is single-select, so the chain is
 * fixed as TYPE followed by whatever is selected — which reproduces the default exactly and is the
 * natural reading of a single-choice control.
 */
public final class TargetSettings {
    /** Maximum eye-to-box distance at which an entity is considered a target. */
    public final NumberProperty range = new NumberProperty("Range", 10, 1, 16, 0.5);

    /** Half-angle from the crosshair within which a candidate must lie. */
    public final NumberProperty fov = new NumberProperty("FOV", 180, 0, 180, 1);

    /** Skip a candidate whose hurt time is above this, i.e. one deep inside its i-frame window. */
    public final NumberProperty hurtTime = new NumberProperty("HurtTime", 10, 0, 10, 1);

    /** Order in which candidates are considered, applied after the type order. */
    public final ModeProperty<TargetPriority> priority = new ModeProperty<>("Priority", TargetPriority.HEALTH);

    /** Which kinds of entity may be targeted at all. */
    public final TargetProperty targets = new TargetProperty(true, false, false, false, false, false);

    private final GroupProperty group =
            new GroupProperty("Target", range, fov, hurtTime, priority, targets.get());

    public double range() {
        return range.getValue();
    }

    public GroupProperty get() {
        return group;
    }

    /**
     * Every living entity that passes the filters, sorted best first.
     *
     * <p>Empty when there is no world or player. The list is the whole candidate set, not a single
     * pick: a placement is scored against all of them.
     */
    public List<LivingEntity> collect() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null) {
            return List.of();
        }

        int flags = targets.getTargetFlags();
        float maxFov = fov.getValue().floatValue();
        int maxHurtTime = hurtTime.getValue().intValue();
        double maxDistanceSq = range() * range();

        java.util.Arrays.fill(rejected, 0);
        List<LivingEntity> candidates = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || living == player) {
                continue;
            }
            rejected[SCANNED]++;
            if (living.isRemoved() || living.isDeadOrDying()) {
                rejected[DEAD]++;
                continue;
            }
            if (living.hurtTime > maxHurtTime) {
                rejected[HURT]++;
                continue;
            }
            if (living.getBoundingBox().distanceToSqr(player.getEyePosition()) > maxDistanceSq) {
                rejected[FAR]++;
                continue;
            }
            if (!isMatchingFlags(player, living, flags)) {
                rejected[KIND]++;
                continue;
            }
            if (!RotationUtility.isEntityInFOV(living, maxFov)) {
                rejected[BEHIND]++;
                continue;
            }
            candidates.add(living);
        }

        candidates.sort(comparator());
        return candidates;
    }

    private static final int SCANNED = 0;
    private static final int DEAD = 1;
    private static final int HURT = 2;
    private static final int FAR = 3;
    private static final int KIND = 4;
    private static final int BEHIND = 5;

    /** Tally of the last {@link #collect()}, for the debug line only. */
    private final int[] rejected = new int[6];

    /**
     * Why the last scan found nothing.
     *
     * <p>An empty target list has six different causes and they are indistinguishable from outside, which
     * makes "the aura does nothing" impossible to diagnose without a rebuild. The counters cost one increment
     * per rejected entity and the string is built only when a debug line is actually emitted.
     */
    public String lastScanSummary() {
        return "scanned=" + rejected[SCANNED]
                + " dead=" + rejected[DEAD]
                + " hurt=" + rejected[HURT]
                + " far=" + rejected[FAR]
                + " kind=" + rejected[KIND]
                + " behind=" + rejected[BEHIND]
                + " flags=" + targets.getTargetFlags();
    }

    /** Type order first, then the selected order — LiquidBounce's default chain. */
    private Comparator<LivingEntity> comparator() {
        TargetPriority selected = priority.getValue();
        Comparator<LivingEntity> byType = TargetPriority.TYPE;
        return selected == TargetPriority.TYPE ? byType : byType.thenComparing(selected);
    }

    /**
     * The entity-kind rules, kept identical to Killaura's so a friend or a bot is one thing to both
     * modules: armour stands and villagers are never targets, a bot is never a target, a teammate is
     * one only when friendlies are enabled, and everything else falls under players, hostile or
     * passive.
     */
    private static boolean isMatchingFlags(LocalPlayer self, LivingEntity entity, int flags) {
        if (entity instanceof ArmorStand || entity instanceof Villager) {
            return false;
        }
        if (entity instanceof Player otherPlayer) {
            if (AntiBotModule.isBot(otherPlayer)) {
                return false;
            }
            if ((flags & TargetFlags.FRIENDLY) == 0 && PlayerUtility.areOnSameTeam(self, otherPlayer)) {
                return false;
            }
            return (flags & TargetFlags.PLAYERS) != 0;
        }
        if (entity instanceof Monster) {
            return (flags & TargetFlags.HOSTILE) != 0;
        }
        return (flags & TargetFlags.PASSIVE) != 0;
    }
}
