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

import cc.aerial.client.rotation.RotationUtility;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

import java.util.Comparator;

/** Order in which candidate victims are considered. Lower compares first. */
public enum TargetPriority implements Comparator<LivingEntity> {
    /** Players first, then hostiles, then mobs angry at us. */
    TYPE("Type") {
        @Override
        public int compare(LivingEntity a, LivingEntity b) {
            return Integer.compare(weight(a), weight(b));
        }

        private int weight(LivingEntity entity) {
            if (entity instanceof Player) {
                return 0;
            }
            if (entity instanceof Enemy) {
                return 1;
            }
            LocalPlayer self = Minecraft.getInstance().player;
            if (entity instanceof NeutralMob neutral && self != null
                    && self.getUUID().equals(neutral.getPersistentAngerTarget())) {
                return 2;
            }
            return Integer.MAX_VALUE;
        }
    },

    /** Lowest health first. */
    HEALTH("Health") {
        @Override
        public int compare(LivingEntity a, LivingEntity b) {
            return Float.compare(actualHealth(a), actualHealth(b));
        }
    },

    /** Closest to us first. */
    DISTANCE("Distance") {
        @Override
        public int compare(LivingEntity a, LivingEntity b) {
            LocalPlayer self = Minecraft.getInstance().player;
            if (self == null) {
                return 0;
            }
            return Double.compare(boxedDistanceSqr(a, self), boxedDistanceSqr(b, self));
        }
    },

    /** Closest to the crosshair first. */
    DIRECTION("Direction") {
        @Override
        public int compare(LivingEntity a, LivingEntity b) {
            return Double.compare(crosshairAngle(a), crosshairAngle(b));
        }
    },

    /** Lowest hurt time first, i.e. the one furthest out of its invulnerability window. */
    HURT_TIME("HurtTime") {
        @Override
        public int compare(LivingEntity a, LivingEntity b) {
            return Integer.compare(a.hurtTime, b.hurtTime);
        }
    },

    /** Oldest entity first. */
    AGE("Age") {
        @Override
        public int compare(LivingEntity a, LivingEntity b) {
            return Integer.compare(b.tickCount, a.tickCount);
        }
    };

    private final String label;

    TargetPriority(String label) {
        this.label = label;
    }

    /**
     * The synced health, matching what LiquidBounce orders by.
     *
     * <p>LiquidBounce prefers a below-name scoreboard objective when the server publishes one; Aerial
     * has no such helper and the objective's scale is server-defined anyway, so the synced value is
     * used directly. Absorption is deliberately NOT added: this orders candidates, and the damage
     * pipeline accounts for absorption separately.
     */
    static float actualHealth(LivingEntity entity) {
        return entity.getHealth();
    }

    /** Squared distance between the two bounding boxes rather than between the two origins. */
    static double boxedDistanceSqr(LivingEntity entity, LocalPlayer self) {
        return entity.getBoundingBox().distanceToSqr(self.getEyePosition());
    }

    /** Angle between the crosshair and the entity's nearest point, in degrees. */
    static double crosshairAngle(LivingEntity entity) {
        LocalPlayer self = Minecraft.getInstance().player;
        if (self == null) {
            return Double.MAX_VALUE;
        }
        return RotationUtility.getRotationDifference(
                RotationUtility.getRotation(),
                RotationUtility.getRotationFromPosition(entity.getBoundingBox().getCenter()));
    }

    @Override
    public String toString() {
        return label;
    }
}
