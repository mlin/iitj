// IMPORTANT: Doub1eIntervalTree.java serves as our original source code and we derive
// {Float,Integer,Long,Short}IntervalTree.java from it using the generate.sh script. We take this
// approach instead of Java generics in order to use the unboxed primitive number types wherever
// possible.
package net.mlin.iitj;

import java.util.ArrayList;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/**
 * Data structure storing [long begin, long end) intervals and answering requests for those
 * overlapping a query interval. Each stored interval is associated with an integer equal to the
 * order in which it was added (zero-based). The index is compact in memory and serializes
 * efficiently, but it's read-only once built.
 */
public class LongIntervalTree implements java.io.Serializable {

    /** Builder storing items to be indexed in a LongIntervalTree */
    public static class Builder {
        private int n;
        private long[] begs, ends;
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
        public int add(long beg, long end) {
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
         * Build the LongIntervalTree from previously stored intervals. After this the Builder
         * object is reset to an empty state.
         */
        public LongIntervalTree build() {
            return new LongIntervalTree(this);
        }

        private void reset() {
            n = 0;
            begs = new long[16];
            ends = new long[begs.length];
            sorted = true;
        }

        private void grow() {
            long capacity = begs.length;
            if (capacity == Integer.MAX_VALUE) {
                throw new UnsupportedOperationException("LongIntervalTree capacity overflow");
            }
            assert ends.length == capacity;
            capacity = (capacity * 3L) / 2L;
            if (capacity > Integer.MAX_VALUE) {
                capacity = Integer.MAX_VALUE;
            }
            long[] tmp = new long[(int) capacity];
            for (int i = 0; i < n; i++) {
                tmp[i] = begs[i];
            }
            begs = tmp;
            tmp = new long[(int) capacity];
            for (int i = 0; i < n; i++) {
                tmp[i] = ends[i];
            }
            ends = tmp;
        }
    }

    // Number of intervals stored
    private final int N;
    // We store the interval begin positions, end positions, and augmentation values (maxEnds) in
    // a primitive array, keeping them compacted in memory in a cache-friendly way. We use an
    // N-by-3 matrix but since Java doesn't have true multidimensional arrays, we tediously write
    // out all the offset arithmetic to navigate the N*3-length all array. The 'rows' are sorted
    // by begin position, then by end position.
    private final long[] all; // N*3 row-major
    // These constants make the offset arithmetic code a little more readable.
    private static final int C = 3;
    private static final int BEG = 0;
    private static final int END = 1;
    private static final int MAX_END = 2;
    // Write N as a sum of powers of two, e.g. N = 12345 = 8192 + 4096 + 32 + 16 + 8 + 1, and
    // consider the corresponding slices of the interval array. The leftmost item in each slice is
    // an "index node", and the 2^p-1 remaining items (for some 0<=p<32) are an implicit binary
    // search tree as in Li's cgranges. Our trees are full & complete by construction, avoiding
    // some complications cgranges handles when that's not so.
    // indexNodes stores the row numbers of the index nodes, in ascending order. The first element
    // is always zero and a last element equal to N is appended as a convenience. The difference
    // between any two adjacent elements is one of the powers of two.
    private final int[] indexNodes;
    // If the intervals weren't originally provided to the builder in sorted order, then permute
    // stores their IDs corresponding to their insertion order. Otherwise permute is null and the
    // IDs are the row numbers in all.
    private final int[] permute;

