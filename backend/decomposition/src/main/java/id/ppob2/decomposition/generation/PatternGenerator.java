package id.ppob2.decomposition.generation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * PRD Section 29's offline decomposition engine, as a pure function: SKUs and an amount in, patterns out. No
 * database, no clock, no randomness, so the same input always gives the same output, which is what Section 29.1
 * means by deterministic and reproducible.
 *
 * <p><b>Why Java and not Rust.</b> Section 29.1 says Rust is <em>preferred</em>, not required. The search space
 * here is bounded by a small quantity limit, and a pure function with this signature is what a Rust binary would
 * replace, should measured generation time ever demand it (see the scale figures in backend/README.md).
 *
 * <p><b>What it does.</b> Enumerates multisets of SKUs whose face values sum to exactly the amount, level by level
 * in increasing total quantity (fewest child-order units first), and stops at {@code maxPatterns}. Within a level,
 * patterns with fewer distinct SKUs come first, then by SKU id. A pruned depth-first search does the enumeration:
 * a branch is cut when the remaining amount cannot be reached with the remaining quantity using the SKUs still
 * available (bounded by the smallest and largest face value left) or when the amount is not a multiple of the
 * greatest common divisor of the face values. That is the DP-reachability and branch-and-bound of Section 29.2 in
 * the form that fits an amount-by-quantity search.
 *
 * <p><b>Structure only.</b> It does not look at prices. Prices change daily without any structural change
 * (Section 30.1), so cost, margin and ranking by value belong to the scorer. A per-SKU {@code cap} (the SKU's
 * daily quota) limits how many units of that SKU one pattern may use.
 *
 * <p><b>Bounded effort.</b> {@code nodeBudget} caps the search per amount. If it runs out the result says so
 * ({@code budgetExhausted}) rather than silently returning a quietly truncated set; it is deterministic, because the
 * node count is.
 */
public final class PatternGenerator {

    private PatternGenerator() {
    }

    public record Sku(long id, long faceValue, Integer cap) {
    }

    public record Component(long skuId, int quantity, long faceValue) {
    }

    public record Pattern(List<Component> components, int totalQuantity, String hash) {
        public int componentCount() {
            return components.size();
        }
    }

    /**
     * @param maxTotalQuantity hard ceiling on child-order units in one pattern
     * @param extraQuantity    how many units beyond the fewest achievable a pattern may use. Relative on purpose: a large
     *                         amount genuinely needs many units of the biggest denomination, so an absolute limit alone would
     *                         leave it unservable, while "the fewest plus a few" bounds fragmentation for every amount alike
     */
    public record Params(int maxPatterns, int maxTotalQuantity, int extraQuantity, long nodeBudget) {
    }

    public record Result(List<Pattern> patterns, boolean hitPatternLimit, boolean budgetExhausted, boolean infeasible) {
    }

    public static Result generate(List<Sku> skus, long amount, Params params) {
        List<Sku> sorted = skus.stream()
                .filter(s -> s.faceValue() > 0 && (s.cap() == null || s.cap() > 0))
                .sorted(Comparator.comparingLong(Sku::faceValue).reversed().thenComparingLong(Sku::id))
                .toList();
        if (amount <= 0 || sorted.isEmpty()) {
            return new Result(List.of(), false, false, true);
        }
        long g = 0;
        for (Sku s : sorted) {
            g = gcd(g, s.faceValue());
        }
        if (amount % g != 0) {
            return new Result(List.of(), false, false, true);
        }

        int n = sorted.size();
        long[] face = new long[n];
        int[] cap = new int[n];
        long[] sufMin = new long[n + 1];
        long[] sufMax = new long[n + 1];
        sufMin[n] = Long.MAX_VALUE;
        sufMax[n] = 0;
        for (int i = n - 1; i >= 0; i--) {
            face[i] = sorted.get(i).faceValue();
            cap[i] = sorted.get(i).cap() == null ? Integer.MAX_VALUE : sorted.get(i).cap();
            sufMin[i] = Math.min(sufMin[i + 1], face[i]);
            sufMax[i] = Math.max(sufMax[i + 1], face[i]);
        }

        List<Pattern> out = new ArrayList<>();
        boolean hitLimit = false;
        boolean exhausted = false;
        long[] nodes = {0};
        long minFace = sufMin[0];
        long maxFace = sufMax[0];
        int firstQuantity = (int) Math.max(1, (amount + maxFace - 1) / maxFace);
        int ceiling = params.maxTotalQuantity();
        for (int q = firstQuantity; q <= ceiling && q * minFace <= amount; q++) {
            List<int[]> level = new ArrayList<>();
            int[] chosen = new int[n];
            boolean ok = search(0, amount, q, face, cap, sufMin, sufMax, chosen, level, nodes, params.nodeBudget());
            if (!ok) {
                exhausted = true;
            }
            if (!level.isEmpty() && out.isEmpty()) {
                ceiling = (int) Math.min(ceiling, (long) q + params.extraQuantity());
            }
            level.sort(Comparator.<int[]>comparingInt(PatternGenerator::distinct).thenComparing(PatternGenerator::byIndex));
            for (int[] counts : level) {
                if (out.size() >= params.maxPatterns()) {
                    hitLimit = true;
                    break;
                }
                out.add(toPattern(sorted, counts, q));
            }
            if (hitLimit || exhausted) {
                break;
            }
        }
        // `infeasible` means no combination exists at all; hitting the budget before finding one is reported separately.
        return new Result(out, hitLimit, exhausted, out.isEmpty() && !exhausted);
    }

    /** @return false when the node budget ran out */
    private static boolean search(int i, long remaining, int qtyLeft, long[] face, int[] cap, long[] sufMin, long[] sufMax,
                                  int[] chosen, List<int[]> level, long[] nodes, long budget) {
        if (++nodes[0] > budget) {
            return false;
        }
        if (qtyLeft == 0) {
            if (remaining == 0) {
                level.add(chosen.clone());
            }
            return true;
        }
        if (i == face.length || remaining < qtyLeft * sufMin[i] || remaining > qtyLeft * sufMax[i]) {
            return true;
        }
        int most = (int) Math.min(Math.min(qtyLeft, cap[i]), remaining / face[i]);
        for (int c = most; c >= 0; c--) {
            chosen[i] = c;
            boolean ok = search(i + 1, remaining - c * face[i], qtyLeft - c, face, cap, sufMin, sufMax, chosen, level, nodes, budget);
            chosen[i] = 0;
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static int distinct(int[] counts) {
        int d = 0;
        for (int c : counts) {
            if (c > 0) {
                d++;
            }
        }
        return d;
    }

    private static int byIndex(int[] a, int[] b) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(b[i], a[i]);
            }
        }
        return 0;
    }

    private static Pattern toPattern(List<Sku> sorted, int[] counts, int totalQuantity) {
        List<Component> comps = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > 0) {
                comps.add(new Component(sorted.get(i).id(), counts[i], sorted.get(i).faceValue()));
            }
        }
        comps.sort(Comparator.comparingLong(Component::skuId));
        return new Pattern(List.copyOf(comps), totalQuantity, hash(comps));
    }

    /** SHA-256 over the components sorted by SKU id, so the same multiset always hashes the same (Section 29.4). */
    public static String hash(List<Component> components) {
        StringBuilder sb = new StringBuilder();
        components.stream().sorted(Comparator.comparingLong(Component::skuId)).forEach(
                c -> sb.append(c.skuId()).append(':').append(c.quantity()).append(':').append(c.faceValue()).append(';'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static long gcd(long a, long b) {
        return b == 0 ? a : gcd(b, a % b);
    }
}
