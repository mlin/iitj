package net.mlin.iitj;

import java.util.ArrayList;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/**
 * Data structure storing [short begin, short end) intervals and answering requests for those
 * overlapping a query interval. Each stored interval is associated with an integer equal to the
 * order in which it was added (zero-based).
 *
 * <p>The index is memory-efficient and serializable, but read-only once built.
 */
public class ShortIntervalTree implements java.io.Serializable {

    /** Builder storing items to be stored in a ShortIntervalTree */
    public static class Builder {
        private int n;
        private short[] begs, ends;
        private boolean sorted;

        public Builder() {
            reset();
        }

        /**
         * Add one [beg, end) interval to be stored. The positions are "half-open" such that two
         * intervals with coincident end and begin positions are abutting, but not overlapping. The
         * same interval may be stored multiple times. Adding the intervals in sorted order, by
         * begin position then end position, will save time and space (but isn't required).
         *
         * @param beg interval begin position (inclusive)
         * @param end interval end position (exclusive)
         * @return An ID for the added interval, equal to the number of intervals added before this
         *     one.
         */
        public int add(short beg, short end) {
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
         * Build the ShortIntervalTree from previously stored intervals. After this the Builder
         * object is reset to an empty state.
         */
        public ShortIntervalTree build() {
            return new ShortIntervalTree(this);
        }

        private void reset() {
            n = 0;
            begs = new short[16];
            ends = new short[begs.length];
            sorted = true;
        }

        private void grow() {
            long capacity = begs.length;
            if (capacity == Integer.MAX_VALUE) {
                throw new UnsupportedOperationException("ShortIntervalTree capacity overflow");
            }
            assert ends.length == capacity;
            capacity = (capacity * 3L) / 2L;
            if (capacity > Integer.MAX_VALUE) {
                capacity = Integer.MAX_VALUE;
            }
            short[] tmp = new short[(int) capacity];
            for (int i = 0; i < n; i++) {
                tmp[i] = begs[i];
            }
            begs = tmp;
            tmp = new short[(int) capacity];
            for (int i = 0; i < n; i++) {
                tmp[i] = ends[i];
            }
            ends = tmp;
        }
    }

    private final short[] begs, ends, maxEnds;
    private final int[] indexNodes, permute;

    private ShortIntervalTree(Builder builder) {
        final int n = builder.n;
        begs = new short[n];
        ends = new short[n];
        maxEnds = new short[n];

        // compute sorting permutation of builder intervals, if needed, then copy the data in
        // https://stackoverflow.com/a/25778783
        if (!builder.isSorted()) {
            permute =
                    java.util.stream.IntStream.range(0, builder.n)
                            .mapToObj(i -> Integer.valueOf(i))
                            .sorted(
                                    (i1, i2) -> {
                                        int c = Short.compare(builder.begs[i1], builder.begs[i2]);
                                        if (c != 0) {
                                            return c;
                                        }
                                        return Short.compare(builder.ends[i1], builder.ends[i2]);
                                    })
                            .mapToInt(value -> value.intValue())
                            .toArray();

            for (int i = 0; i < builder.n; i++) {
                begs[i] = builder.begs[permute[i]];
                ends[i] = builder.ends[permute[i]];
            }
        } else {
            for (int i = 0; i < builder.n; i++) {
                begs[i] = builder.begs[i];
                ends[i] = builder.ends[i];
            }
            permute = null;
        }
        builder.reset();

        // compute index nodes
        int[] indexNodesTmp = new int[31];
        indexNodesTmp[0] = 0;
        int nIndexNodes = 1;

        int nRem = n;
        while (nRem > 0) {
            int p2 = Integer.highestOneBit(nRem);
            assert p2 > 0;
            indexNodesTmp[nIndexNodes] = p2 + indexNodesTmp[nIndexNodes - 1];
            nIndexNodes++;
            nRem &= ~p2;
        }
        assert indexNodesTmp[nIndexNodes - 1] == n;
        indexNodes = new int[nIndexNodes];
        for (int i = 0; i < nIndexNodes; i++) {
            indexNodes[i] = indexNodesTmp[i];
        }

        // compute maxEnds
        for (int which_i = 0; which_i < indexNodes.length - 1; which_i++) {
            int i = indexNodes[which_i];
            int n_i = indexNodes[which_i + 1] - i;
            assert n_i > 0 && Integer.bitCount(n_i) == 1;
            if (n_i == 1) {
                maxEnds[i] = ends[i];
            } else {
                int root = rootNode(n_i - 1);
                assert nodeLevel(root) == rootLevel(n_i - 1);
                recurseMaxEnds(i + 1, root, nodeLevel(root));
                maxEnds[i] = max(ends[i], maxEnds[i + 1 + root]);
            }
        }
    }

