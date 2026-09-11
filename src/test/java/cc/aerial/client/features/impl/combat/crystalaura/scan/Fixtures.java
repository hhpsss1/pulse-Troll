package cc.aerial.client.features.impl.combat.crystalaura.scan;

import cc.aerial.client.features.impl.combat.crystalaura.calc.DamageResult;
import cc.aerial.client.features.impl.combat.crystalaura.calc.ShapeSource;
import cc.aerial.client.features.impl.combat.crystalaura.calc.VictimState;
import cc.aerial.client.features.impl.combat.crystalaura.world.TargetEntry;
import net.minecraft.world.Difficulty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Fixtures every ranking test builds on: the finalist list, the victim snapshots and the target entry a bound
 * needs.
 */
final class Fixtures {
    private Fixtures() {
    }

    /**
     * One constructed finalist of a ranking: the key names it in the assertions, the effective damage is what the
     * ranking is ordered by, the squared distance is the tie-break, and the self damage is what the walk is to
     * find at its explosion centre.
     */
    record Finalist(String key, float effective, double distSq, float self) {
    }

    /** Target damage descending, then distance ascending — the order the walk expects. */
    static final Comparator<Finalist> BY_TARGET =
            Comparator.<Finalist>comparingDouble(f -> -f.effective()).thenComparingDouble(Finalist::distSq);

    /**
     * The cells as the finalist list the walk expects, sorted so a tie group is contiguous.
     *
     * <p>The explosion centre of entry {@code i} is {@code (i, 0, 0)}, so a self-damage lookup can recover the
     * cell from the centre alone — the walk only ever hands the centre to the self-damage function. The bound is
     * irrelevant past the refinement and is set to the effective damage.
     */
    static List<Scored<String>> finalists(List<Finalist> cells, TargetEntry target) {
        List<Finalist> sorted = new ArrayList<>(cells);
        sorted.sort(BY_TARGET);
        List<Scored<String>> result = new ArrayList<>(sorted.size());
        for (int index = 0; index < sorted.size(); index++) {
            Finalist cell = sorted.get(index);
            Bound<String> bound = new Bound<>(cell.key(), new Vec3(index, 0.0, 0.0),
                    cell.distSq(), target, cell.effective());
            result.add(new Scored<>(bound, result(cell.effective())));
        }
        return result;
    }

    /** A damage result with the given effective damage and nothing else set. */
    static DamageResult result(float effective) {
        return new DamageResult(0f, 0f, 0f, 0f, false, 0f, 0f, effective, 0f, 0f);
    }

    /** A shape source without a single occluder: every exposure ray passes, so the exposure is 1. */
    static ShapeSource emptyShapes() {
        return (x, y, z) -> Shapes.empty();
    }

    /** A 20-health player victim with no armour, no effects, no shield and no i-frames. */
    static VictimState victim(Vec3 feet) {
        return victim(feet, 20f);
    }

    static VictimState victim(Vec3 feet, float health) {
        AABB box = new AABB(feet.x - 0.3, feet.y, feet.z - 0.3, feet.x + 0.3, feet.y + 1.8, feet.z + 0.3);
        return new VictimState(feet, box, true, false, 0, 0f, -1, 0f, 0f, health,
                null, 0, null, Difficulty.NORMAL);
    }

    /**
     * A target entry carrying the state for both snapshots, with no entity at all.
     *
     * <p>Nothing in the ranker or the walk ever reads the entity — it only matters to the callers that turn a
     * ranking into a candidate — and standing a real one up would mean standing a real world up for a value that
     * is never touched. LiquidBounce passes an armour stand with a null level here, which worked while entity ids
     * came from a static counter; 26.2 allocates them through {@code level.getNextEntityId()}, so a null level now
     * throws inside the entity constructor. A null entity is the honest fixture either way.
     */
    static TargetEntry target(VictimState state) {
        return new TargetEntry(null, state, state, emptyShapes());
    }
}
