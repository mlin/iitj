// IMPORTANT: Doub1eIntervalTree.java serves as our original source code and we derive
// {Float,Integer,Long,Short}IntervalTree.java from it using the generate.sh script. We take this
// approach instead of Java generics in order to use the unboxed primitive number types wherever
// possible.
package net.mlin.iitj;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/**
 * Data structure storing [double begin, double end) intervals and answering requests for those
 * overlapping a query interval. Each stored interval is associated with an integer equal to the
 * order in which it was added (zero-based). The index is compact in memory and serializes
 * efficiently, but it's read-only once built.
 */
public class DoubleIntervalTree implements java.io.Serializable {

    /** Builder storing items to be indexed in a DoubleIntervalTree */
    public static class Builder {
        private int n;
        private final int initialCapacity;
        private double[] begs, ends;
        private boolean sorted;

        public Builder(int initialCapacity) {
            if (initialCapacity <= 0) {
                throw new IllegalArgumentException();
            }
            this.initialCapacity = initialCapacity;
            reset();
        }

        public Builder() {
            this(16);
        }

        /**
         * Add one [beg,end) interval to be stored. The positions are "half-open" such that an
         * interval [x,y) abuts but does not overlap [w,x) and [y,z). The same interval may be
         * stored multiple times. Adding the intervals in sorted order, by begin position then end
         * position, will save time and space (but isn't required).
         *
         * @param beg interval begin position (inclusive)
         * @param end interval end position (exclusive)
         * @return An ID for the added interval, equal to the number of intervals added before this
         *     one.
         */
        public int add(double beg, double end) {
            if (beg > end) {
                throw new IllegalArgumentException();
            }
            if (n == begs.length) {
                grow();
                assert n < begs.length;
            }
            begs[n] = beg;
            ends[n] = end;
            if (sorted
                    && n > 0
                    && (begs[n] < begs[n - 1]
                            || (begs[n] == begs[n - 1] && ends[n] < ends[n - 1]))) {
                sorted = false;
            }
            return n++;
        }

        /**
         * Return true iff intervals have so far been added in sorted order, by begin position then
         * end position. This isn't required, but improves time and space needs.
         */
        public boolean isSorted() {
            return sorted;
        }

        /**
         * Build the DoubleIntervalTree from previously stored intervals. After this the Builder
         * object is reset to an empty state.
         */
        public DoubleIntervalTree build() {
            return new DoubleIntervalTree(this);
        }

        private void reset() {
            n = 0;
            begs = new double[initialCapacity];
            ends = new double[initialCapacity];
            sorted = true;
        }

        private void grow() {
            long capacity = begs.length;
            if (capacity == Integer.MAX_VALUE) {
                throw new UnsupportedOperationException("DoubleIntervalTree capacity overflow");
            }
            assert ends.length == capacity;
            capacity = (capacity * 3L) / 2L + 1L;
            if (capacity > Integer.MAX_VALUE) {
                capacity = Integer.MAX_VALUE;
            }
            begs = Arrays.copyOf(begs, (int) capacity);
            ends = Arrays.copyOf(ends, (int) capacity);
        }
    }

    // We store the N interval begin & end positions in separate arrays to keep them unboxed. The
    // intervals represented by the two arrays are sorted by begin position, then by end position.
    // Note: we've explored transposing these into a single row-major array to improve cache
    //       locality, but it (i) complicates the code and (ii) halves the effective maximum N due
    //       to the maximum Java array size. (commit cca404ab)
    private final double[] begs, ends;
    // If the intervals weren't originally provided to the builder in the same sorted order, then
    // permute stores their original IDs. Otherwise permute is null and the IDs are the indexes
    // in the above sorted arrays.
    private final int[] permute;
    // Write N as a sum of powers of two, e.g. N = 12345 = 8192 + 4096 + 32 + 16 + 8 + 1, and
    // consider the corresponding slices of the interval array. The leftmost item in each slice is
    // an "index node", and the 2^p-1 remaining items (for some 0<=p<32) are an implicit binary
    // search tree as in Li's cgranges. Our trees are "perfect" by construction, avoiding some
    // complications cgranges handles when that's not so.
    // indexNodes stores the array positions of the index nodes, in ascending order. The first
    // element is always zero and a last element equal to N is appended as a convenience. The
    // difference between any two adjacent elements is one of the powers of two.
    private final int[] indexNodes;
    // Interval tree augmentation values (maxEnds): our search procedure uses the textbook interval
    // tree recursion above level 2, and upon reaching a level 2 or below subtree (<= 7 items) it
    // just scans them. Therefore, we only need to store maxEnds for levels 2 and above (25% of
    // nodes). We keep an array per index node; for each index node, the first element is maxEnd of
    // the whole tree & index node, followed by the maxEnd for every fourth node in the tree.
    private final double[][] maxEnds;