    private LongIntervalTree(Builder builder) {
        N = builder.n;
        all = new long[C * N];

        // compute sorting permutation of builder intervals, if needed, then copy the data in
        if (!builder.isSorted()) {
            permute = // https://stackoverflow.com/a/25778783
                    java.util.stream.IntStream.range(0, builder.n)
                            .mapToObj(i -> Integer.valueOf(i))
                            .sorted(
                                    (i1, i2) -> {
                                        int c = Long.compare(builder.begs[i1], builder.begs[i2]);
                                        if (c != 0) {
                                            return c;
                                        }
                                        return Long.compare(builder.ends[i1], builder.ends[i2]);
                                    })
                            .mapToInt(value -> value.intValue())
                            .toArray();

            for (int i = 0; i < builder.n; i++) {
                final int Ci = C * i;
                all[Ci + BEG] = builder.begs[permute[i]];
                all[Ci + END] = builder.ends[permute[i]];
            }
        } else {
            for (int i = 0; i < builder.n; i++) {
                final int Ci = C * i;
                all[Ci + BEG] = builder.begs[i];
                all[Ci + END] = builder.ends[i];
            }
            permute = null;
        }
        builder.reset();

        // compute index nodes
        final int[] indexNodesTmp = new int[31];
        indexNodesTmp[0] = 0;
        int nIndexNodes = 1;

        int nRem = N;
        while (nRem > 0) { // for each binary one digit in N
            final int p2 = Integer.highestOneBit(nRem);
            assert p2 > 0 && Integer.bitCount(p2) == 1;
            indexNodesTmp[nIndexNodes] = p2 + indexNodesTmp[nIndexNodes - 1];
            nIndexNodes++;
            nRem &= ~p2;
        }
        indexNodes = new int[nIndexNodes];
        for (int i = 0; i < nIndexNodes; i++) {
            indexNodes[i] = indexNodesTmp[i];
        }
        assert indexNodes[nIndexNodes - 1] == N;

        // Compute maxEnds througout each implict tree; for the index nodes themselves, the maxEnd
        // is the greater of its own end position and the maxEnd of the subsequent tree.
        for (int which_i = 0; which_i < indexNodes.length - 1; which_i++) {
            final int i = indexNodes[which_i];
            final int n_i = indexNodes[which_i + 1] - i;
            final int Ci = C * i;
            assert n_i > 0 && Integer.bitCount(n_i) == 1;
            if (n_i == 1) {
                all[Ci + MAX_END] = all[Ci + END];
            } else {
                int root = rootNode(n_i - 1);
                assert nodeLevel(root) == rootLevel(n_i - 1);
                recurseMaxEnds(i + 1, root, nodeLevel(root));
                all[Ci + MAX_END] = max(all[Ci + END], all[C * (i + 1 + root) + MAX_END]);
            }
        }
    }

    public void validate() {
        assert all.length == C * N;
        assert permute == null || permute.length == N;

        for (int i = 0; i < N; i++) {
            final int Ci = C * i;
            assert all[Ci + END] >= all[Ci + BEG];
            if (i > 0) {
                if (all[Ci + BEG] == all[Ci - C + BEG]) {
                    assert all[Ci + END] >= all[Ci - C + END];
                } else {
                    assert all[Ci + BEG] > all[Ci - C + BEG];
                }
            }
            assert all[Ci + MAX_END] >= all[Ci + END];
        }
    }

    /** @return Total number of intervals stored. */
    public int size() {
        return N;
    }

    /** Result from a query, an interval and its ID as returned by Builder.add() */
    public static class QueryResult {
        public final long beg;
        public final long end;
        public final int id;

