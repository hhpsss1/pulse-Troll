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
import cc.aerial.client.features.impl.combat.crystalaura.config.MovementCorrectionMode;
import cc.aerial.client.features.impl.combat.crystalaura.config.RotationMode;
import cc.aerial.client.features.impl.combat.crystalaura.config.RotationSettings;
import cc.aerial.client.rotation.SilentAim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;

/**
 * The aura's aim: it holds the silent rotation, steps it, and puts it on the wire through
 * {@link SilentAim}.
 *
 * <p>LiquidBounce delegates all of this to a client-wide rotation manager. Aerial has no such thing —
 * {@code RotationMouseHandler} turns the real camera instead — so the state machine lives here, scoped to this
 * one module.
 *
 * <h2>Modes</h2>
 * <ul>
 *   <li>Snap: the requested rotation goes on the wire in the same tick, and the moment the request expires the
 *       override is released instantly. There is no return path.</li>
 *   <li>Smooth: one step per tick towards the request, and after it expires one step per tick back to the
 *       camera, until the wire equals the camera exactly.</li>
 * </ul>
 *
 * <h2>Grid</h2>
 * <p>Every step is quantised onto the mouse grid. The dithered mode rounds the delta and adds sub-quantum
 * noise; the exact mode pre-quantises the requested delta to whole grid counts and marks it normalised, which
 * turns the dither into a no-op — exact multiples on the wire. The exact grid is what an anticheat's aim
 * processor keys on, which is why the dithered mode is the default. {@link #slackDegrees} tells the look point
 * search how far the wire may end up from the requested aim per axis.
 *
 * <h2>Request semantics</h2>
 * <p>A request is alive for the configured hold count of ticks. It can be overwritten at any time by a newer
 * one. Callers must verify their rays against {@link #outgoing()} before acting rather than assume the request
 * landed — a higher-priority submitter can win the tick.
 *
 * <h2>hold</h2>
 * <p>Between two actions of a cycle there is nothing new to aim at, but if the request expired the aim would
 * start returning to the camera — instantly, in snap mode — the server rotation would flip back and forth every
 * cycle, and the next action would have to travel from the camera again. {@link #hold} re-requests the rotation
 * already on the wire, marked normalised so both the step and the quantisation are no-ops: the override stays
 * alive and the outgoing packet does not change.
 */
public final class AuraRotation {
    private AuraRotation() {
    }

    /**
     * Above Killaura, below Scaffold, so a crystal cycle is not steered away mid-action while a life-saving
     * placement still wins.
     */
    public static final int PRIORITY = 35;

    /** Rounding plus dither that the wire quantisation may add per axis, in grid steps. */
    private static final float DITHER_SLACK = 0.9f;

    /** Rounding only: the delta is pre-quantised and the dither is a no-op. */
    private static final float EXACT_SLACK = 0.5f;

    /** The silent rotation currently held, or null when the aim is released. */
    @Nullable
    private static Rotation current;

    /** The live request target, already grid-prepared, or null when nothing is requested. */
    @Nullable
    private static Rotation requested;

    private static int expiryTick = Integer.MIN_VALUE;

    /** Set when the return path has landed on the camera; the next update releases. */
    private static boolean pendingRelease;

    /**
     * Entity of the last request, reused by {@link #hold} so the step stays on the approach path.
     *
     * <p>Held weakly: this is client-lifetime state, and a strong field would keep the victim — and through it
     * its level — alive after the module was disabled or the world changed. {@link #reset} clears it; the weak
     * reference is the safety net for a missed reset.
     */
    @Nullable
    private static WeakReference<Entity> lastEntity;

    @Nullable
    private static SmoothProcessor smoothProcessor;
    @Nullable
    private static RotationSettings smoothSettings;

    /**
     * The origin every step and every quantisation measures from: the rotation we are already holding, or the
     * camera when the aim is released.
     */
    static Rotation stepOrigin() {
        return current != null ? current : cameraRotation();
    }