    private DoubleIntervalTree(Builder builder) {
        final int n = builder.n;

        // compute sorting permutation of builder intervals, if needed, then copy the data in
        // https://stackoverflow.com/a/25778783
        if (!builder.isSorted()) {
            permute =
                    java.util.stream.IntStream.range(0, builder.n)
                            .mapToObj(i -> Integer.valueOf(i))
                            .sorted(
                                    (i1, i2) -> {
                                        int c = Double.compare(builder.begs[i1], builder.begs[i2]);
                                        if (c != 0) {
                                            return c;
                                        }
                                        return Double.compare(builder.ends[i1], builder.ends[i2]);
                                    })
                            .mapToInt(value -> value.intValue())
                            .toArray();

            begs = new double[n];
            ends = new double[n];
            for (int i = 0; i < builder.n; i++) {
                begs[i] = builder.begs[permute[i]];
                ends[i] = builder.ends[permute[i]];
            }
        } else if (builder.n != builder.begs.length) {
            begs = Arrays.copyOf(builder.begs, builder.n);
            ends = Arrays.copyOf(builder.ends, builder.n);
            permute = null;
        } else {
            begs = builder.begs;
            ends = builder.ends;
            permute = null;
        }
        builder.reset();

        // compute index nodes
        int nIndexNodes = Integer.bitCount(n) + 1;
        indexNodes = new int[nIndexNodes];
        indexNodes[0] = 0;
        int nRem = n;
        for (int i = 1; i < nIndexNodes; i++) {
            int p2 = Integer.highestOneBit(nRem);
            assert Integer.bitCount(p2) == 1;
            indexNodes[i] = p2 + indexNodes[i - 1];
            nRem &= ~p2;
        }
        assert nRem == 0 && indexNodes[nIndexNodes - 1] == n;

        // Compute maxEnds througout each implict tree; for the index nodes themselves, the maxEnd
        // is the greater of its own end position and the maxEnd of the subsequent tree.
        maxEnds = new double[nIndexNodes][0];
        for (int which_i = 0; which_i < indexNodes.length - 1; which_i++) {
            int i = indexNodes[which_i];
            int n_i = indexNodes[which_i + 1] - i;
            assert Integer.bitCount(n_i) == 1;
            double[] maxEnd = new double[1 + (n_i - 1) / 4];
            maxEnd[0] = ends[i];
            if (n_i > 1) {
                int root = rootNode(n_i - 1);
                assert nodeLevel(root) == rootLevel(n_i - 1);
                recurseMaxEnds(maxEnd, i + 1, root, nodeLevel(root));
                if (root >= 3) {
                    assert n_i >= 8;
                    maxEnd[0] = max(ends[i], maxEnd[root / 4 + 1]);
                } else {
                    for (int j = i + 1; j < i + n_i; ++j) {
                        maxEnd[0] = max(maxEnd[0], ends[j]);
                    }
                }
            }
            maxEnds[which_i] = maxEnd;
        }
    }

    /** @hidden */
    public void validate() {
        int n = begs.length;
        assert ends.length == n;
        assert maxEnds.length == indexNodes.length;
        assert permute == null || permute.length == n;

        int m = 0;
        for (int which_i = 0; which_i < indexNodes.length - 1; which_i++) {
            int i = indexNodes[which_i];
            int n_i = indexNodes[which_i + 1] - i;
            double[] maxEnd = maxEnds[which_i];
            assert maxEnd.length == 1 + (n_i - 1) / 4;
            for (int j = i; j < i + n_i; ++j, ++m) {
                assert ends[j] >= begs[j];
                if (j > 0) {
                    if (begs[j] == begs[j - 1]) {
                        assert ends[j] >= ends[j - 1];
                    } else {
                        assert begs[j] > begs[j - 1];
                    }
                }
                assert ends[j] <= maxEnd[0];
                if (j - i > 0 && (j - i) % 4 == 0) {
                    assert ends[j] <= maxEnd[(j - i) / 4];
                }
            }
        }

        assert n == m;
    }

