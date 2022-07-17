# iitj
**Implicit Interval Trees (for Java)**

iitj provides an in-memory data structure for indexing begin/end position intervals, such as genome feature annotations or time windows, and answering requests for all items overlapping a query interval. The design is based on [Heng Li's cgranges](https://github.com/lh3/cgranges), differing in some implementation details. It's fairly compact in memory (by JVM standards) and efficiently serializable, but currently read-only once built.

While designed for general purposes, our original motivation was to use in [Apache Spark broadcast variables](https://spark.apache.org/docs/3.2.1/rdd-programming-guide.html#broadcast-variables) for highly parallelized joining/filtering of big genomic datasets with smaller reference annotations.

## Quick start

## Implementation notes

**Data structure layout.** First review the original design of [cgranges](https://github.com/lh3/cgranges), perhaps informed by [our notes on it](https://github.com/mlin/iitii/blob/master/notes_on_cgranges.md).

cgranges runs into a few complications in the usual case that its implicit binary tree isn't full & complete (that is, the sorted array of *N* items isn't a power of two minus one). iitj uses a different approach, suggested by [Brodal, Fagerberg & Jacob (2001) §3.3](https://tidsskrift.dk/brics/article/download/21696/19132), treating the array as a concatenation of full & complete trees instead of a big, incomplete one. Write *N* as a sum of powers of two, e.g. *N* = 12345 = 8192 + 4096 + 32 + 16 + 8 + 1. Then consider each corresponding slice of the array as a full & complete search tree (plus one extra "index node").

With hindsight, we think the overall complexity is similar to cgranges, but the concerns are more separate and easier to explain.

**Java/JVM specifics.** The implict tree's compactness would be somewhat defeated if we still kept the intervals boxed in separate JVM objects and scattered all over the heap. Instead, we store the essential data about each interval in a handful of primitive arrays (three position values per interval). No object references are stored, but each interval has an integer ID corresponding to its insertion order. Then the caller can associate these IDs with other JVM objects *if needed*. If the items are inserted in sorted order, then no separate storage for the IDs is needed. (Otherwise we store the permutation from the sorted order onto the insertion/ID order.) 

Lastly, due to [limitations of Java generics](https://www.infoworld.com/article/3639525/openjdk-proposals-would-bring-universal-generics-to-java.html), we provide separate classes for double/float/int/short interval position types. `DoubleIntervalTree.java` serves as the source template, from which the others are generated by sed find/replace.
