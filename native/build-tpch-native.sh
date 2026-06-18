#!/bin/bash
set -e
GRAALVM=/Library/Java/JavaVirtualMachines/graalvm-21.jdk/Contents/Home
CP=$(cat /tmp/tb_cp_interp.txt)
"$GRAALVM/bin/native-image" \
  -cp "$CP" \
  --no-fallback \
  -J--add-opens=java.base/java.nio=ALL-UNNAMED \
  -H:ConfigurationFileDirectories=native/agent-config \
  --initialize-at-build-time=org.apache.arrow.memory.util.MemoryUtil \
  --initialize-at-build-time=org.slf4j,org.apache.log4j \
  -H:+UnlockExperimentalVMOptions \
  -o native/tpch-native \
  protocatalyst.truffle.backend.bench.TpchNativeDriver