    /**
     * The rotation the server currently has for us, gathered from the movement packets that actually left.
     *
     * <p>Use this, not {@link #outgoing()}, for anything that reasons about what the server ALREADY has.
     */
    public static Rotation wire() {
        Vec2 wire = SilentAim.INSTANCE.wire();
        return new Rotation(wire.x, wire.y);
    }

    /**
     * The rotation THIS tick's movement packet will carry.
     *
     * <h3>Why it is already known at execute time</h3>
     * <p>The plan runs at the head of the client tick and submits here; the movement packet is built later in
     * the same tick, inside the entity tick. Execute sits between the two, at the tail of the keybind handling.
     * So at execute this value is final and post-quantisation: not a guess, it is what the movement packet will
     * carry a few lines later in the same tick.
     *
     * <h3>Why aiming an action with it is not more aggressive than vanilla</h3>
     * <p>The mouse is only applied in the render section, after the tick loop. Within one client tick the
     * rotation is therefore constant: the crosshair pick, the clicks inside the keybind handling and the
     * movement packet all read the same value. A vanilla click is aimed with the rotation the SAME tick's
     * movement packet carries, and that packet leaves after the click — so verifying against {@link #wire()}
     * instead would be strictly more conservative than vanilla and would cost one client tick per re-aim.
     *
     * <h3>Why an anticheat reads it the same way</h3>
     * <p>Placement, dig and attack checks are deferred to the flying packet and re-evaluated with the NEW
     * rotation; the same-tick rotation is the first raytrace candidate. Exactness is not optional there: a
     * missed post-flying raytrace can arm a path that CANCELS placements for the next several interactions
     * rather than merely flagging them. An action may therefore only be sent in the same tick when the ray
     * provably hits with this exact value, never with a widened tolerance. The fallback is simply to wait for
     * the next tick, where the wire has caught up.
     */
    public static Rotation outgoing() {
        Vec2 outgoing = SilentAim.INSTANCE.outgoing();
        return new Rotation(outgoing.x, outgoing.y);
    }

    /** Whether the aim is currently holding a silent rotation. */
    public static boolean isRotating() {
        return current != null;
    }

    /** Allowed deviation of a look point from the exact aim, in degrees. */
    public static float slackDegrees(RotationSettings settings) {
        float gcd = SilentAim.gcd();
        return (settings.grid.getValue() == GridMode.EXACT ? EXACT_SLACK : DITHER_SLACK) * gcd;
    }

    /**
     * Files a rotation for the configured hold count of ticks.
     *
     * <p>Pass the victim whenever there is one: a null entity is the reset signal, and the return path then
     * moves at the return speed instead of the acceleration envelope. On the exact grid the rotation is
     * quantised relative to the origin the step will measure from; an already normalised input passes through.
     */
    public static void request(Rotation rotation, @Nullable Entity entity, RotationSettings settings) {
        lastEntity = entity == null ? null : new WeakReference<>(entity);
        requested = settings.grid.getValue() == GridMode.EXACT ? quantiseExact(rotation) : rotation;
        expiryTick = currentTick() + settings.holdTicks.getValue().intValue();
        pendingRelease = false;
    }

    /**
     * Re-requests the rotation already on the wire with the entity of the last request; an entity that has
     * since been removed or collected is dropped, which only changes the speed of a zero-length step.
     */
    public static void hold(RotationSettings settings) {
        Entity entity = lastEntity == null ? null : lastEntity.get();
        if (entity != null && entity.isRemoved()) {
            entity = null;
        }
        Rotation held = wire();
        request(new Rotation(held.yaw(), held.pitch(), true), entity, settings);
    }

