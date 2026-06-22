#!/bin/sh

BASEDIR=$(dirname "$0")
cd "$BASEDIR" || exit 1

# Netty 4.2.x uses the Foreign Function & Memory API (MemorySegment) instead of sun.misc.Unsafe,
# so on JDK 24+ it runs WITHOUT the terminal-deprecation Unsafe warning and needs no flag for it
# (4.1.x did — it required -Dio.netty.noUnsafe=true).
# -Dlog4j2.shutdownHookEnabled=false  lets our own shutdown hook stop log4j2 LAST, so teardown
#                                     events still reach the log file the tail page reads.
# -Dlog4j2.configurationFile=...      points log4j2 at the config under xml/config/.
java -Dlog4j2.shutdownHookEnabled=false \
     -Dlog4j2.configurationFile=xml/config/log4j2.xml \
     -jar modbusBridge.jar

exit
