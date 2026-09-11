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

package cc.aerial.client.features.impl.combat.crystalaura.debug;

import cc.aerial.client.features.impl.combat.crystalaura.calc.DamageResult;
import cc.aerial.client.features.impl.combat.crystalaura.calc.ExplosionMath;
import cc.aerial.client.features.impl.combat.crystalaura.calc.Exposure;
import cc.aerial.client.features.impl.combat.crystalaura.calc.VictimState;
import cc.aerial.client.features.impl.combat.crystalaura.world.TargetEntry;
import cc.aerial.client.utility.ChatUtility;
import cc.aerial.client.utility.Stopwatch;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Diagnostics of the crystal aura: a rate-limited chat line, and a runtime exactness check of the replicated
 * explosion model against vanilla's own code running on the client.
 *
 * <p>This is a DIAGNOSTIC path, only active with the module's debug setting: every cross-check casts the
 * complete exposure grid through the vanilla clip — 45 rays for a player-sized box — in addition to the module's
 * own rays. Both entry points return immediately when their enabled argument is false. Main thread only: the
 * counters, the timers and the report buffer are shared, unsynchronised state.
 *
 * <h2>The oracles, and why these three stages</h2>
 * <p>Every stage compared here has an EXACT oracle, so a mismatch is always our bug and never the oracle's:
 * <ul>
 *   <li><b>exposure</b> against {@code ServerExplosion.getSeenPercent(Vec3, Entity)} — a public static of the
 *       game itself, which samples the entity's live box and clips its own level with its own collision
 *       context. Our side is therefore re-evaluated at the LIVE box through the target's own shape source, the
 *       same context and a per-tick memo, rather than taking the possibly predicted result the aura scored
 *       with.</li>
 *   <li><b>raw</b> against the vanilla damage formula recomputed from that vanilla exposure.</li>
 *   <li><b>afterDifficulty</b> against the vanilla difficulty table, which is players-only.</li>
 * </ul>
 *
 * <p>LiquidBounce also compares the armour and enchantment stages against a client-side re-derivation that sums
 * the attribute modifiers of the four armour stacks and applies a fixed protection table. Those two legs are
 * deliberately NOT ported. Its own documentation concedes that a mismatch there "can be the oracle's
 * simplification, not necessarily our error" — the re-derivation reads equipment rather than the synced
 * attributes our victim snapshot uses, and its fixed table is blind to data-pack protection effects that our
 * enchantment model follows. Porting it would add a hundred lines whose disagreements are mostly false alarms.
 * Those two stages already have a better reference: the damage pipeline is a line-by-line replica of the vanilla
 * code, held to it by its own tests.
 *
 * <p>Not comparable at all, because no oracle models them: the shield stage, the invulnerability window, and the
 * absorption split. The damage stages are therefore only compared when neither the shield nor the window changed
 * the value, and the emitted line says so when they are skipped.
 *
 * <h2>Bookkeeping</h2>
 * <p>Every stage comparison performed is counted, and every one whose absolute difference exceeds its tolerance
 * is counted as a mismatch. The counters are updated whether or not the line is emitted; the line itself is
 * rate-limited to one per second. The shape source memo is per tick, so run the check before the tick's actions
 * have changed the world — vanilla's side always reads the live level.
 */
public final class AuraDebug {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("Aerial");

    private AuraDebug() {
    }

    /** Five log lines per second at most. */
    private static final long LOG_INTERVAL_MS = 200L;

    /** One mismatch line per second at most. */
    private static final long MISMATCH_INTERVAL_MS = 1000L;

    /** A real exposure disagreement is at least one ray out of 45, far above this. */
    private static final float EXPOSURE_TOLERANCE = 1e-4f;

    private static final float DAMAGE_TOLERANCE = 0.05f;

    private static final String STAGE_SEPARATOR = " | ";

    private static Stopwatch logTimer = new Stopwatch(0);
    private static Stopwatch mismatchTimer = new Stopwatch(0);

    /** Reusable buffer for the mismatch line; only its final conversion allocates. */
    private static final StringBuilder REPORT = new StringBuilder();

    private static int checks;
    private static int mismatches;

    /** Stage comparisons performed since the last reset. */
    public static int checks() {
        return checks;
    }

    /** Comparisons that exceeded their tolerance. */
    public static int mismatches() {
        return mismatches;
    }

    /**
     * Rate-limited debug line, only when enabled. The message is a supplier so nothing is built when the
     * line is disabled or dropped.
     *
     * <p>It goes to the chat AND to the log. The chat is what a player watches while testing, but it holds a
     * hundred lines and a busy server scrolls them away in seconds; the log is what survives the session and
     * can be read afterwards. Client-side chat is not reliably mirrored into the log by the game, so writing
     * both here is the only way a debug session leaves a record.
     */
    public static void log(boolean enabled, Supplier<String> message) {
        if (enabled && logTimer.hasTimeElapsed(LOG_INTERVAL_MS)) {
            logTimer.reset();
            String line = message.get();
            ChatUtility.print(line);
            LOGGER.info("[CrystalAura] {}", line);
        }
    }

