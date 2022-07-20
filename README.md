# iitj
**Implicit Interval Trees (for Java)**

[![build](https://github.com/mlin/iitj/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/mlin/iitj/actions/workflows/build.yml) [![javadoc](https://img.shields.io/badge/javadoc-latest-brightgreen)](https://mlin.github.io/iitj/javadoc/latest) ![GitHub release (latest by date)](https://img.shields.io/github/v/release/mlin/iitj)

iitj provides an in-memory data structure for indexing [begin,end) position intervals, such as genome feature annotations or time windows, and answering requests for all items overlapping a query interval. It stores all the intervals in a few primitive arrays instead of individual Objects, achieving space and serialization efficiency; but it's currently read-only once built. The design is based on [Heng Li's cgranges](https://github.com/lh3/cgranges), differing in some implementation details.

Our original motivation was to have a data structure suitable to ship in [Apache Spark broadcast variables](https://spark.apache.org/docs/3.2.1/rdd-programming-guide.html#broadcast-variables) for distributed joining/filtering of big genomic datasets with smaller reference annotations.

## Installation

With the current [![release version](https://img.shields.io/github/v/release/mlin/iitj)](https://github.com/mlin/iitj/releases) = X.Y.Z,

**Gradle:** add to your `gradle.build`,

```groovy
repositories {
    maven {
        url "https://raw.githubusercontent.com/wiki/mlin/iitj/mvn-repo/"
    }
}
dependencies {
    implementation 'net.mlin:iitj:X.Y.Z'
}
```

**Maven:** add to your `pom.xml`,

```xml
    <repositories>
        <repository>
            <id>iitj</id>
            <url>https://raw.githubusercontent.com/wiki/mlin/iitj/mvn-repo/</url>
        </repository>
    </repositories>
    <dependencies>
        <dependency>
            <groupId>net.mlin</groupId>
            <artifactId>iitj</artifactId>
            <version>X.Y.Z</version>
        </dependency>
    </dependencies>
```

## Quick start

Import any of `net.mlin.iitj.{Double,Float,Integer,Long,Short}IntervalTree` according to the desired interval position type. The following example will use `IntegerIntervalTree`.

```java
import net.mlin.iitj.IntegerIntervalTree;

IntegerIntervalTree.Builder builder = new IntegerIntervalTree.Builder();
int id0 = builder.add(0, 23);   // id0 == 0
int id1 = builder.add(12, 34);  // id1 == 1
int id2 = builder.add(34, 56);  // id2 == 2
IntegerIntervalTree it = builder.build();

List<IntegerIntervalTree.QueryResult> hits = it.queryOverlap(22, 25);
for (IntegerIntervalTree.QueryResult hit : hits) {
    System.out.println(
        String.join("\t", String.valueOf(hit.beg), String.valueOf(hit.end), String.valueOf(hit.id))
    );
}

/*
output:
0       23      0
12      34      1
*/
```

All [beg, end) interval positions are *half-open*, with inclusive begin position and exclusive end position. Given a query interval [x,y), intervals [w,x) and [y,z) are abutting but *not* overlapping, so would not be returned by the overlap query. (See [Dijkstra's note](https://www.cs.utexas.edu/users/EWD/ewd08xx/EWD831.PDF) on this convention.)

Use the interval IDs, reflecting the order in which they're added to the builder, to associate results with other data/objects if needed.

See [![javadoc](https://img.shields.io/badge/javadoc-latest-brightgreen)](https://mlin.github.io/iitj/javadoc/latest) for other available query methods.

## Design notes

**Data structure layout.** First please review the original design of [cgranges](https://github.com/lh3/cgranges); we have some [extra notes](https://github.com/mlin/iitii/blob/master/notes_on_cgranges.md) to help.

cgranges handles a few complications in the typical case that its implicit binary tree isn't full & complete (that is, the stored item count *N* isn't exactly a power of two minus one). Instead of treating the entire sorted array as one incomplete tree, we decompose it into a concatenation of full & complete trees, as suggested by [Brodal, Fagerberg & Jacob (2001) §3.3](https://tidsskrift.dk/brics/article/download/21696/19132). Write *N* as a sum of powers of two, e.g. *N* = 12345 = 8192 + 4096 + 32 + 16 + 8 + 1, then interpret each corresponding slice of the array as a full & complete search tree (plus one extra "index node").

This solution isn't much simpler than cgranges in code, but it seems easier to explain conceptually.

**Java/JVM specifics.** The implict tree's compactness would be somewhat defeated if we kept each interval boxed in its own JVM `Object`. Instead, we store each interval's essential coordinates in a primitive array (three position values per interval). We don't store any `Object` references, but we assign each interval an integer ID corresponding to its original insertion order. If the caller takes care to insert the intervals in sorted order (by begin then end), then we don't use any separate storage for the IDs. (Otherwise we store the permutation from the sorted order onto the insertion/ID order.)

Lastly, due to [limitations of Java generics](https://www.infoworld.com/article/3639525/openjdk-proposals-would-bring-universal-generics-to-java.html), we provide separate classes for double/float/int/long/short interval position types. `DoubleIntervalTree.java` serves as the source template, from which we [generate](https://github.com/mlin/iitj/blob/main/generate.sh) the others by sed find/replace.
