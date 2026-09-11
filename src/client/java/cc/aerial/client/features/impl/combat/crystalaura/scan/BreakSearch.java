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

package cc.aerial.client.features.impl.combat.crystalaura.scan;

import cc.aerial.client.features.impl.combat.crystalaura.calc.DamageCalculator;
import cc.aerial.client.features.impl.combat.crystalaura.calc.DamageResult;
import cc.aerial.client.features.impl.combat.crystalaura.config.BreakSettings;
import cc.aerial.client.features.impl.combat.crystalaura.config.PlaceSettings;
import cc.aerial.client.features.impl.combat.crystalaura.state.CrystalLedger;
import cc.aerial.client.features.impl.combat.crystalaura.world.TargetEntry;
import cc.aerial.client.features.impl.combat.crystalaura.world.WorldView;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Finds the existing crystal to attack right now. One instance per tick, since it holds the tick's world view;
 * main thread only.
 *
 * <p>Vanilla: any damage removes the crystal and explodes it at its own position with power 6, so the centre is
 * the crystal's position and the victims are hurt exactly as for a placement. The acceptance rules and the
 * ordering are the placement scan's, evaluated at the break snapshot, through the same ranker.
 *
 * <p>Candidates: every crystal in the view, minus those we already attacked or expect to be chain-removed and,
 * with the own-only setting, those not confirmed ours. The prefilter is the crystal's bounding box closer than the
 * break range to the eye — the eye-to-hit-point distance of the attack pick can only be larger, and the exact ray
 * test is the planner's look point search.
 */
public final class BreakSearch {
    private final WorldView view;
    private final DamageCalculator calc;
    private final PlaceSettings place;
    private final BreakSettings brk;
    private final CrystalLedger ledger;

    public BreakSearch(WorldView view, DamageCalculator calc, PlaceSettings place,
                       BreakSettings brk, CrystalLedger ledger) {
        this.view = view;
        this.calc = calc;
        this.place = place;
        this.brk = brk;
        this.ledger = ledger;
    }

    /** Best existing crystal to attack now, or null. */
    @Nullable
    public BreakCandidate best() {
        ExplosionRanker<EndCrystal> ranker = new ExplosionRanker<>(
                view, calc, place,
                TargetEntry::breakState,
                TargetEntry::source,
                view.selfSource(),
                crystal -> true);
        Vec3 eye = view.eye();
        double rangeSq = rangeSq();
        for (EndCrystal crystal : view.crystals()) {
            if (isExcluded(crystal.getId())) {
                continue;
            }
            double distSq = crystal.getBoundingBox().distanceToSqr(eye);
            if (distSq < rangeSq) {
                ranker.offer(crystal, crystal.position(), distSq);
            }
        }
        Ranked<EndCrystal> best = ranker.best();
        return best == null ? null : new BreakCandidate(best.key(), best.center(), best.target(),
                best.damage(), best.selfDamage());
    }

    /**
     * Damage bookkeeping for a crystal exploding at a centre, for the id-prediction phantoms and the ledger's
     * lastHurt estimates: target entity id to the post-shield damage, the value vanilla stores in lastHurt.
     *
     * <p>Victims that take nothing and i-frame-gated hits are left out: for the latter vanilla returns before
     * touching lastHurt, so the ledger's previous estimate stays valid. Evaluated at the break snapshot; the local
     * player is not tracked by the ledger and therefore not included.
     */
    public Map<Integer, Float> predictedLastHurt(Vec3 center) {
        Int2FloatOpenHashMap result = new Int2FloatOpenHashMap();
        for (TargetEntry target : view.targets()) {
            DamageResult damage = calc.damage(center, target.breakState(), target.source());
            if (damage.afterBlocking() > 0f && !damage.iFrameGated()) {
                result.put(target.entity().getId(), damage.afterBlocking());
            }
        }
        return new HashMap<>(result);
    }

    private boolean isExcluded(int id) {
        if (ledger.isAttacked(id) || ledger.isExpectedRemoved(id)) {
            return true;
        }
        return brk.onlyOwn.getValue() && !ledger.isOwn(id);
    }

    private double rangeSq() {
        double range = brk.range.getValue();
        return range * range;
    }
}
