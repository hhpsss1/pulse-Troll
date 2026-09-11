package cc.aerial.client.features.impl.combat.crystalaura.scan;

import cc.aerial.client.features.impl.combat.crystalaura.calc.DamageCalculator;
import cc.aerial.client.features.impl.combat.crystalaura.calc.DamageResult;
import cc.aerial.client.features.impl.combat.crystalaura.calc.VictimState;
import cc.aerial.client.features.impl.combat.crystalaura.config.PlaceSettings;
import cc.aerial.client.features.impl.combat.crystalaura.scan.Fixtures.Finalist;
import cc.aerial.client.features.impl.combat.crystalaura.world.TargetEntry;
import cc.aerial.client.features.impl.combat.crystalaura.world.WorldView;
import cc.aerial.client.testsupport.MinecraftBootstrap;
import net.minecraft.world.Difficulty;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lazy ranking walk against the single-result search it replaces.
 *
 * <p>The reference is {@link #oldBest}, the loop the ranker ran before the lazy walk existed: walk the finalists
 * in target order, stop at the first one whose target damage is strictly below that of an accepted candidate, and
 * keep the rank minimum of everything accepted on the way. The contract is therefore not just "same head" but
 * "same head for the same self-damage evaluations, in the same order" — one evaluation is 45 rays, and the lazy
 * walk must not have made the common tick more expensive.
 *
 * <p>The log records those evaluations. The centre of the finalist at index {@code i} is {@code (i, 0, 0)}, so the
 * log is the list of finalist indices the walk paid for.
 */
class ExplosionRankerTest {
    private static final int RANDOM_RANKINGS = 400;
    private static final int MAX_CELLS = 9;

    /** Few distinct damages, so tie groups — the whole point of the walk — occur constantly. */
    private static final float[] TIED_DAMAGES = {6f, 8f, 10f};

    /** The contract order, spelled out here so the test does not lean on the production comparator. */
    private static final Comparator<Ranked<?>> BY_RANK =
            Comparator.<Ranked<?>>comparingDouble(r -> -r.damage().effective())
                    .thenComparingDouble(r -> r.selfDamage().effective())
                    .thenComparingDouble(Ranked::distSq);

    private final PlaceSettings settings = new PlaceSettings();
    private final VictimState targetState = Fixtures.victim(new Vec3(0.5, 64.0, 0.5));
    private final TargetEntry target = Fixtures.target(targetState);

    @BeforeAll
    static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Test
    void headIsTheOldSelectionWhenTheTieGroupReordersBySelfDamage() {
        // A is first by target order (equal damage, nearer), B wins by rank (equal damage, less self damage).
        List<Finalist> cells = List.of(
                new Finalist("A", 10f, 0.5, 5f),
                new Finalist("B", 10f, 1.5, 1f),
                new Finalist("C", 8f, 0.1, 0f));
        List<Scored<String>> list = Fixtures.finalists(cells, target);
        SelfDamageLog log = new SelfDamageLog(cells);
        Ranked<String> head = first(FinalistRanking.rank(() -> list, log::of, this::acceptsSelf));

        assertEquals("B", head.key());
        assertEquals(1f, head.selfDamage().effective());
        // The whole tie group, and only it: C is never evaluated although it is a finalist.
        assertEquals(List.of(0, 1), log.evaluated);
        assertSameAsOld(list, cells, head);
    }

    @Test
    void headSkipsATieGroupThatFailsTheSelfChecks() {
        List<Finalist> cells = List.of(
                new Finalist("A", 10f, 0.5, 9f),
                new Finalist("B", 10f, 1.5, 7f),
                new Finalist("C", 8f, 0.1, 3f));
        List<Scored<String>> list = Fixtures.finalists(cells, target);
        SelfDamageLog log = new SelfDamageLog(cells);
        Ranked<String> head = first(FinalistRanking.rank(() -> list, log::of, this::acceptsSelf));

        assertEquals("C", head.key());
        assertEquals(List.of(0, 1, 2), log.evaluated);
        assertSameAsOld(list, cells, head);
    }

    @Test
    void theWalkIsOrderedAndDropsWhatTheSelfChecksReject() {
        List<Finalist> cells = orderedCells();
        List<Scored<String>> list = Fixtures.finalists(cells, target);
        SelfDamageLog log = new SelfDamageLog(cells);
        List<Ranked<String>> ranked = all(FinalistRanking.rank(() -> list, log::of, this::acceptsSelf));

        assertEquals(List.of("B", "A", "D", "E"), ranked.stream().map(Ranked::key).toList());
        assertEquals(List.of(10f, 10f, 8f, 6f), ranked.stream().map(r -> r.damage().effective()).toList());
        assertEquals(List.of(1f, 5f, 2f, 0f), ranked.stream().map(r -> r.selfDamage().effective()).toList());
        for (int i = 1; i < ranked.size(); i++) {
            assertTrue(ranked.get(i - 1).damage().effective() >= ranked.get(i).damage().effective());
        }
    }

    @Test
    void pullingTheHeadEvaluatesOnlyItsOwnTieGroup() {
        List<Finalist> cells = orderedCells();
        List<Scored<String>> list = Fixtures.finalists(cells, target);
        SelfDamageLog headLog = new SelfDamageLog(cells);
        SelfDamageLog fullLog = new SelfDamageLog(cells);

        first(FinalistRanking.rank(() -> list, headLog::of, this::acceptsSelf));
        all(FinalistRanking.rank(() -> list, fullLog::of, this::acceptsSelf));

        assertEquals(List.of(0, 1), headLog.evaluated);
        assertEquals(List.of(0, 1, 2, 3, 4), fullLog.evaluated);
        // The supplier is pulled lazily as well: building the walk must not drain the ranker's queue.
        boolean[] built = {false};
        FinalistRanking.rank(() -> {
            built[0] = true;
            return list;
        }, headLog::of, this::acceptsSelf);
        assertFalse(built[0]);
    }

    @Test
    void noAcceptedFinalistYieldsAnEmptyWalkAtTheOldCost() {
        List<Finalist> cells = List.of(
                new Finalist("A", 10f, 0.5, 9f),
                new Finalist("B", 8f, 0.5, 9f));
        List<Scored<String>> list = Fixtures.finalists(cells, target);
        SelfDamageLog log = new SelfDamageLog(cells);

        assertNull(firstOrNull(FinalistRanking.rank(() -> list, log::of, this::acceptsSelf)));
        assertEquals(List.of(0, 1), log.evaluated);
        assertNull(oldBest(list, new SelfDamageLog(cells)::of));
    }

    @Test
    void headAndCostMatchTheOldSelectionOnRandomRankings() {
        Random random = new Random(20260906L);
        for (int run = 0; run < RANDOM_RANKINGS; run++) {
            int count = 1 + random.nextInt(MAX_CELLS - 1);
            List<Finalist> cells = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                cells.add(new Finalist(
                        "c" + index,
                        TIED_DAMAGES[random.nextInt(TIED_DAMAGES.length)],
                        1 + random.nextInt(5),
                        random.nextInt(10)));
            }
            List<Scored<String>> list = Fixtures.finalists(cells, target);
            SelfDamageLog newLog = new SelfDamageLog(cells);
            SelfDamageLog oldLog = new SelfDamageLog(cells);
            Ranked<String> head = firstOrNull(FinalistRanking.rank(() -> list, newLog::of, this::acceptsSelf));
            Ranked<String> reference = oldBest(list, oldLog::of);

            assertEquals(reference == null ? null : reference.key(), head == null ? null : head.key());
            assertEquals(reference == null ? null : reference.selfDamage().effective(),
                    head == null ? null : head.selfDamage().effective());
            assertEquals(oldLog.evaluated, newLog.evaluated);
        }
    }

    @Test
    void rankedWalksTheTieGroupOfARealRanker() {
        // Two centres exactly 3 blocks from the target's feet — the same damage — and 11.31 and 12.08 blocks from
        // the local player, so only the first of them hurts us at all. The target order offers the nearer cell
        // first; the rank order has to answer with the harmless one.
        VictimState selfState = Fixtures.victim(new Vec3(11.5, 64.0, 8.5));
        WorldView view = new WorldView(
                0,
                new Vec3(11.5, 65.6, 8.5),
                selfState,
                Fixtures.emptyShapes(),
                List.of(target),
                List.of(),
                Difficulty.NORMAL,
                null);

        List<Ranked<String>> ranked = all(offerPair(ranker(view)).ranked());

        assertEquals(List.of("far", "near"), ranked.stream().map(Ranked::key).toList());
        assertEquals(ranked.get(0).damage().effective(), ranked.get(1).damage().effective());
        assertEquals(0f, ranked.get(0).selfDamage().effective());
        assertTrue(ranked.get(1).selfDamage().effective() > 0f);
        assertEquals("far", offerPair(ranker(view)).best().key());
    }

    /** The five cells of the ordering and laziness tests: two tie groups, one rejected member, one lone tail. */
    private static List<Finalist> orderedCells() {
        return List.of(
                new Finalist("A", 10f, 0.5, 5f),
                new Finalist("B", 10f, 1.5, 1f),
                new Finalist("C", 8f, 0.1, 9f),
                new Finalist("D", 8f, 0.2, 2f),
                new Finalist("E", 6f, 0.0, 0f));
    }

    /** The acceptance the walk is given; anti-suicide cannot bite here. */
    private boolean acceptsSelf(DamageResult damage) {
        return damage.effective() <= settings.maxSelfDamage.getValue();
    }

    /** The head is what the old selection would have returned, for the very same evaluations in the same order. */
    private void assertSameAsOld(List<Scored<String>> list, List<Finalist> cells, Ranked<String> head) {
        SelfDamageLog log = new SelfDamageLog(cells);
        Ranked<String> reference = oldBest(list, log::of);
        assertEquals(reference == null ? null : reference.key(), head.key());
        assertEquals(reference == null ? null : reference.selfDamage().effective(), head.selfDamage().effective());
    }

    /**
     * The loop the ranker used to be, written out independently of the production comparator: walk the finalists
     * in target order, break at the first target damage strictly below that of an accepted candidate, and keep the
     * rank minimum of the accepted ones.
     */
    private Ranked<String> oldBest(List<Scored<String>> finalists, Function<Vec3, DamageResult> selfDamage) {
        Ranked<String> best = null;
        for (Scored<String> scored : finalists) {
            if (best != null && scored.damage.effective() < best.damage().effective()) {
                break;
            }
            DamageResult self = selfDamage.apply(scored.bound.center);
            if (acceptsSelf(self)) {
                Ranked<String> ranked = new Ranked<>(scored, self);
                if (best == null || BY_RANK.compare(ranked, best) < 0) {
                    best = ranked;
                }
            }
        }
        return best;
    }

    private ExplosionRanker<String> ranker(WorldView view) {
        return new ExplosionRanker<>(view, new DamageCalculator(false), settings,
                TargetEntry::placeState, TargetEntry::source, Fixtures.emptyShapes(), key -> true);
    }

    private static ExplosionRanker<String> offerPair(ExplosionRanker<String> ranker) {
        ranker.offer("near", new Vec3(3.5, 64.0, 0.5), 1.0);
        ranker.offer("far", new Vec3(0.5, 64.0, 3.5), 9.0);
        return ranker;
    }

    private static <T> T first(Iterable<T> iterable) {
        return iterable.iterator().next();
    }

    private static <T> T firstOrNull(Iterable<T> iterable) {
        Iterator<T> iterator = iterable.iterator();
        return iterator.hasNext() ? iterator.next() : null;
    }

    private static <T> List<T> all(Iterable<T> iterable) {
        List<T> result = new ArrayList<>();
        iterable.forEach(result::add);
        return result;
    }

    /**
     * Self damage by explosion centre, recording which finalist each evaluation belonged to. The centre of
     * finalist {@code i} is {@code (i, 0, 0)}, so the record is the list of indices the walk paid for.
     */
    private static final class SelfDamageLog {
        private final List<Finalist> sorted;
        private final List<Integer> evaluated = new ArrayList<>();

        private SelfDamageLog(List<Finalist> cells) {
            this.sorted = new ArrayList<>(cells);
            this.sorted.sort(Fixtures.BY_TARGET);
        }

        private DamageResult of(Vec3 center) {
            int index = (int) center.x;
            evaluated.add(index);
            return Fixtures.result(sorted.get(index).self());
        }
    }
}
