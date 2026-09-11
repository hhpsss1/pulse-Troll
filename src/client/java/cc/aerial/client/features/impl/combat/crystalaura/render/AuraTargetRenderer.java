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

package cc.aerial.client.features.impl.combat.crystalaura.render;

import cc.aerial.client.event.impl.render.Render3DEvent;
import cc.aerial.client.features.impl.combat.crystalaura.calc.BlockReduction;
import cc.aerial.client.features.impl.combat.crystalaura.calc.BlockingState;
import cc.aerial.client.features.impl.combat.crystalaura.calc.ExplosionMath;
import cc.aerial.client.features.impl.combat.crystalaura.calc.VictimPipeline;
import cc.aerial.client.features.impl.combat.crystalaura.calc.VictimState;
import cc.aerial.client.features.impl.combat.crystalaura.config.RenderSettings;
import cc.aerial.client.render.Render3DUtility;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The ground mark of the detonation overlay: everything this class draws is anchored at the TARGET's feet and
 * encodes state only a crystal aura knows.
 *
 * <ul>
 *   <li><b>Blast ring</b>: a horizontal gradient band whose radius is the KILL-ZONE radius — the largest
 *   distance at which a crystal at full exposure would still deal at least the configured minimum damage to
 *   this victim ({@link #killZoneRadius}). Its colour walks from {@link CrystalColors#RING_COLD} to
 *   {@link CrystalColors#RING_HOT} with {@code bestDamage / (health + absorption)} ({@link #heatRatio}).</li>
 *   <li><b>Kill segments</b>: the band is cut into {@code ceil((health + absorption) / bestDamage)} arcs, one
 *   per crystal still needed for the kill; they rotate slowly so the mark reads as alive. A single remaining
 *   crystal pulses the whole ring instead.</li>
 *   <li><b>Shield wedge</b>: while the victim blocks and the crystal damage type is not bypassing the shield,
 *   the sector inside the ring in which the block would eat the hit is filled.</li>
 * </ul>
 *
 * <h2>Animation</h2>
 *
 * <p>Nothing pops. {@link #update} is called once per frame with the current snapshot and only pushes TARGETS
 * into the tweens; {@link #draw} reads what the tweens currently show. That split is what lets the ring keep
 * fading after the target is gone (the module hands us {@code null} and we still have a residual, see
 * {@link #hasResidual}), lets the radius spring instead of jumping when the victim's armour changes, and lets a
 * changed segment count cross-fade between the old and the new arc layout instead of re-cutting the band in one
 * frame.
 *
 * <p>Pure rendering: nothing here touches the ledger, the plan or any packet path, and every input may be null.
 */
public final class AuraTargetRenderer {

    private AuraTargetRenderer() {
    }

    /** Bisection steps of the kill zone; 16 halvings of 12 blocks resolve the radius to well under a millimetre. */
    private static final int BISECTION_STEPS = 16;

    /** Radial thickness of the ring band, in blocks. */
    private static final float BAND = 0.35f;

    /** Upper bound of the segment count — beyond a dozen crystals the arcs stop being countable. */
    private static final int MAX_SEGMENTS = 12;

    private static final double DEG_TO_RAD = Math.PI / 180.0;
    private static final double TWO_PI = 2.0 * Math.PI;

    /** Gap between two kill segments, and its cap as a fraction of one segment's own span. */
    private static final double GAP_RAD = 5.0 * DEG_TO_RAD;
    private static final double MAX_GAP_FRACTION = 0.25;

    /**
     * Rotation of the segment pattern: 8 degrees per second at a cold target, up to
     * {@code 1 + SPIN_HEAT_GAIN} times that when one crystal would empty the victim. The step cap limits what a
     * stalled frame may contribute, so a loading pause does not whip the ring around.
     */
    private static final double SPIN_RAD_PER_SECOND = 8.0 * DEG_TO_RAD;
    private static final double SPIN_HEAT_GAIN = 1.2;
    private static final long MAX_SPIN_STEP_MS = 100L;

    /** Single-segment pulse: {@code alpha * (0.65 + 0.35 * pulse)} over a one-second period. */
    private static final float PULSE_BASE = 0.65f;
    private static final float PULSE_AMPLITUDE = 0.35f;
    private static final long PULSE_PERIOD_MS = 1000L;

    /** Placement of the shield wedge relative to the ring's inner edge, in blocks. */
    private static final float SHIELD_GAP = 0.08f;
    private static final float SHIELD_BAND = 0.55f;
    private static final int SHIELD_INNER_DIVISOR = 3;

    /** Shield-wedge breathing: alpha walks {@code 0.7..1.0} over 1.4 seconds. */
    private static final long WEDGE_BREATHE_MS = 1400L;
    private static final float WEDGE_BREATHE_BASE = 0.7f;
    private static final float WEDGE_BREATHE_AMP = 0.3f;

    /** Tween lengths, before the shared animation-speed divisor. */
    private static final long RADIUS_MS = 260L;
    private static final long RING_FADE_MS = 180L;
    private static final long SEGMENT_MS = 220L;
    private static final long CONSUME_FLASH_MS = 260L;
    private static final long WEDGE_FADE_MS = 200L;
    private static final long COLOR_MS = 220L;

    /** How far the consume flash pulls the band towards white, and how much alpha it adds on top. */
    private static final float FLASH_MIX = 0.85f;
    private static final float FLASH_ALPHA_GAIN = 0.6f;

    private static final double MILLIS_PER_SECOND = 1000.0;

    /** Ring radius, springing towards the current kill zone; only ever retargeted while the ring is alive. */
    private static final AuraAnim RADIUS = new AuraAnim(RADIUS_MS, 0f, AuraEase.SPRING);

    /** Overall ring opacity: 1 while a target with a kill zone exists, 0 otherwise. Stops it blinking. */
    private static final AuraAnim RING_ALPHA = new AuraAnim(RING_FADE_MS, 0f, AuraEase.OUT_CUBIC);

    /** Cross-fade between the previous and the current arc layout; 1 = only the current layout is drawn. */
    private static final AuraAnim SEGMENT_FADE = new AuraAnim(SEGMENT_MS, 1f, AuraEase.IN_OUT_CUBIC);

    /** One-shot brightening when the segment count DROPPED, i.e. a crystal of the kill was consumed. */
    private static final AuraAnim CONSUME_FLASH = new AuraAnim(CONSUME_FLASH_MS, 0f, AuraEase.OUT_CUBIC);

    /** Shield wedge opacity, on top of its own breathing sine. */
    private static final AuraAnim WEDGE_ALPHA = new AuraAnim(WEDGE_FADE_MS, 0f, AuraEase.OUT_CUBIC);

    /** Cold to hot band colour, cross-fading instead of stepping with the heat. */
    private static final AuraAnimColor EDGE_COLOR =
            new AuraAnimColor(COLOR_MS, CrystalColors.TRANSPARENT, AuraEase.OUT_CUBIC);

    /** Arc layout currently drawn and the one being faded out; {@code 0} = one closed band. */
    private static int segments;
    private static int previousSegments;

    /** Accumulated ring rotation and the clock it was last advanced at, so the heat may change its RATE. */
    private static double spin;
    private static long spinAt;

    /** The victim we anchor to, and its last known feet for the fade-out after it is gone. */
    @Nullable
    private static LivingEntity target;
    @Nullable
    private static Vec3 lastFeet;

    /** Facing and half-angle of the blocked sector, captured in {@link #update} and drawn in the wedge. */
    private static double wedgeFacing;
    private static double wedgeHalf;

    /**
     * Largest distance {@code d} at which a crystal centred {@code d} blocks from the victim and seeing it
     * fully would still deal at least {@code minDamage} effective damage; {@code 0} when even a point-blank
     * crystal fails the threshold (the victim is effectively immune at the current settings, so the ring is
     * not drawn).
     *
     * <p>Found by bisection over {@code [0, ExplosionMath.DOUBLE_RADIUS]}, i.e. pure arithmetic and NO
     * raycasts: the raw damage depends on the two points only through their distance, so a probe centre at
     * {@code feet + (d, 0, 0)} stands in for every direction, and the whole pipeline is monotone decreasing in
     * {@code d} — the raw damage falls with {@code d} and every later stage is monotone in it — which is what
     * makes bisection exact here.
     *
     * <p>The shield is deliberately taken OUT of the probe: the blocked damage derives its arc from
     * {@code sourcePosition - blocking.position}, so scoring the probe with it would make the radius depend on
     * the one direction the probe happens to use — the ring would collapse to zero whenever due east sits
     * inside the shield arc, and stay full size when it does not. The ring therefore shows the reach against
     * the victim's ARMOUR, and the blocked sector is drawn on top of it by the shield wedge.
     *
     * <p>I-frames are ignored: the ring answers "what does a crystal do to this victim", not "what does the
     * next crystal do inside the current invulnerability window" — the latter is already in the plan's own
     * damage.
     */
    public static float killZoneRadius(VictimState state, float minDamage) {
        VictimState victim = withoutBlocking(state);
        if (damageAt(victim, 0.0) < minDamage) {
            return 0f;
        }

        double low = 0.0;
        double high = ExplosionMath.DOUBLE_RADIUS;
        for (int step = 0; step < BISECTION_STEPS; step++) {
            double middle = (low + high) / 2.0;
            if (damageAt(victim, middle) >= minDamage) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return (float) low;
    }

    /** The same victim with its block dropped; records have no copy-with, so the components are respread. */
    private static VictimState withoutBlocking(VictimState state) {
        if (state.blocking() == null) {
            return state;
        }
        return new VictimState(state.feet(), state.box(), state.isPlayer(), state.skip(), state.armor(),
                state.toughness(), state.resistanceAmplifier(), state.protectionPoints(), state.absorption(),
                state.health(), null, state.invulnerableTime(), state.lastHurt(), state.difficulty());
    }

    /** Effective damage of a full-exposure crystal {@code distance} blocks east of the victim. */
    private static float damageAt(VictimState victim, double distance) {
        Vec3 center = victim.feet().add(distance, 0.0, 0.0);
        return VictimPipeline.apply(victim, ExplosionMath.rawDamage(victim.feet(), center, 1f), 1f, center,
                false).effective();
    }

    /**
     * {@code bestDamage / (health + absorption)}, clamped to {@code 0..1}: 0 = a crystal barely scratches,
     * 1 = one crystal empties the victim. Drives the cold-to-hot ring colour and the damage text colour.
     */
    public static float heatRatio(AuraVisuals visuals) {
        VictimState state = visuals.targetState();
        if (state == null) {
            return 0f;
        }
        float pool = state.health() + state.absorption();
        if (pool <= 0f) {
            return 0f;
        }
        return Math.clamp(visuals.bestDamage() / pool, 0f, 1f);
    }

    /**
     * Pushes this frame's snapshot into the tweens; draws nothing. The snapshot is {@code null} whenever the
     * module has no plan, which starts the fade-out rather than dropping the ring.
     */
    public static void update(@Nullable AuraVisuals visuals, RenderSettings settings, long nowMs) {
        advanceSpin(visuals, nowMs);
        target = visuals == null ? null : visuals.target();
        boolean alive = isAlive(visuals, settings);
        RING_ALPHA.setTarget(alive ? 1f : 0f);
        if (visuals == null || !alive) {
            updateWedge(null, settings);
            return;
        }

        // Only retarget while alive, so a ring that is fading out keeps its last size instead of imploding.
        RADIUS.setTarget(visuals.killZoneRadius());
        EDGE_COLOR.setTarget(
                AuraAnimColor.lerp(CrystalColors.RING_COLD, CrystalColors.RING_HOT, heatRatio(visuals)));
        updateSegments(segmentCount(visuals, settings));
        updateWedge(visuals.targetState() == null ? null : visuals.targetState().blocking(), settings);
    }

    /** A ring exists only for a live victim with a non-empty kill zone and at least one of its parts on. */
    private static boolean isAlive(@Nullable AuraVisuals visuals, RenderSettings settings) {
        if (visuals == null || visuals.target() == null) {
            return false;
        }
        return visuals.killZoneRadius() > 0f
                && (settings.blastRing.getValue() || settings.shieldWedge.getValue());
    }

    /**
     * Advances the rotation by real time rather than reading the clock directly, because the RATE depends on
     * the heat (hotter victim = visibly faster ring) and a rate change must not teleport the pattern.
     */
    private static void advanceSpin(@Nullable AuraVisuals visuals, long nowMs) {
        long elapsed = spinAt == 0L ? 0L : Math.clamp(nowMs - spinAt, 0L, MAX_SPIN_STEP_MS);
        spinAt = nowMs;
        float heat = visuals == null ? 0f : heatRatio(visuals);
        double rate = SPIN_RAD_PER_SECOND * (1.0 + heat * SPIN_HEAT_GAIN);
        spin = (spin + elapsed / MILLIS_PER_SECOND * rate) % TWO_PI;
    }

    /**
     * Starts a cross-fade whenever the arc layout changes, and a one-shot flash when the count DROPPED, which
     * is exactly the moment a crystal of the projected kill was consumed.
     */
    private static void updateSegments(int count) {
        if (count == segments) {
            return;
        }
        if (count >= 1 && count < segments) {
            CONSUME_FLASH.snap(1f);
            CONSUME_FLASH.setTarget(0f);
        }
        previousSegments = segments;
        segments = count;
        SEGMENT_FADE.snap(0f);
        SEGMENT_FADE.setTarget(1f);
    }

    /**
     * Captures the blocked sector for the frame. Centre = the victim's facing,
     * {@code atan2(viewVector.z, viewVector.x)}: the captured view vector is a unit vector with {@code y == 0},
     * and the ring parameterises the angle as {@code (cos, sin) -> (x, z)} exactly like every other horizontal
     * mark. Half-angle = the largest horizontal blocking angle of the item's reductions (degrees; a vanilla
     * shield is 90), which is the angle the reduction resolution compares against.
     */
    private static void updateWedge(@Nullable BlockingState blocking, RenderSettings settings) {
        float degrees = wedgeDegrees(blocking);
        if (blocking == null || degrees <= 0f || !settings.shieldWedge.getValue()) {
            WEDGE_ALPHA.setTarget(0f);
            return;
        }
        wedgeFacing = Math.atan2(blocking.viewVector().z, blocking.viewVector().x);
        wedgeHalf = Math.min(degrees * DEG_TO_RAD, Math.PI);
        WEDGE_ALPHA.setTarget(1f);
    }

    /** Half-angle of the blocked sector in degrees, or {@code 0} when nothing is actually blocked. */
    private static float wedgeDegrees(@Nullable BlockingState blocking) {
        if (blocking == null || blocking.bypassed()) {
            return 0f;
        }
        float widest = 0f;
        for (BlockReduction reduction : blocking.reductions()) {
            widest = Math.max(widest, reduction.horizontalBlockingAngle());
        }
        return widest;
    }

    /** True while something is still on screen after the target is gone, so the caller keeps the frame alive. */
    public static boolean hasResidual() {
        return !RING_ALPHA.settled() || RING_ALPHA.value() > 0f;
    }

    /** Drops every animation and the cached victim; called from the renderer's own reset. */
    public static void reset() {
        RADIUS.snap(0f);
        RING_ALPHA.snap(0f);
        SEGMENT_FADE.snap(1f);
        CONSUME_FLASH.snap(0f);
        WEDGE_ALPHA.snap(0f);
        EDGE_COLOR.snap(CrystalColors.TRANSPARENT);
        segments = 0;
        previousSegments = 0;
        spin = 0.0;
        spinAt = 0L;
        target = null;
        lastFeet = null;
    }

    /**
     * Draws the ring and the shield wedge at the interpolated feet of the victim, or — while fading out — at
     * the last feet we saw. {@code nowMs} is the wall clock shared with the shockwaves.
     */
    public static void draw(Render3DEvent event, RenderSettings settings, SoftStyle style, long nowMs) {
        float alpha = RING_ALPHA.value();
        float radius = RADIUS.value();
        if (alpha <= 0f || radius <= 0f) {
            return;
        }

        Vec3 feet = target == null
                ? lastFeet
                : Render3DUtility.interpolatedPosition(target, event.partialTick());
        if (feet == null) {
            return;
        }
        lastFeet = feet;

        int edge = EDGE_COLOR.value();
        if (settings.blastRing.getValue()) {
            drawRing(event, feet, radius, edge, alpha, style, nowMs);
        }
        if (settings.shieldWedge.getValue()) {
            drawShieldWedge(event, feet, radius, alpha, style, nowMs);
        }
    }

    /**
     * How many crystals the victim still survives: {@code ceil((health + absorption) / bestDamage)}, clamped to
     * {@code 1..MAX_SEGMENTS}. {@code 0} means "no segmentation" — either the setting is off or no damage is
     * known, and the ring is drawn as one closed band.
     */
    private static int segmentCount(AuraVisuals visuals, RenderSettings settings) {
        if (!settings.killSegments.getValue() || visuals.bestDamage() <= 0f) {
            return 0;
        }
        VictimState state = visuals.targetState();
        if (state == null) {
            return 0;
        }
        float pool = state.health() + state.absorption();
        if (pool <= 0f) {
            return 0;
        }
        return Math.clamp((int) Math.ceil(pool / visuals.bestDamage()), 1, MAX_SEGMENTS);
    }

    /**
     * The band, cross-faded between the layout we are leaving and the one we are moving to, and brightened
     * towards white while the consume flash decays. Both layouts share the same rotation, so the arcs slide
     * instead of jumping.
     */
    private static void drawRing(Render3DEvent event, Vec3 feet, float radius, int edge, float alpha,
                                 SoftStyle style, long nowMs) {
        float flash = CONSUME_FLASH.value();
        int hot = flash <= 0f ? edge : AuraAnimColor.lerp(edge, CrystalColors.WHITE, flash * FLASH_MIX);
        float boost = 1f + flash * FLASH_ALPHA_GAIN;
        float fade = SEGMENT_FADE.value();

        if (fade < 1f && previousSegments != segments) {
            drawRingLayer(event, feet, radius, hot, previousSegments, alpha * (1f - fade) * boost, style,
                    nowMs);
        }
        drawRingLayer(event, feet, radius, hot, segments, alpha * fade * boost, style, nowMs);
    }

    /**
     * One arc layout: no segments draws a closed gradient band, one draws it pulsing (the victim dies to the
     * next crystal), anything else draws that many rotating arcs separated by a small gap.
     *
     * <p>All three go through the feathered helpers, so the band no longer ends on a hard rim at the radius:
     * the fade zones eat inwards from both edges and the kill-zone radius stays exact.
     */
    private static void drawRingLayer(Render3DEvent event, Vec3 feet, float radius, int edge, int count,
                                      float alpha, SoftStyle style, long nowMs) {
        // The consume flash has to be able to push the band past its configured alpha, so the channel is
        // computed directly rather than through a helper that can only dim.
        int outer = scaleAlpha(edge, alpha);
        if (alphaOf(outer) == 0) {
            return;
        }
        float inner = Math.max(radius - BAND, 0f);

        if (count <= 0) {
            SoftGeometry.drawSoftRing(event, feet, radius, inner, outer, transparent(outer), style);
            return;
        }
        if (count == 1) {
            int pulsed = fade(outer, PULSE_BASE + PULSE_AMPLITUDE * AuraAnim.pulse(nowMs, PULSE_PERIOD_MS));
            SoftGeometry.drawSoftRing(event, feet, radius, inner, pulsed, transparent(pulsed), style);
            return;
        }

        int rim = transparent(outer);
        double step = TWO_PI / count;
        double gap = Math.min(GAP_RAD, step * MAX_GAP_FRACTION);
        for (int index = 0; index < count; index++) {
            double from = spin + index * step;
            SoftGeometry.drawSoftArc(event, feet, radius, inner, from, from + step - gap, outer, rim, style);
        }
    }

    /**
     * The sector in which our crystals are fully eaten by the victim's shield, drawn just inside the ring: it
     * fades in and out with its own tween and breathes on a sine, so a raised shield reads as a live state
     * rather than a static fill. The angles were captured in {@link #updateWedge}.
     */
    private static void drawShieldWedge(Render3DEvent event, Vec3 feet, float radius, float alpha,
                                        SoftStyle style, long nowMs) {
        float breathe = WEDGE_BREATHE_BASE + WEDGE_BREATHE_AMP * AuraAnim.pulse(nowMs, WEDGE_BREATHE_MS);
        int faded = fade(CrystalColors.SHIELD, Math.clamp(WEDGE_ALPHA.value() * alpha * breathe, 0f, 1f));
        float outer = radius - BAND - SHIELD_GAP;
        if (alphaOf(faded) == 0 || outer <= 0f) {
            return;
        }

        SoftGeometry.drawSoftArc(event, feet, outer, Math.max(outer - SHIELD_BAND, 0f),
                wedgeFacing - wedgeHalf, wedgeFacing + wedgeHalf,
                faded, withAlpha(faded, alphaOf(faded) / SHIELD_INNER_DIVISOR), style);
    }

    private static int alphaOf(int argb) {
        return argb >>> 24;
    }

    private static int withAlpha(int argb, int alpha) {
        return (Math.clamp(alpha, 0, 255) << 24) | (argb & 0x00FFFFFF);
    }

    /** Scales the alpha by a factor that may exceed 1; saturates rather than wrapping. */
    private static int scaleAlpha(int argb, float factor) {
        return withAlpha(argb, (int) (alphaOf(argb) * factor));
    }

    /** Dims only, like the colour helper it replaces: a factor at or above 1 leaves the colour alone. */
    private static int fade(int argb, float factor) {
        return factor >= 1f ? argb : scaleAlpha(argb, factor);
    }

    private static int transparent(int argb) {
        return argb & 0x00FFFFFF;
    }
}