    public void validate() {
        int n = begs.length;
        assert ends.length == n;
        assert maxEnds.length == n;
        assert permute == null || permute.length == n;

        for (int i = 0; i < n; i++) {
            assert ends[i] >= begs[i];
            if (i > 0) {
                if (begs[i] == begs[i - 1]) {
                    assert ends[i] >= ends[i - 1];
                } else {
                    assert begs[i] > begs[i - 1];
                }
            }
            assert maxEnds[i] >= ends[i]
                    : String.valueOf(maxEnds[i])
                            + "<"
                            + String.valueOf(ends[i])
                            + "@"
                            + String.valueOf(i);
        }
    }

    /** @return Total number of intervals stored. */
    public int size() {
        return begs.length;
    }

    /** Result from a query, an interval and its ID as returned by Builder.add() */
    public static class QueryResult {
        public final short beg;
        public final short end;
        public final int id;

        public QueryResult(short beg, short end, int id) {
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
     * @param queryEnd Query interval end position (exclusive). A stored interval whose begin
     *     position equals the query end position is considered abutting, but NOT overlapping the
     *     query, so would not be returned.
     * @param callback Function to be called for each query result. The function may return true to
     *     continue the query, or false to stop immediately.
     */
    public void queryOverlap(short queryBeg, short queryEnd, Predicate<QueryResult> callback) {
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
     * @param queryEnd Query interval end position (exclusive). A stored interval whose begin
     *     position equals the query end position is considered abutting, but NOT overlapping the
     *     query, so would not be returned.
     * @return Materialized list of all the results.
     */
    public java.util.List<QueryResult> queryOverlap(short queryBeg, short queryEnd) {
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
     * only need the ID of each result (as returned by Builder.add()).
     *
     * @param queryBeg Query interval begin position (inclusive)
     * @param queryEnd Query interval end position (exclusive). A stored interval whose begin
     *     position equals the query end position is considered abutting, but NOT overlapping the
     *     query, so would not be returned.
     * @param callback Function to be called for each query result ID. The function may return true
     *     to continue the query, or false to stop immediately.
     */
    public void queryOverlapId(short queryBeg, short queryEnd, IntPredicate callback) {
        queryOverlapInternal(
                queryBeg, queryEnd, i -> callback.test(permute != null ? permute[i] : i));
    }

    /**
     * Query for any one stored interval overlapping the given interval.
     *
     * @param queryBeg Query interval begin position (inclusive)
     * @param queryEnd Query interval end position (exclusive). A stored interval whose begin
     *     position equals the query end position is considered abutting, but NOT overlapping the
     *     query, so would not be returned.
     * @return null if there are no overlapping intervals stored.
     */
    public QueryResult queryAnyOverlap(short queryBeg, short queryEnd) {
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
     * Return any one ID of a stored interval overlapping the given interval.
     *
     * @param queryBeg Query interval begin position (inclusive)
     * @param queryEnd Query interval end position (exclusive). A stored interval whose begin
     *     position equals the query end position is considered abutting, but NOT overlapping the
     *     query, so would not be returned.
     * @return -1 if there are no overlapping intervals stored.
     */
    public int queryAnyOverlapId(short queryBeg, short queryEnd) {
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
     * @param queryEnd Query interval end position (exclusive). A stored interval whose begin
     *     position equals the query end position is considered abutting, but NOT overlapping the
     *     query, so would not qualify.
     */
    public boolean queryOverlapExists(short queryBeg, short queryEnd) {
        return queryAnyOverlapId(queryBeg, queryEnd) >= 0;
    }

    /**
     * Query for all stored intervals exactly equalling the given interval.
     *
     * @param callback @see queryOverlapId
     */
    public void queryExactId(short queryBeg, short queryEnd, IntPredicate callback) {
        // TODO: replace with simple binary search in the beg-sorted array
        queryOverlapInternal(
                queryBeg,
                queryEnd,
                i -> {
                    if (begs[i] == queryBeg && ends[i] == queryEnd) {
                        return callback.test(permute != null ? permute[i] : i);
                    }
                    return true;
                });
    }

    /**
     * Query for any one ID of a stored interval exactly equalling the given interval.
     *
     * @return -1 if there are no equal intervals stored.
     */
    public int queryAnyExactId(short queryBeg, short queryEnd) {
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
    public boolean queryExactExists(short queryBeg, short queryEnd) {
        return queryAnyExactId(queryBeg, queryEnd) >= 0;
    }

    /**
     * Query for all stored intervals
     *
     * @param callback @see queryOverlap
     */
    public void queryAll(Predicate<QueryResult> callback) {
        for (int i = 0; i < begs.length; i++) {
            if (!callback.test(
                    new QueryResult(begs[i], ends[i], permute != null ? permute[i] : i))) {
                return;
            }
        }
    }

    private void recurseMaxEnds(int ofs, int node, int lvl) {
        // TODO: should be faster to replace recursion with stepping through levels, as in cgranges
        Short maxEnd = ends[ofs + node];
        if (lvl > 0) {
            int ch = nodeLeftChild(node, lvl);
            assert ch >= 0 && ch < node;
            recurseMaxEnds(ofs, ch, lvl - 1);
            maxEnd = max(maxEnd, maxEnds[ofs + ch]);
            ch = nodeRightChild(node, lvl);
            assert ch > node && ch < begs.length;
            recurseMaxEnds(ofs, ch, lvl - 1);
            maxEnd = max(maxEnd, maxEnds[ofs + ch]);
        }
        maxEnds[ofs + node] = maxEnd;
    }

    private void queryOverlapInternal(short queryBeg, short queryEnd, IntPredicate callback) {
        for (int which_i = 0; which_i < indexNodes.length - 1; which_i++) {
            int i = indexNodes[which_i];
            if (begs[i] >= queryEnd) {
                break;
            } else if (maxEnds[i] > queryBeg) {
                if (ends[i] > queryBeg) {
                    if (!callback.test(i)) {
                        return;
                    }
                }
                int n_i = indexNodes[which_i + 1] - i;
                if (n_i > 1) {
                    int root = rootNode(n_i - 1);
                    recurseQuery(queryBeg, queryEnd, i + 1, root, nodeLevel(root), callback);
                }
            }
        }
    }

    private boolean recurseQuery(
            short queryBeg, short queryEnd, int ofs, int node, int lvl, IntPredicate callback) {
        // TODO: unroll traversal of bottom few levels
        int i = ofs + node;
        if (maxEnds[i] > queryBeg) {
            if (lvl > 0) {
                if (!recurseQuery(
                        queryBeg, queryEnd, ofs, nodeLeftChild(node, lvl), lvl - 1, callback)) {
                    return false;
                }
            }
            if (begs[i] < queryEnd) {
                if (ends[i] > queryBeg) {
                    if (!callback.test(i)) {
                        return false;
                    }
                }
                if (lvl > 0) {
                    if (!recurseQuery(
                            queryBeg,
                            queryEnd,
                            ofs,
                            nodeRightChild(node, lvl),
                            lvl - 1,
                            callback)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

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

    private static int nodeRightChild(int node, int lvl) {
        return node + (1 << (lvl - 1));
    }

    private static short max(short lhs, short rhs) {
        // avoiding Short.max b/c Short.max does not exist for some reason
        return lhs >= rhs ? lhs : rhs;
    }
}
