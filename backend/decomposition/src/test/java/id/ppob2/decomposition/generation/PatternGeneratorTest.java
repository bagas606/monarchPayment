package id.ppob2.decomposition.generation;

import static org.assertj.core.api.Assertions.assertThat;

import id.ppob2.decomposition.generation.PatternGenerator.Component;
import id.ppob2.decomposition.generation.PatternGenerator.Params;
import id.ppob2.decomposition.generation.PatternGenerator.Pattern;
import id.ppob2.decomposition.generation.PatternGenerator.Result;
import id.ppob2.decomposition.generation.PatternGenerator.Sku;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PatternGeneratorTest {

    private static final Params BIG = new Params(10_000, 12, 100, 50_000_000);

    private static Sku sku(long id, long face) {
        return new Sku(id, face, null);
    }

    private static long sum(Pattern p) {
        return p.components().stream().mapToLong(c -> c.faceValue() * c.quantity()).sum();
    }

    /** Independent oracle: every multiset of (sku id -> count) with total quantity <= maxQ summing to the amount. */
    private static Set<String> bruteForce(List<Sku> skus, long amount, int maxQ) {
        Set<String> found = new HashSet<>();
        recurse(skus, 0, amount, maxQ, new TreeMap<>(), found);
        return found;
    }

    private static void recurse(List<Sku> skus, int i, long remaining, int qLeft, TreeMap<Long, Integer> chosen, Set<String> out) {
        if (remaining == 0 && !chosen.isEmpty()) {
            out.add(chosen.toString());
        }
        if (i == skus.size() || qLeft == 0) {
            return;
        }
        Sku s = skus.get(i);
        int max = (int) Math.min(qLeft, s.cap() == null ? Integer.MAX_VALUE : s.cap());
        for (int c = 0; c <= max && c * s.faceValue() <= remaining; c++) {
            if (c > 0) {
                chosen.put(s.id(), c);
            }
            recurse(skus, i + 1, remaining - c * s.faceValue(), qLeft - c, chosen, out);
            chosen.remove(s.id());
        }
    }

    private static String key(Pattern p) {
        TreeMap<Long, Integer> m = new TreeMap<>();
        p.components().forEach(c -> m.put(c.skuId(), c.quantity()));
        return m.toString();
    }

    @Test
    void everyPatternSumsToTheAmountExactly() {
        Result r = PatternGenerator.generate(List.of(sku(1, 10_000), sku(2, 20_000), sku(3, 50_000), sku(4, 20_000)), 100_000, BIG);
        assertThat(r.patterns()).isNotEmpty();
        assertThat(r.patterns()).allSatisfy(p -> assertThat(sum(p)).isEqualTo(100_000));
    }

    @Test
    void matchesBruteForceOnRandomSmallCatalogs() {
        Random rnd = new Random(42);
        for (int trial = 0; trial < 60; trial++) {
            int n = 1 + rnd.nextInt(4);
            List<Sku> skus = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                skus.add(new Sku(i + 1, 1 + rnd.nextInt(9), rnd.nextInt(3) == 0 ? 1 + rnd.nextInt(3) : null));
            }
            long amount = 1 + rnd.nextInt(30);
            int maxQ = 1 + rnd.nextInt(8);

            Result r = PatternGenerator.generate(skus, amount, new Params(1_000_000, maxQ, 100, 50_000_000));
            Set<String> got = r.patterns().stream().map(PatternGeneratorTest::key).collect(Collectors.toSet());

            assertThat(got).as("trial %d skus=%s amount=%d maxQ=%d", trial, skus, amount, maxQ).isEqualTo(bruteForce(skus, amount, maxQ));
            assertThat(r.patterns().size()).as("no duplicates").isEqualTo(got.size());
            assertThat(r.infeasible()).isEqualTo(got.isEmpty());
        }
    }

    @Test
    void fewestChildUnitsComeFirst() {
        Result r = PatternGenerator.generate(List.of(sku(1, 10_000), sku(2, 40_000)), 80_000, BIG);
        List<Integer> q = r.patterns().stream().map(Pattern::totalQuantity).toList();
        assertThat(q).isSorted();
        assertThat(q.get(0)).isEqualTo(2);
    }

    @Test
    void withinALevelFewerDistinctSkusComeFirst() {
        // 60000 in 3 units: 20000x3 (one SKU) before 10000+20000+30000-style mixes.
        Result r = PatternGenerator.generate(List.of(sku(1, 20_000), sku(2, 10_000), sku(3, 30_000)), 60_000, BIG);
        List<Pattern> level3 = r.patterns().stream().filter(p -> p.totalQuantity() == 3).toList();
        assertThat(level3.get(0).componentCount()).isEqualTo(1);
        assertThat(level3.stream().map(Pattern::componentCount).toList()).isSorted();
    }

    @Test
    void sameInputGivesIdenticalOutputAndHashes() {
        List<Sku> skus = List.of(sku(3, 20_000), sku(1, 20_000), sku(2, 10_000));
        Result a = PatternGenerator.generate(skus, 60_000, BIG);
        Result b = PatternGenerator.generate(skus.reversed(), 60_000, BIG);
        assertThat(a.patterns()).isEqualTo(b.patterns());
        assertThat(a.patterns()).extracting(Pattern::hash).doesNotHaveDuplicates();
    }

    @Test
    void hashIsRealSha256OfTheSortedComponents() {
        String h = PatternGenerator.hash(List.of(new Component(2, 1, 10_000), new Component(1, 2, 20_000)));
        assertThat(h).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(h).isEqualTo(PatternGenerator.hash(List.of(new Component(1, 2, 20_000), new Component(2, 1, 10_000))));
        assertThat(h).isNotEqualTo(PatternGenerator.hash(List.of(new Component(1, 1, 20_000), new Component(2, 1, 10_000))));
    }

    @Test
    void patternLimitCapsTheOutputAndSaysSo() {
        Result r = PatternGenerator.generate(List.of(sku(1, 1), sku(2, 2), sku(3, 3), sku(4, 5)), 40, new Params(25, 40, 100, 50_000_000));
        assertThat(r.patterns()).hasSize(25);
        assertThat(r.hitPatternLimit()).isTrue();
        assertThat(r.patterns().stream().mapToInt(Pattern::totalQuantity).min().getAsInt()).isLessThanOrEqualTo(10);
    }

    @Test
    void perSkuCapLimitsUnitsOfThatSku() {
        Result r = PatternGenerator.generate(List.of(new Sku(1, 20_000, 2), sku(2, 20_000)), 80_000, BIG);
        assertThat(r.patterns()).allSatisfy(p -> p.components().stream().filter(c -> c.skuId() == 1)
                .forEach(c -> assertThat(c.quantity()).isLessThanOrEqualTo(2)));
        assertThat(r.patterns()).hasSize(3); // sku1 x0/x1/x2, rest from sku2
    }

    @Test
    void anAmountNoCombinationReachesIsInfeasibleNotAnError() {
        assertThat(PatternGenerator.generate(List.of(sku(1, 20_000)), 10_000, BIG).infeasible()).isTrue();
        assertThat(PatternGenerator.generate(List.of(sku(1, 20_000), sku(2, 40_000)), 30_000, BIG).patterns()).isEmpty();
        assertThat(PatternGenerator.generate(List.of(), 20_000, BIG).infeasible()).isTrue();
        assertThat(PatternGenerator.generate(List.of(sku(1, 20_000)), 0, BIG).infeasible()).isTrue();
    }

    @Test
    void quantityLimitStopsSearchAndIsNotReportedAsInfeasibleWhenSomethingWasFound() {
        Result r = PatternGenerator.generate(List.of(sku(1, 10_000)), 50_000, new Params(100, 5, 100, 1_000_000));
        assertThat(r.patterns()).hasSize(1);
        Result tooFew = PatternGenerator.generate(List.of(sku(1, 10_000)), 50_000, new Params(100, 4, 100, 1_000_000));
        assertThat(tooFew.patterns()).isEmpty();
    }

    @Test
    void anExhaustedNodeBudgetIsReportedNotHidden() {
        Result r = PatternGenerator.generate(List.of(sku(1, 3), sku(2, 5), sku(3, 7), sku(4, 11)), 997, new Params(10_000, 100, 100, 50));
        assertThat(r.budgetExhausted()).isTrue();
        assertThat(r.infeasible()).isFalse();
    }

    @Test
    void extraQuantityIsRelativeToTheFewestAchievable() {
        List<Sku> skus = List.of(sku(1, 10_000), sku(2, 20_000), sku(3, 30_000));
        // 60000 needs at least 2 units (2 x 30000); with +1 extra, patterns of 2 and 3 units only (4-unit ones exist too).
        Result r = PatternGenerator.generate(skus, 60_000, new Params(10_000, 40, 1, 50_000_000));
        assertThat(r.patterns().stream().mapToInt(Pattern::totalQuantity).min().getAsInt()).isEqualTo(2);
        assertThat(r.patterns().stream().mapToInt(Pattern::totalQuantity).max().getAsInt()).isEqualTo(3);
    }

    @Test
    void aLargeAmountIsStillServableBecauseTheLimitIsRelative() {
        // 10,000,000 needs 20 units of the largest SKU. An absolute cap of 12 would make it unservable; the relative
        // window anchors on the fewest achievable (20) instead.
        Result r = PatternGenerator.generate(List.of(sku(1, 500_000), sku(2, 100_000)), 10_000_000, new Params(100, 40, 2, 5_000_000));
        assertThat(r.patterns()).isNotEmpty();
        assertThat(r.patterns().get(0).totalQuantity()).isEqualTo(20);
        assertThat(r.patterns()).allSatisfy(p -> assertThat(sum(p)).isEqualTo(10_000_000));
    }

    @Test
    void theHardCeilingStillWins() {
        Result r = PatternGenerator.generate(List.of(sku(1, 500_000)), 10_000_000, new Params(100, 12, 100, 5_000_000));
        assertThat(r.patterns()).isEmpty();
    }
}