    /**
     * Advances the aim by one tick and puts the result on the wire. Call once per tick at the end of the plan,
     * after any {@link #request} or {@link #hold}.
     */
    public static void update(RotationSettings settings) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            reset();
            return;
        }
        if (pendingRelease) {
            release();
            return;
        }

        boolean live = requested != null && currentTick() <= expiryTick;
        if (!live && current == null) {
            requested = null;
            return;
        }

        Rotation camera = cameraRotation();
        Rotation raw;
        if (settings.mode.getValue() == RotationMode.SNAP) {
            if (!live) {
                // No return path: the override simply stops.
                release();
                return;
            }
            raw = requested;
        } else {
            Rotation from = stepOrigin();
            Rotation target = live ? requested : camera;
            raw = smoothProcessorFor(settings).process(!live, from, target);
        }

        Rotation next = raw.normalize();
        if (!live) {
            // Snap onto the camera once we are within the threshold, so the hand-back cannot stall on a
            // residual that the grid rounding would swallow.
            if (next.angleTo(camera) <= settings.resetThreshold()) {
                next = new Rotation(camera.yaw(), camera.pitch(), true);
            }
            if (next.angleTo(camera) <= 0f) {
                // Carry the exact camera rotation for one tick, then let go.
                pendingRelease = true;
            }
        }

        current = next;
        apply(player, next, settings);
    }

    /**
     * Puts the rotation where the chosen movement correction wants it.
     *
     * <ul>
     *   <li>Change-look turns the real camera, head included, so movement and view agree by construction.</li>
     *   <li>Everything else is silent: the rotation is stamped onto the movement packet only. Strict and silent
     *       additionally publish the yaw so the client's own movement fix strafes in the sent direction rather
     *       than the camera's; off leaves the movement alone, which feels best and is the most detectable.</li>
     * </ul>
     */
    private static void apply(LocalPlayer player, Rotation rotation, RotationSettings settings) {
        MovementCorrectionMode correction = settings.movementCorrection.getValue();
        if (correction == MovementCorrectionMode.CHANGE_LOOK) {
            player.setYRot(rotation.yaw());
            player.setXRot(rotation.pitch());
            player.yHeadRot = rotation.yaw();
            return;
        }
        SilentAim.Movement movement = switch (correction) {
            case STRICT -> SilentAim.Movement.STRICT;
            case SILENT -> SilentAim.Movement.REDIRECT;
            default -> SilentAim.Movement.NONE;
        };
        SilentAim.INSTANCE.submit(rotation.yaw(), rotation.pitch(), PRIORITY, CLAIM, movement);
    }

    /** Drops the silent rotation without touching what already went out. */
    private static void release() {
        current = null;
        requested = null;
        pendingRelease = false;
    }

    /**
     * Drops everything retained between module runs: the held rotation, the request, the cached victim and the
     * cached step rule. Call it from the module's own state reset, on disable and on world change.
     */
    public static void reset() {
        release();
        expiryTick = Integer.MIN_VALUE;
        lastEntity = null;
        smoothProcessor = null;
        smoothSettings = null;
    }

    private static SmoothProcessor smoothProcessorFor(RotationSettings settings) {
        if (smoothProcessor == null || smoothSettings != settings) {
            smoothProcessor = new SmoothProcessor(settings);
            smoothSettings = settings;
        }
        return smoothProcessor;
    }

    /**
     * Rounds the delta from the origin the step will measure from to whole grid counts and marks the result
     * normalised. Yaw is wrapped; the pitch clamp mirrors the wire quantisation. An already normalised rotation
     * is returned untouched.
     */
    private static Rotation quantiseExact(Rotation rotation) {
        if (rotation.normalized()) {
            return rotation;
        }
        Rotation from = stepOrigin();
        double gcd = SilentAim.gcd();
        long yawCounts = Math.round(Mth.wrapDegrees(rotation.yaw() - from.yaw()) / gcd);
        long pitchCounts = Math.round((rotation.pitch() - from.pitch()) / gcd);
        return new Rotation(
                (float) (from.yaw() + yawCounts * gcd),
                Mth.clamp((float) (from.pitch() + pitchCounts * gcd), -90f, 90f),
                true);
    }

    private static Rotation cameraRotation() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? Rotation.ZERO : new Rotation(player.getYRot(), player.getXRot());
    }

    private static int currentTick() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? Integer.MIN_VALUE : player.tickCount;
    }

    /** Owner token handed to the aim arbiter; identity only. */
    private static final Object CLAIM = new Object();
}
