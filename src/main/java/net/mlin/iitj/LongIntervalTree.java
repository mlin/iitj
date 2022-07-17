package net.mlin.iitj;

import java.util.ArrayList;
import java.util.function.Function;

/**
 * Data structure for storing long [begin, end) intervals and answering requests for all items
 * overlapping a query interval. The structure is relatively memory-efficient and serializable, but
 * read-only once built.
 */
public class LongIntervalTree implements java.io.Serializable {
    public static class Builder {
        private int n;
        private long[] begs, ends;
        private Object[] objs;

        public Builder() {
            reset();
        }

        public void add(long beg, long end, Object tag) {
            if (beg > end) {
                throw new IllegalArgumentException();
            }
            if (n == begs.length) {
                grow();
                assert n < begs.length;
            }
            begs[n] = beg;
            ends[n] = end;
            if (tag != null) {
                if (objs == null) {
                    objs = new Object[begs.length];
                }
                objs[n] = tag;
            }
            n++;
        }

        public void add(long beg, long end) {
            add(beg, end, null);
        }

        public LongIntervalTree build() {
            LongIntervalTree ans = new LongIntervalTree(this);
            reset();
            return ans;
        }

        private void reset() {
            n = 0;
            begs = new long[16];
            ends = new long[begs.length];
            objs = null;
        }

        private void grow() {
            long capacity = begs.length;
            if (capacity == Integer.MAX_VALUE) {
                throw new UnsupportedOperationException("ImplicitIntervalTree capacity overflow");
            }
            assert ends.length == capacity;
            assert objs == null || objs.length == capacity;
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
            if (objs != null) {
                Object[] obj2 = new Object[(int) capacity];
                for (int i = 0; i < n; i++) {
                    obj2[i] = objs[i];
                }
                objs = obj2;
            }
        }
    }

    private final long[] begs, ends, maxEnds;
    private final Object[] objs;
    private final int[] indexNodes;

    public LongIntervalTree(Builder builder) {
        // compute sorting permutation of builder intervals
        // https://stackoverflow.com/a/25778783
        int[] permute =
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

        // copy sorted intervals into this
        begs = new long[builder.n];
        ends = new long[builder.n];
        maxEnds = new long[builder.n];
        objs = builder.objs != null ? new Object[builder.n] : null;

        for (int i = 0; i < builder.n; i++) {
            begs[i] = builder.begs[permute[i]];
            ends[i] = builder.ends[permute[i]];
            if (objs != null) {
                objs[i] = builder.objs[permute[i]];
            }
        }

        // compute index nodes
        int[] indexNodesTmp = new int[31];
        indexNodesTmp[0] = 0;
        int nIndexNodes = 1;

        int nRem = builder.n;
        while (nRem > 0) {
            int p2 = Integer.highestOneBit(nRem);
            assert p2 > 0;
            indexNodesTmp[nIndexNodes] = p2 + indexNodesTmp[nIndexNodes - 1];
            nIndexNodes++;
            nRem &= ~p2;
        }
        assert indexNodesTmp[nIndexNodes - 1] == builder.n;
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
        assert objs == null || objs.length == n;

        for (int i = 0; i < n; i++) {
            assert ends[i] >= begs[i];
            if (i > 0) {
                if (begs[i] == begs[i - 1]) {
                    assert ends[i] >= ends[i - 1];
                } else {
                    assert begs[i] > begs[i - 1];
                }
            }
            assert maxEnds[i] >= ends[i];
        }
    }

    public int size() {
        return begs.length;
    }

    public static class QueryResult {
        public final long beg;
        public final long end;
        public final Object obj;

        public QueryResult(long beg, long end, Object obj) {
            this.beg = beg;
            this.end = end;
            this.obj = obj;
        }

        public String toString() {
            // for debugging
            String ans = "[" + (new Long(beg)).toString() + "," + (new Long(end)).toString() + ")";
            if (obj != null) {
                ans += "=" + obj.toString();
            }
            return ans;
        }

