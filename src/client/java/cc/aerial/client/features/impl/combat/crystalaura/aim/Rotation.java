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

package cc.aerial.client.features.impl.combat.crystalaura.aim;

import cc.aerial.client.rotation.SilentAim;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

/**
 * A yaw and pitch pair, plus a flag saying whether it has already been put on the mouse grid.
 *
 * <p>This is the aura's own rotation type rather than a bare {@link Vec2}, because two things travel with the
 * angles: whether {@link #normalize()} still has work to do, and the arithmetic that decides what actually goes on
 * the wire.
 */
public record Rotation(float yaw, float pitch, boolean normalized) {
    public static final Rotation ZERO = new Rotation(0f, 0f);

    public Rotation(float yaw, float pitch) {
        this(yaw, pitch, false);
    }

    /** The rotation that looks from one point at another. */
    public static Rotation lookingAt(Vec3 point, Vec3 from) {
        return fromRotationVec(point.subtract(from));
    }

    public static Rotation fromRotationVec(Vec3 lookVec) {
        return fromRotationVec(lookVec.x, lookVec.y, lookVec.z);
    }

    public static Rotation fromRotationVec(double diffX, double diffY, double diffZ) {
        return new Rotation(
                Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(diffZ, diffX)) - 90f),
                Mth.wrapDegrees((float) -Math.toDegrees(Math.atan2(diffY, Math.hypot(diffX, diffZ)))));
    }

    /** The unit vector this rotation looks along. */
    public Vec3 directionVector() {
        return Vec3.directionFromRotation(pitch, yaw);
    }

    public Vec2 toVec2() {
        return new Vec2(yaw, pitch);
    }

    /**
     * Quantises the rotation delta onto the mouse-sensitivity grid and then dithers it with sub-quantum noise.
     * Also clamps pitch to +-90.
     *
     * <p>Snapping the delta to EXACT multiples of the sensitivity step makes every rotation delta on the wire a
     * perfect integer multiple of one constant, and anticheats derive the sensitivity from the greatest common
     * divisor of consecutive deltas and flag the unnaturally exact grid. The dither keeps deltas near the grid,
     * which is what a real mouse produces, without the exact-multiple signature.
     *
     * <p>A zero delta stays exactly zero, so a settled aim sends no spurious rotation and produces no duplicate
     * look.
     *
     * <p>The delta is measured from the rotation the aim is already holding, falling back to the camera when it
     * holds none — the same origin the step measures from, so a step and its quantisation agree on where the
     * turn starts.
     */
    public Rotation normalize() {
        if (normalized) {
            return this;
        }
        double gcd = SilentAim.gcd();
        Rotation current = AuraRotation.stepOrigin();

        RotationDelta diff = current.rotationDeltaTo(this);
        double quantizedYaw = Math.round(diff.deltaYaw() / gcd) * gcd;
        double quantizedPitch = Math.round(diff.deltaPitch() / gcd) * gcd;

        float newYaw = current.yaw + (float) dither(quantizedYaw, gcd);
        float newPitch = current.pitch + (float) dither(quantizedPitch, gcd);
        return new Rotation(newYaw, Mth.clamp(newPitch, -90f, 90f), true);
    }

    /**
     * Adds triangular sub-quantum noise, 5 to 40 percent of one grid step, to a non-zero quantised delta. A zero
     * delta is kept exact so a still player stays still on the wire.
     */
    private static double dither(double quantizedDelta, double gcd) {
        if (quantizedDelta == 0.0) {
            return 0.0;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double amplitude = gcd * (0.05 + 0.35 * random.nextDouble());
        double noise = (random.nextDouble() - random.nextDouble()) * amplitude;
        return quantizedDelta + noise;
    }

    /** The angle between this rotation and another, in degrees, capped at 180. */
    public float angleTo(Rotation other) {
        return Math.min(rotationDeltaTo(other).length(), 180.0f);
    }

    /** What would have to be added to this rotation to arrive at the other one, wrapped into +-180. */
    public RotationDelta rotationDeltaTo(Rotation other) {
        return new RotationDelta(
                Mth.wrapDegrees(other.yaw - this.yaw),
                Mth.wrapDegrees(other.pitch - this.pitch));
    }

    /**
     * A rotation closer to the other one, moving at most the given number of degrees on each axis along the
     * straight line between them.
     */
    public Rotation towardsLinear(Rotation other, float horizontalFactor, float verticalFactor) {
        RotationDelta diff = rotationDeltaTo(other);
        float difference = diff.length();
        if (difference == 0f) {
            return this;
        }
        float straightLineYaw = Math.abs(diff.deltaYaw() / difference) * horizontalFactor;
        float straightLinePitch = Math.abs(diff.deltaPitch() / difference) * verticalFactor;
        return new Rotation(
                this.yaw + Mth.clamp(diff.deltaYaw(), -straightLineYaw, straightLineYaw),
                this.pitch + Mth.clamp(diff.deltaPitch(), -straightLinePitch, straightLinePitch));
    }

    /** Interpolates towards the other rotation by the given factor. */
    public Rotation interpolateTo(Rotation other, float factor) {
        return new Rotation(
                (float) Math.fma(factor, other.yaw - yaw, yaw),
                (float) Math.fma(factor, other.pitch - pitch, pitch));
    }

    public boolean approximatelyEquals(Rotation other, float tolerance) {
        return angleTo(other) <= tolerance;
    }
}
