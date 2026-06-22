@echo off
cd /d "%~dp0"

rem Netty 4.2.x uses the Foreign Function & Memory API (MemorySegment) instead of sun.misc.Unsafe,
rem so on JDK 24+ it runs WITHOUT the terminal-deprecation Unsafe warning and needs no flag for it
rem (4.1.x did - it required -Dio.netty.noUnsafe=true).
rem -Dlog4j2.shutdownHookEnabled=false  lets our own shutdown hook stop log4j2 LAST, so teardown
rem                                     events still reach the log file the tail page reads.
rem -Dlog4j2.configurationFile=...      points log4j2 at the config under xml/config/.
java -Dlog4j2.shutdownHookEnabled=false ^
     -Dlog4j2.configurationFile=xml/config/log4j2.xml ^
     -jar modbusBridge.jar

exit
