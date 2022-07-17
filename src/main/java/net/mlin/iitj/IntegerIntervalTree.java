package net.mlin.iitj;

import java.util.ArrayList;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/**
 * Data structure storing [int begin, int end) intervals and answering requests for those
 * overlapping a query interval. Each stored interval is associated with an integer equal to the
 * order in which it was added (zero-based).
 *
 * <p>The index is memory-efficient and serializable, but read-only once built.
 */
public class IntegerIntervalTree implements java.io.Serializable {

    /** Builder storing items to be stored in a IntegerIntervalTree */
    public static class Builder {
        private int n;
        private int[] begs, ends;
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
        public int add(int beg, int end) {
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
         * Build the IntegerIntervalTree from previously stored intervals. After this the Builder
         * object is reset to an empty state.
         */
        public IntegerIntervalTree build() {
            return new IntegerIntervalTree(this);
        }

        private void reset() {
            n = 0;
            begs = new int[16];
            ends = new int[begs.length];
            sorted = true;
        }

        private void grow() {
            long capacity = begs.length;
            if (capacity == Integer.MAX_VALUE) {
                throw new UnsupportedOperationException("IntegerIntervalTree capacity overflow");
            }
            assert ends.length == capacity;
            capacity = (capacity * 3L) / 2L;
            if (capacity > Integer.MAX_VALUE) {
                capacity = Integer.MAX_VALUE;
            }
            int[] tmp = new int[(int) capacity];
            for (int i = 0; i < n; i++) {
                tmp[i] = begs[i];
            }
            begs = tmp;
            tmp = new int[(int) capacity];
            for (int i = 0; i < n; i++) {
                tmp[i] = ends[i];
            }
            ends = tmp;
        }
    }

    // We store the interval begin positions, end positions, and augmentation values (maxEnds) in
    // separate arrays to keep them unboxed. These arrays each correspond to the intervals sorted
    // by begin position then by end position.
    private final int[] begs, ends, maxEnds;
    // If the intervals weren't originally provided to the builder in the same sorted order, then
    // permute stores their original IDs. Otherwise permute is null and the IDs are the indexes
    // in the above sorted arrays.
    private final int[] permute;
    // We can view any N, the number of intervals stored, as a sum of powers of 2, and the above
    // arrays as a concatenation of slices, each one of these powers of 2 in length. The leftmost
    // item in each slice is the "index node", and the 2^p-1 remaining items (for some 0<=p<32)
    // are an implicit binary search tree as in Li's cgranges. These trees are full & complete by
    // construction, which avoids the need for workarounds cgranges has for when that's not so.
    //
    // indexNodes provides the array offsets for the index nodes in ascending order. The first
    // element is always zero and a last element equal to N is appended as a convenience.
    private final int[] indexNodes;

    private IntegerIntervalTree(Builder builder) {
        final int n = builder.n;
        begs = new int[n];
        ends = new int[n];
        maxEnds = new int[n];

        // compute sorting permutation of builder intervals, if needed, then copy the data in
        // https://stackoverflow.com/a/25778783
        if (!builder.isSorted()) {
            permute =
                    java.util.stream.IntStream.range(0, builder.n)
                            .mapToObj(i -> Integer.valueOf(i))
                            .sorted(
                                    (i1, i2) -> {
                                        int c = Integer.compare(builder.begs[i1], builder.begs[i2]);
                                        if (c != 0) {
                                            return c;
                                        }
                                        return Integer.compare(builder.ends[i1], builder.ends[i2]);
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
        while (nRem > 0) { // for each binary one digit in N
            int p2 = Integer.highestOneBit(nRem);
            assert p2 > 0 && Integer.bitCount(p2) == 1;
            indexNodesTmp[nIndexNodes] = p2 + indexNodesTmp[nIndexNodes - 1];
            nIndexNodes++;
            nRem &= ~p2;
        }
        indexNodes = new int[nIndexNodes];
        for (int i = 0; i < nIndexNodes; i++) {
            indexNodes[i] = indexNodesTmp[i];
        }
        assert indexNodes[nIndexNodes - 1] == n;

        // Compute maxEnds througout each implict tree; for the index nodes themselves; the maxEnd
        // is the greater of its own end position and the maxEnd of the subsequent tree.
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
        public final int beg;
        public final int end;
        public final int id;

        public QueryResult(int beg, int end, int id) {
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
    public void queryOverlap(int queryBeg, int queryEnd, Predicate<QueryResult> callback) {
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
    public java.util.List<QueryResult> queryOverlap(int queryBeg, int queryEnd) {
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
    public void queryOverlapId(int queryBeg, int queryEnd, IntPredicate callback) {
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
    public QueryResult queryAnyOverlap(int queryBeg, int queryEnd) {
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
    public int queryAnyOverlapId(int queryBeg, int queryEnd) {
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
    public boolean queryOverlapExists(int queryBeg, int queryEnd) {
        return queryAnyOverlapId(queryBeg, queryEnd) >= 0;
    }

    /**
     * Query for all stored intervals exactly equalling the given interval.
     *
     * @param callback @see queryOverlapId
     */
    public void queryExactId(int queryBeg, int queryEnd, IntPredicate callback) {
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
    public int queryAnyExactId(int queryBeg, int queryEnd) {
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
    public boolean queryExactExists(int queryBeg, int queryEnd) {
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
        // compute interval tree augmentation values for the subtree rooted at (ofs+node)
        // TODO: should be faster to replace recursion with stepping through levels, as in cgranges
        Integer maxEnd = ends[ofs + node];
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

    private void queryOverlapInternal(int queryBeg, int queryEnd, IntPredicate callback) {
        // for each index node
        for (int which_i = 0; which_i < indexNodes.length - 1; which_i++) {
            int i = indexNodes[which_i];
            if (begs[i] >= queryEnd) {
                break; // whole remainder of the beg-sorted array must be irrelevant
            } else if (maxEnds[i] > queryBeg) { // slice has relevant item(s)
                if (ends[i] > queryBeg) { // index node is a hit itself, return it first
                    if (!callback.test(i)) {
                        return;
                    }
                }
                // search the adjacent tree, formed by the slice from (i+1) until the next index
                // node. The root is in the middle at an offset calculable from the tree size (=
                // slice length).
                int n_i = indexNodes[which_i + 1] - i; // n_i is a power of two
                if (n_i > 1) {
                    int root = rootNode(n_i - 1);
                    recurseQuery(queryBeg, queryEnd, i + 1, root, nodeLevel(root), callback);
                }
            }
        }
    }

    private boolean recurseQuery(
            int queryBeg, int queryEnd, int ofs, int node, int lvl, IntPredicate callback) {
        // TODO: unroll traversal of bottom few levels
        int i = ofs + node;
        if (maxEnds[i] > queryBeg) { // subtree rooted here may have relevant item(s)
            if (lvl > 0) { // search left subtree
                if (!recurseQuery(
                        queryBeg, queryEnd, ofs, nodeLeftChild(node, lvl), lvl - 1, callback)) {
                    return false;
                }
            }
            if (begs[i] < queryEnd) { // root or right subtree may include relevant item(s)
                if (ends[i] > queryBeg) { // current root overlaps
                    if (!callback.test(i)) {
                        return false;
                    }
                }
                if (lvl > 0) { // search right subtree
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

    private static int nodeRightChild(int node, int lvl) {
        return node + (1 << (lvl - 1));
    }

    private static int max(int lhs, int rhs) {
        // avoiding Integer.max b/c Short.max does not exist for some reason
        return lhs >= rhs ? lhs : rhs;
    }
}