        public QueryResult(long beg, long end, int id) {
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
     * @param callback Predicate function to be called with each query result; it may return true to
     *     continue the query, or false to stop immediately.
     */
    public void queryOverlap(long queryBeg, long queryEnd, Predicate<QueryResult> callback) {
        queryOverlapInternal(
                queryBeg,
                queryEnd,
                i -> {
                    final int Ci = C * i;
                    return callback.test(
                            new QueryResult(
                                    all[Ci + BEG],
                                    all[Ci + END],
                                    permute != null ? permute[i] : i));
                });
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
    public java.util.List<QueryResult> queryOverlap(long queryBeg, long queryEnd) {
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
     * @param callback Predicate function to be called with each query result ID; it may return true
     *     to continue the query, or false to stop immediately.
     */
    public void queryOverlapId(long queryBeg, long queryEnd, IntPredicate callback) {
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
    public QueryResult queryAnyOverlap(long queryBeg, long queryEnd) {
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
     * @param queryEnd Query interval end position (exclusive). A stored interval whose begin
     *     position equals the query end position is considered abutting, but NOT overlapping the
     *     query, so would not be returned.
     * @return -1 if there are no overlapping intervals stored.
     */
    public int queryAnyOverlapId(long queryBeg, long queryEnd) {
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
    public boolean queryOverlapExists(long queryBeg, long queryEnd) {
        return queryAnyOverlapId(queryBeg, queryEnd) >= 0;
    }

    /**
     * Query for all stored intervals exactly equalling the given interval.
     *
     * @param callback @see queryOverlapId
     */
    public void queryExactId(long queryBeg, long queryEnd, IntPredicate callback) {
        // TODO: replace with simple binary search in the beg-sorted array
        queryOverlapInternal(
                queryBeg,
                queryEnd,
                i -> {
                    final int Ci = C * i;
                    if (all[Ci + BEG] == queryBeg && all[Ci + END] == queryEnd) {
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
    public int queryAnyExactId(long queryBeg, long queryEnd) {
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
    public boolean queryExactExists(long queryBeg, long queryEnd) {
        return queryAnyExactId(queryBeg, queryEnd) >= 0;
    }

    /**
     * Query for all stored intervals
     *
     * @param callback @see queryOverlap
     */
    public void queryAll(Predicate<QueryResult> callback) {
        for (int i = 0; i < N; i++) {
            final int Ci = C * i;
            if (!callback.test(
                    new QueryResult(
                            all[Ci + BEG], all[Ci + END], permute != null ? permute[i] : i))) {
                return;
            }
        }
    }

    private void recurseMaxEnds(final int row0, final int node, final int lvl) {
        // compute interval tree augmentation values for the subtree rooted at (ofs+node)
        // TODO: should be faster to replace recursion with stepping through levels, as in cgranges
        final int Ci = C * (row0 + node);
        Long maxEnd = all[Ci + END];
        if (lvl > 0) {
            int ch = nodeLeftChild(node, lvl);
            // assert ch >= 0 && ch < node;
            recurseMaxEnds(row0, ch, lvl - 1);
            maxEnd = max(maxEnd, all[C * (row0 + ch) + MAX_END]);
            ch = nodeRightChild(node, lvl);
            // assert ch > node && ch < N;
            recurseMaxEnds(row0, ch, lvl - 1);
            maxEnd = max(maxEnd, all[C * (row0 + ch) + MAX_END]);
        }
        all[Ci + MAX_END] = maxEnd;
    }

    private void queryOverlapInternal(long queryBeg, long queryEnd, IntPredicate callback) {
        // for each index node
        for (int which_i = 0; which_i < indexNodes.length - 1; which_i++) {
            final int i = indexNodes[which_i];
            final int Ci = C * i;
            if (all[Ci + BEG] >= queryEnd) {
                break; // whole remainder of the beg-sorted array must be irrelevant
            } else if (all[Ci + MAX_END] > queryBeg) { // slice has relevant item(s)
                if (all[Ci + END] > queryBeg) { // index node is a hit itself, return it first
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
                    recurseQuery(queryBeg, queryEnd, i + 1, root, nodeLevel(root), callback);
                }
            }
        }
    }

    private boolean recurseQuery(
            long queryBeg,
            long queryEnd,
            final int row0,
            final int node,
            final int lvl,
            IntPredicate callback) {
        // TODO: unroll traversal of bottom few levels
        final int i = row0 + node;
        final int Ci = C * i;
        if (all[Ci + MAX_END] > queryBeg) { // subtree rooted here may have relevant item(s)
            if (lvl > 0) { // search left subtree
                if (!recurseQuery(
                        queryBeg, queryEnd, row0, nodeLeftChild(node, lvl), lvl - 1, callback)) {
                    return false;
                }
            }
            if (all[Ci + BEG] < queryEnd) { // root or right subtree may include relevant item(s)
                if (all[Ci + END] > queryBeg) { // current root overlaps
                    if (!callback.test(i)) {
                        return false;
                    }
                }
                if (lvl > 0) { // search right subtree
                    if (!recurseQuery(
                            queryBeg,
                            queryEnd,
                            row0,
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

    private static long max(long lhs, long rhs) {
        // avoiding Long.max b/c Short.max does not exist for some reason
        return lhs >= rhs ? lhs : rhs;
    }
}