        @Override
        public boolean equals(Object rhso) {
            if (rhso instanceof QueryResult) {
                QueryResult rhs = (QueryResult) rhso;
                return beg == rhs.beg
                        && end == rhs.end
                        && (obj != null ? obj.equals(rhs.obj) : rhs.obj == null);
            }
            return false;
        }
    }

    public void queryOverlap(
            Long queryBeg, Long queryEnd, Function<QueryResult, Boolean> callback) {
        for (int which_i = 0; which_i < indexNodes.length - 1; which_i++) {
            int i = indexNodes[which_i];
            if (begs[i] >= queryEnd) {
                break;
            } else if (maxEnds[i] > queryBeg) {
                if (ends[i] > queryBeg) {
                    Boolean stop =
                            callback.apply(
                                    new QueryResult(
                                            begs[i], ends[i], objs != null ? objs[i] : null));
                    if (stop != null && stop != false) {
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

    public java.util.List<QueryResult> queryOverlap(Long queryBeg, Long queryEnd) {
        ArrayList<QueryResult> results = new ArrayList<QueryResult>();
        queryOverlap(
                queryBeg,
                queryEnd,
                x -> {
                    results.add(x);
                    return null;
                });
        return results;
    }

    public QueryResult queryAnyOverlap(Long queryBeg, Long queryEnd) {
        QueryResult[] ans = {null};
        queryOverlap(
                queryBeg,
                queryEnd,
                x -> {
                    ans[0] = x;
                    return true;
                });
        return ans[0];
    }

    public boolean queryOverlapExists(Long queryBeg, Long queryEnd) {
        return queryAnyOverlap(queryBeg, queryEnd) != null;
    }

    public void queryExact(Long queryBeg, Long queryEnd, Function<QueryResult, Boolean> callback) {
        // TODO: replace with simple binary search in the beg-sorted array
        queryOverlap(
                queryBeg,
                queryEnd,
                res -> {
                    if (res.beg == queryBeg && res.end == queryEnd) {
                        return callback.apply(res);
                    }
                    return true;
                });
    }

    public QueryResult queryAnyExact(Long queryBeg, Long queryEnd) {
        QueryResult[] ans = {null};
        queryExact(
                queryBeg,
                queryEnd,
                x -> {
                    ans[0] = x;
                    return true;
                });
        return ans[0];
    }

    public boolean queryExactExists(Long queryBeg, Long queryEnd) {
        return queryAnyExact(queryBeg, queryEnd) != null;
    }

    public void queryAll(Function<QueryResult, Boolean> callback) {
        queryOverlap(Long.MIN_VALUE, Long.MAX_VALUE, callback);
    }

    private void recurseMaxEnds(int ofs, int node, int lvl) {
        // TODO: should be faster to replace recursion with stepping through levels, as in cgranges
        Long maxEnd = ends[ofs + node];
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

    private boolean recurseQuery(
            long queryBeg,
            long queryEnd,
            int ofs,
            int node,
            int lvl,
            Function<QueryResult, Boolean> callback) {
        // TODO: unroll traversal of bottom few levels
        int i = ofs + node;
        if (maxEnds[i] > queryBeg) {
            if (lvl > 0) {
                if (recurseQuery(
                        queryBeg, queryEnd, ofs, nodeLeftChild(node, lvl), lvl - 1, callback)) {
                    return true;
                }
            }
            if (begs[i] < queryEnd) {
                if (ends[i] > queryBeg) {
                    Boolean stop =
                            callback.apply(
                                    new QueryResult(
                                            begs[i], ends[i], objs != null ? objs[i] : null));
                    if (stop != null && stop != false) {
                        return true;
                    }
                }
                if (lvl > 0) {
                    if (recurseQuery(
                            queryBeg,
                            queryEnd,
                            ofs,
                            nodeRightChild(node, lvl),
                            lvl - 1,
                            callback)) {
                        return true;
                    }
                }
            }
        }
        return false;
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

    private static Long max(Long lhs, Long rhs) {
        // avoiding Long.max b/c Short.max does not exist for some reason
        return lhs >= rhs ? lhs : rhs;
    }
}