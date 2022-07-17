#!/bin/bash

PRG="$0"
# Need this for relative symlinks.
while [ -h "$PRG" ] ; do
    ls=`ls -ld "$PRG"`
    link=`expr "$ls" : '.*-> \(.*\)$'`
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=`dirname "$PRG"`"/$link"
    fi
done
cd "`dirname \"$PRG\"`/" >/dev/null
cd src/main/java/net/mlin/iitj

sed s/Double/Float/g DoubleIntervalTree.java | sed s/double/float/g > FloatIntervalTree.java
sed s/Double/Integer/g DoubleIntervalTree.java | sed s/double/int/g > IntegerIntervalTree.java
sed s/Double/Long/g DoubleIntervalTree.java | sed s/double/long/g > LongIntervalTree.java
sed s/Double/Short/g DoubleIntervalTree.java | sed s/double/short/g > ShortIntervalTree.java
