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

import cc.aerial.client.features.impl.combat.crystalaura.config.GridMode;
import cc.aerial.client.features.impl.combat.crystalaura.config.RotationSettings;
import cc.aerial.client.rotation.SilentAim;
import net.minecraft.util.Mth;

/**
 * Speed-limited turn towards the target rotation; the single step rule of every smooth aim.
 *
 * <h2>Reset rule</h2>
 * <p>Resetting means the request has expired and the aim is handing the view back. On the approach the step
 * follows the acceleration envelope; on the return it moves at the configured return speed.
 *
 * <h2>Step</h2>
 * <p>The yaw delta is wrapped into +-180, the pitch delta is a PLAIN subtraction, and the distance is the
 * hypotenuse of the two. Wrapping the pitch as well would be wrong: pitch lives in [-90, 90], so a delta of
 * exactly +180 — current -90 to target +90, both reachable through the clamps below — wraps to -180. The step
 * would then push the pitch into the clamp it is already sitting on while the yaw step is zero: a hard stall.
 *
 * <p>When the distance is within one step the target itself is returned, which lands exactly. Otherwise the
 * rotation moves that many degrees along the straight line between the two. On the exact grid the step is
 * re-expressed as whole grid counts relative to the current rotation — never zero on the dominant axis — and
 * marked normalised, so the wire quantisation leaves it untouched.
 *
 * <h2>Why the step is strictly monotone</h2>
 * <p>The aim releases only once the rotation on the wire equals the camera EXACTLY; short of that it keeps
 * stepping. A step rule that could stall — a step rounding to zero counts — or fail to converge would keep the
 * override alive forever and the module could never hand the aim back. Here the distance shrinks every tick: the
 * step is at least one degree by the setting ranges, while what the wire quantisation adds afterwards is at most
 * half a grid step of rounding plus 0.4 of a step of dither per axis, which is well under a degree at full
 * sensitivity.
 *
 * <h2>Why the reset landing is marked normalised</h2>
 * <p>On the RESET path the final landing is marked normalised, which turns the wire quantisation into a no-op.
 * Without the flag the residual is rounded onto the mouse grid, and a residual below half a step rounds to zero:
 * the quantisation then hands back the very rotation the aim already holds, the difference never reaches zero and
 * the release never fires. The threshold snap cannot save it either once the reset threshold is below half a grid
 * step — the setting bottoms out at 0.1 degrees while half a step reaches about 0.31 degrees at full
 * sensitivity — so the module would hold the player's server-side rotation hostage forever.
 *
 * <p>The approach path is deliberately left unmarked: flagging it would suppress the dither that is the whole
 * point of the dithered grid mode.
 */
final class SmoothProcessor {
    private final RotationSettings settings;

    SmoothProcessor(RotationSettings settings) {
        this.settings = settings;
    }

    Rotation process(boolean resetting, Rotation currentRotation, Rotation targetRotation) {
        float deltaYaw = Mth.wrapDegrees(targetRotation.yaw() - currentRotation.yaw());
        // Plain subtraction: wrapping the pitch would turn an exact +-180 delta into a stall.
        float deltaPitch = targetRotation.pitch() - currentRotation.pitch();
        float dist = (float) Math.hypot(deltaYaw, deltaPitch);
        float speed = resetting ? settings.returnSpeed() : approachSpeed(dist);

        if (dist <= speed) {
            // The hand-back has to land on the camera EXACTLY, so the wire quantisation must not round it back
            // onto the grid. On the approach the dither stays welcome.
            return resetting
                    ? new Rotation(targetRotation.yaw(), targetRotation.pitch(), true)
                    : targetRotation;
        }

        float factor = speed / dist;
        float stepYaw = deltaYaw * factor;
        float stepPitch = deltaPitch * factor;

        if (settings.grid.getValue() == GridMode.EXACT) {
            return gridStep(currentRotation, stepYaw, stepPitch);
        }
        return new Rotation(
                currentRotation.yaw() + stepYaw,
                Mth.clamp(currentRotation.pitch() + stepPitch, -90f, 90f));
    }

    /**
     * The acceleration envelope, clamped into the speed band. The lower bound is capped at the upper one, so a
     * minimum above the maximum degrades to a constant speed instead of an empty range.
     */
    private float approachSpeed(float dist) {
        float maxSpeed = settings.maxSpeed();
        float minSpeed = Math.min(settings.minSpeed(), maxSpeed);
        return Mth.clamp(settings.accel() * dist, minSpeed, maxSpeed);
    }

    /**
     * Re-expresses the step as whole grid counts relative to the current rotation — that delta is what the wire
     * carries — and forces at least one count on the dominant axis, so the step can never round to a stand-still.
     * The pitch clamp is the same one the wire quantisation applies.
     */
    private static Rotation gridStep(Rotation current, float stepYaw, float stepPitch) {
        double gcd = SilentAim.gcd();
        long yawCounts = Math.round(stepYaw / gcd);
        long pitchCounts = Math.round(stepPitch / gcd);

        if (Math.abs(stepYaw) >= Math.abs(stepPitch)) {
            if (yawCounts == 0L) {
                yawCounts = stepYaw < 0f ? -1L : 1L;
            }
        } else if (pitchCounts == 0L) {
            pitchCounts = stepPitch < 0f ? -1L : 1L;
        }

        return new Rotation(
                (float) (current.yaw() + yawCounts * gcd),
                Mth.clamp((float) (current.pitch() + pitchCounts * gcd), -90f, 90f),
                true);
    }
}
