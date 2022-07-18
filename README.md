# iitj
**Implicit Interval Trees (for Java)**

iitj provides an in-memory data structure for indexing begin/end position intervals, such as genome feature annotations or time windows, and answering requests for all items overlapping a query interval. The design is based on [Heng Li's cgranges](https://github.com/lh3/cgranges), differing in some implementation details. It's compact in memory (by JVM standards) and serializes efficiently, but it's currently read-only once built.

While designed for general purposes, our original motivation was to use in [Apache Spark broadcast variables](https://spark.apache.org/docs/3.2.1/rdd-programming-guide.html#broadcast-variables) for highly parallelized joining/filtering of big genomic datasets with smaller reference annotations.

### Quick start

### Design notes

**Data structure layout.** First please review the original design of [cgranges](https://github.com/lh3/cgranges); we have some [extra notes](https://github.com/mlin/iitii/blob/master/notes_on_cgranges.md) to help.

cgranges has a few complications to handle the typical case that its implicit binary tree isn't full & complete (that is, the stored item count *N* isn't exactly a power of two minus one). Instead of treating the entire sorted array as one incomplete tree, we decompose it into a concatenation of full & complete trees, as suggested by [Brodal, Fagerberg & Jacob (2001) §3.3](https://tidsskrift.dk/brics/article/download/21696/19132). Write *N* as a sum of powers of two, e.g. *N* = 12345 = 8192 + 4096 + 32 + 16 + 8 + 1, then interpret each corresponding slice of the array as a full & complete search tree (plus one extra "index node").

The code for this solution isn't much simpler than cgranges, but it seems easier to explain conceptually.

**Java/JVM specifics.** The implict tree's compactness would be somewhat defeated if we kept each interval boxed in its own JVM `Object`. Instead, we store each interval's essential coordinates in a primitive array (three position values per interval). We don't store any `Object` references, but we assign each interval an integer ID corresponding to its original insertion order. Then, only if needed, the caller can separately associate these IDs with other JVM objects. If the caller takes care to insert the intervals in sorted order (by begin then end), then we don't use any separate storage for the IDs. (Otherwise we store the permutation from the sorted order onto the insertion/ID order.)

Lastly, due to [limitations of Java generics](https://www.infoworld.com/article/3639525/openjdk-proposals-would-bring-universal-generics-to-java.html), we provide separate classes for double/float/int/long/short interval position types. `DoubleIntervalTree.java` serves as the source template, from which we [generate](https://github.com/mlin/iitj/blob/main/generate.sh) the others by sed find/replace.