    /** @return Total number of intervals stored. */
    public int size() {
        return begs.length;
    }

    /**
     * Result from a query, an interval and its ID as returned by {@link Builder#add Builder.add()}
     */
    public static class QueryResult {
        public final double beg;
        public final double end;
        public final int id;

        public QueryResult(double beg, double end, int id) {
            this.beg = beg;
            this.end = end;
            this.id = id;
        }

        public String toString() {
            // for debugging
            return "["
                    + String.valueOf(beg)
                    + ","
                    + String.valueOf(end)
                    + ")="
                    + String.valueOf(id);
        }

        @Override
        public boolean equals(Object rhso) {
            if (rhso instanceof QueryResult) {
                QueryResult rhs = (QueryResult) rhso;
                return beg == rhs.beg && end == rhs.end && id == rhs.id;
            }
            return false;
        }
    }

    /**
     * Query for all stored intervals overlapping the given interval.
     *
     * @param queryBeg Query interval begin position (inclusive)
     * @param queryEnd Query interval end position (exclusive). Given a query interval [x,y), stored
     *     intervals [w,x) and [y,z) are abutting, but NOT overlapping the query, so would not be
     *     returned.
     * @param callback Predicate function to be called with each query result; it may return true to
     *     continue the query, or false to stop immediately.
     */
    public void queryOverlap(double queryBeg, double queryEnd, Predicate<QueryResult> callback) {
        queryOverlapInternal(
                queryBeg,
                queryEnd,
                i ->
                        callback.test(
                                new QueryResult(
                                        begs[i], ends[i], permute != null ? permute[i] : i)));
    }

    /**
     * Query for all stored intervals overlapping the given interval.
     *
     * @param queryBeg Query interval begin position (inclusive)
     * @param queryEnd Query interval end position (exclusive). Given a query interval [x,y), stored
     *     intervals [w,x) and [y,z) are abutting, but NOT overlapping the query, so would not be
     *     returned.
     * @return Materialized list of {@link QueryResult QueryResult} (not preferred for large result
     *     sets)
     */
    public java.util.List<QueryResult> queryOverlap(double queryBeg, double queryEnd) {
        ArrayList<QueryResult> results = new ArrayList<QueryResult>();
        queryOverlap(
                queryBeg,
                queryEnd,
                x -> {
                    results.add(x);
                    return true;
                });
        return results;
    }

    /**
     * Query for all stored intervals overlapping the given interval, optimized for callers that
     * only need the ID of each result (as generated by {@link Builder#add Builder.add()}. Overhead
     * is reduced by avoiding allocation of {@link QueryResult QueryResult} objects.
     *
     * @param queryBeg Query interval begin position (inclusive)
     * @param queryEnd Query interval end position (exclusive). Given a query interval [x,y), stored
     *     intervals [w,x) and [y,z) are abutting, but NOT overlapping the query, so would not be
     *     returned.
     * @param callback Predicate function to be called with each query result ID; it may return true
     *     to continue the query, or false to stop immediately.
     */
    public void queryOverlapId(double queryBeg, double queryEnd, IntPredicate callback) {
        queryOverlapInternal(
                queryBeg, queryEnd, i -> callback.test(permute != null ? permute[i] : i));
    }

    /**
     * Query for any one stored interval overlapping the given interval.
     *
     * @param queryBeg Query interval begin position (inclusive)
     * @param queryEnd Query interval end position (exclusive). Given a query interval [x,y), stored
     *     intervals [w,x) and [y,z) are abutting, but NOT overlapping the query, so would not be
     *     returned.
     * @return null if there are no overlapping intervals stored.
     */
    public QueryResult queryAnyOverlap(double queryBeg, double queryEnd) {
        QueryResult[] box = {null};
        queryOverlap(
                queryBeg,
                queryEnd,
                x -> {
                    box[0] = x;
                    return false;
                });
        return box[0];
    }