    /**
     * Says why a tick produced no plan at all.
     *
     * <p>Without this, "the aura decided to do nothing" and "the aura never got as far as deciding" look
     * exactly the same from outside: both are silence. They have completely different causes, so they must
     * not look the same.
     */
    public static void bail(boolean enabled, String reason) {
        log(enabled, () -> "no plan: " + reason);
    }

    /**
     * Compares the aura's model with vanilla for the crystal explosion at a centre against one target.
     *
     * <p>The result passed in is the one the aura scored with, computed on the break snapshot or the place
     * snapshot depending on the phase. The damage stages are compared only when that snapshot's feet are the
     * entity's current position — a predicted victim has no oracle — when the result was actually evaluated,
     * and when neither the shield nor the invulnerability window altered the value. Otherwise only the exposure
     * is checked, and the emitted line notes why.
     *
     * <p>Diagnostic only: 45 vanilla clip calls per check on top of the module's own 45.
     */
    public static void crossCheck(boolean enabled, Vec3 center, TargetEntry target, DamageResult ours,
                                  boolean forBreak) {
        if (!enabled) {
            return;
        }
        LivingEntity entity = target.entity();
        VictimState state = forBreak ? target.breakState() : target.placeState();
        String skipReason = damageSkipReason(ours, state.feet(), entity);

        float vanillaExposure = ServerExplosion.getSeenPercent(center, entity);
        float liveExposure = Exposure.seenPercent(center, entity.getBoundingBox(), target.source());

        boolean describe = mismatchTimer.hasTimeElapsed(MISMATCH_INTERVAL_MS);
        REPORT.setLength(0);
        compare("exposure", liveExposure, vanillaExposure, EXPOSURE_TOLERANCE, describe);
        if (skipReason == null) {
            float vanillaRaw = ExplosionMath.rawDamage(entity.position(), center, vanillaExposure);
            compare("raw", ours.raw(), vanillaRaw, DAMAGE_TOLERANCE, describe);
            compare("afterDifficulty", ours.afterDifficulty(),
                    scaleByDifficulty(vanillaRaw, state), DAMAGE_TOLERANCE, describe);
        }
        if (REPORT.isEmpty()) {
            return;
        }

        mismatchTimer.reset();
        if (skipReason != null) {
            REPORT.append(STAGE_SEPARATOR).append("damage stages skipped: ").append(skipReason);
        }
        ChatUtility.print("CrossCheck " + entity.getName().getString() + "#" + entity.getId()
                + " (" + (forBreak ? "break" : "place") + "): " + REPORT);
    }

    /** Zeroes the counters and lets the next log and mismatch line through immediately. */
    public static void reset() {
        checks = 0;
        mismatches = 0;
        REPORT.setLength(0);
        // A stopwatch started at zero is already long elapsed, so the next line of either kind passes at once.
        logTimer = new Stopwatch(0);
        mismatchTimer = new Stopwatch(0);
    }

    /**
     * Why the damage stages cannot be compared, or null when they can: the result was not evaluated at all —
     * a skipped victim or one outside the radius — the snapshot is at a predicted position rather than the
     * entity's live one, or the shield or invulnerability stages, which have no oracle, changed the value.
     */
    @Nullable
    private static String damageSkipReason(DamageResult ours, Vec3 feet, LivingEntity entity) {
        if (ours.raw() == 0f) {
            return "not evaluated (skipped victim or outside the radius)";
        }
        if (!feet.equals(entity.position())) {
            return "victim not at the evaluated position (predicted)";
        }
        if (ours.dealt() != ours.afterDifficulty()) {
            return "shield or invulnerability stage changed the value (no oracle)";
        }
        return null;
    }

    /** The vanilla difficulty table, which applies to players only. */
    private static float scaleByDifficulty(float raw, VictimState victim) {
        if (!victim.isPlayer()) {
            return raw;
        }
        Difficulty difficulty = victim.difficulty();
        return switch (difficulty) {
            case PEACEFUL -> 0.0f;
            case EASY -> Math.min(raw / 2.0f + 1.0f, raw);
            case NORMAL -> raw;
            case HARD -> raw * 3.0f / 2.0f;
        };
    }

    /**
     * Counts one comparison; a difference above the tolerance, or a NaN, counts as a mismatch and, when the
     * rate limit allows, is appended to the report with both values.
     */
    private static void compare(String stage, float ours, float vanilla, float tolerance, boolean describe) {
        checks++;
        if (Math.abs(ours - vanilla) <= tolerance) {
            return;
        }
        mismatches++;
        if (!describe) {
            return;
        }
        if (!REPORT.isEmpty()) {
            REPORT.append(STAGE_SEPARATOR);
        }
        REPORT.append(stage).append(" ours=").append(format(ours))
                .append(" vanilla=").append(format(vanilla));
    }

    private static String format(float value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
