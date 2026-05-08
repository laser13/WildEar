#!/usr/bin/env bash
# Wrapper for running gradle unit tests with the right JAVA_HOME and bounded memory.
#
# Why: the project requires JDK 17 (room-gradle-plugin:2.6.1 won't resolve under
# the wrapper's bundled JDK 11). KSP + Hilt + Compose annotation processors push
# heap usage close to the gradle.properties default; on machines under memory
# pressure the daemon gets OOM-killed (exit 137). We use a slightly tighter heap
# than the project default so the kernel has headroom.
#
# Usage:
#   bin/test.sh                     # all unit tests
#   bin/test.sh com.foo.MyTest      # single class
#   bin/test.sh "com.foo.*"         # pattern
set -euo pipefail

JAVA_17_HOME="${JAVA_17_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
if [[ ! -d "$JAVA_17_HOME" ]]; then
  echo "JDK 17 not found at $JAVA_17_HOME" >&2
  echo "Set JAVA_17_HOME to your installed JDK 17 (e.g., temurin/zulu)." >&2
  exit 2
fi
export JAVA_HOME="$JAVA_17_HOME"

# Bounded heap; project default is 4096m which is too aggressive when other
# processes (IDE, browser) are running.
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8"

cd "$(dirname "$0")/.."

# Hard mode: no daemon (clean JVM each run), bounded heap, single worker.
# Avoids piling up Kotlin/KSP/Hilt processors in a long-lived daemon that
# eventually OOM-kills (exit 137) under memory pressure.
GRADLE_FLAGS="--no-daemon --console=plain --max-workers=1"

if (( $# == 0 )); then
  ./gradlew :app:testDebugUnitTest $GRADLE_FLAGS
else
  ./gradlew :app:testDebugUnitTest $GRADLE_FLAGS --tests "$@"
fi