    /**
     * Query for any one ID of a stored interval overlapping the given interval.
     *
     * @param queryBeg Query interval begin position (inclusive)
     * @param queryEnd Query interval end position (exclusive). Given a query interval [x,y), stored
     *     intervals [w,x) and [y,z) are abutting, but NOT overlapping the query, so would not be
     *     returned.
     * @return -1 if there are no overlapping intervals stored.
     */
    public int queryAnyOverlapId(double queryBeg, double queryEnd) {
        int[] box = {-1};
        queryOverlapId(
                queryBeg,
                queryEnd,
                i -> {
                    box[0] = i;
                    return false;
                });
        return box[0];
    }

    /**
     * Query whether there exists any stored interval overlapping the given interval.
     *
     * @param queryBeg Query interval begin position (inclusive)
     * @param queryEnd Query interval end position (exclusive). Given a query interval [x,y), stored
     *     intervals [w,x) and [y,z) are abutting, but NOT overlapping the query, so would not be
     *     returned.
     */
    public boolean queryOverlapExists(double queryBeg, double queryEnd) {
        return queryAnyOverlapId(queryBeg, queryEnd) >= 0;
    }

    /**
     * Query for IDs of all stored intervals exactly equalling the given interval.
     *
     * @param callback Predicate function to be called with each query result ID; it may return true
     *     to continue the query, or false to stop immediately.
     */
    public void queryExactId(double queryBeg, double queryEnd, IntPredicate callback) {
        int p = java.util.Arrays.binarySearch(begs, queryBeg);
        if (p >= 0) {
            for (; p > 0 && begs[p - 1] == queryBeg && ends[p - 1] >= queryEnd; --p)
                ;
            for (; p < begs.length && begs[p] == queryBeg && ends[p] <= queryEnd; ++p) {
                if (ends[p] == queryEnd && !callback.test(permute != null ? permute[p] : p)) {
                    return;
                }
            }
        }
    }

    /**
     * Query for any one ID of a stored interval exactly equalling the given interval.
     *
     * @return -1 if there are no equal intervals stored.
     */
    public int queryAnyExactId(double queryBeg, double queryEnd) {
        int[] box = {-1};
        queryExactId(
                queryBeg,
                queryEnd,
                i -> {
                    box[0] = i;
                    return false;
                });
        return box[0];
    }

    /** Query whether there exists any stored interval exactly equalling the given interval. */
    public boolean queryExactExists(double queryBeg, double queryEnd) {
        return queryAnyExactId(queryBeg, queryEnd) >= 0;
    }

    /**
     * Query for all stored intervals
     *
     * @param callback Predicate function to be called with each query result; it may return true to
     *     continue the query, or false to stop immediately.
     */
    public void queryAll(Predicate<QueryResult> callback) {
        for (int i = 0; i < begs.length; i++) {
            if (!callback.test(
                    new QueryResult(begs[i], ends[i], permute != null ? permute[i] : i))) {
                return;
            }
        }
    }

    private void recurseMaxEnds(double[] ans, int ofs, int node, int lvl) {
        // compute interval tree augmentation values for the subtree rooted at (ofs+node)
        if (lvl > 2) {
            assert node >= 7 && node % 4 == 3;
            Double maxEnd = ends[ofs + node];
            int ch = nodeLeftChild(node, lvl);
            // assert ch >= 0 && ch < node;
            assert ch >= 3 && ch < node && ch % 4 == 3;
            recurseMaxEnds(ans, ofs, ch, lvl - 1);
            maxEnd = max(maxEnd, ans[ch / 4 + 1]);
            ch = nodeRightChild(node, lvl);
            // assert ch > node && ch < begs.length;
            assert ch > node && ch % 4 == 3;
            recurseMaxEnds(ans, ofs, ch, lvl - 1);
            ans[node / 4 + 1] = max(maxEnd, ans[ch / 4 + 1]);
        } else if (lvl == 2) {
            assert node >= 3 && node % 4 == 3;
            final int scanL = ofs + nodeLeftmostChild(node, lvl);
            Double maxEnd = ends[scanL];
            final int scanR = ofs + nodeRightmostChild(node, lvl);
            for (int j = scanL + 1; j <= scanR; ++j) {
                maxEnd = max(maxEnd, ends[j]);
            }
            ans[node / 4 + 1] = maxEnd;
        }
    }

    private void queryOverlapInternal(double queryBeg, double queryEnd, IntPredicate callback) {
        // for each index node
        for (int which_i = 0; which_i < indexNodes.length - 1; which_i++) {
            int i = indexNodes[which_i];
            double[] maxEnd = maxEnds[which_i];
            if (begs[i] >= queryEnd) {
                break; // whole remainder of the beg-sorted array must be irrelevant
            } else if (maxEnd[0] > queryBeg) { // slice has relevant item(s)
                if (ends[i] > queryBeg) { // index node is a hit itself, return it first
                    if (!callback.test(i)) {
                        return;
                    }
                }
                // search the subsequent tree, formed by the slice from (i+1) until the next index
                // node. The root is in the middle at an offset calculable from the tree size (=
                // slice length).
                int n_i = indexNodes[which_i + 1] - i; // n_i is a power of two
                if (n_i > 1) {
                    int root = rootNode(n_i - 1);
                    recurseQuery(
                            queryBeg, queryEnd, maxEnd, i + 1, root, nodeLevel(root), callback);
                }
            }
        }
    }

    private boolean recurseQuery(
            double queryBeg,
            double queryEnd,
            double[] maxEnd,
            int ofs,
            int node,
            int lvl,
            IntPredicate callback) {
        if (lvl > 2) {
            if (maxEnd[node / 4 + 1] <= queryBeg) {
                return true;
            }
            if (!recurseQuery( // search left subtree
                    queryBeg, queryEnd, maxEnd, ofs, nodeLeftChild(node, lvl), lvl - 1, callback)) {
                return false;
            }
            final int i = ofs + node;
            if (begs[i] < queryEnd) { // root or right subtree may include relevant item(s)
                if (ends[i] > queryBeg) { // current root overlaps
                    if (!callback.test(i)) {
                        return false;
                    }
                }
                if (!recurseQuery( // search right subtree
                        queryBeg,
                        queryEnd,
                        maxEnd,
                        ofs,
                        nodeRightChild(node, lvl),
                        lvl - 1,
                        callback)) {
                    return false;
                }
            }
        } else if (lvl == 2 && maxEnd[node / 4 + 1] <= queryBeg) {
            return true;
        } else {
            // lvl <= 2: once we get down to a subtree of <= 7 items, just scan left-to-right.
            // this is unlikely to be appreciably slower than the recursion, and removes the
            // need to store the 75% of maxEnds on levels 0 and 1.
            final int scanL = ofs + nodeLeftmostChild(node, lvl);
            final int scanR = ofs + nodeRightmostChild(node, lvl);
            for (int j = scanL; j <= scanR && begs[j] < queryEnd; ++j) {
                if (ends[j] > queryBeg && !callback.test(j)) {
                    return false;
                }
            }
        }
        return true;
    }

    // cgranges node rank calculations

    private static int nodeLevel(int node) {
        return Integer.numberOfTrailingZeros(~node);
    }

    private static int rootLevel(int treeSize) {
        return 31 - Integer.numberOfLeadingZeros(treeSize); // floor(log2(treeSize))
    }

    private static int rootNode(int treeSize) {
        return (1 << rootLevel(treeSize)) - 1;
    }

    private static int nodeLeftChild(int node, int lvl) {
        return node - (1 << (lvl - 1));
    }

    private static int nodeLeftmostChild(int node, int lvl) {
        return node - (1 << lvl) + 1;
    }

    private static int nodeRightChild(int node, int lvl) {
        return node + (1 << (lvl - 1));
    }

    private static int nodeRightmostChild(int node, int lvl) {
        return node + (1 << lvl) - 1;
    }

    private static double max(double lhs, double rhs) {
        // avoiding Doub1e.max b/c Short.max does not exist for some reason
        return lhs >= rhs ? lhs : rhs;
    }
}
