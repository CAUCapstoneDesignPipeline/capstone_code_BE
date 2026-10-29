#!/bin/bash
# Run at the explicitly selected BE worktree. Uses existing Gradle/Testcontainers dependencies.
set -euo pipefail
test -f build.gradle
test -n "${JAVA_HOME:-}"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1 | grep -q '"21\.'
mkdir -p .local/verification/logs
scratch=$(mktemp -d .local/verification/migration-test.XXXXXX)
trap 'rm -r "$scratch"' EXIT
cat > "$scratch/classpath.gradle" <<'GRADLE'
allprojects {
    tasks.register('verificationClasspath') {
        doLast { println configurations.testRuntimeClasspath.asPath }
    }
}
GRADLE
./gradlew -q -I "$scratch/classpath.gradle" verificationClasspath > "$scratch/classpath.txt"
"$JAVA_HOME/bin/javac" -cp "$(cat "$scratch/classpath.txt")" -d "$scratch" deploy/gcp/tests/MigrationAcceptance.java
"$JAVA_HOME/bin/java" -cp "$scratch:$(cat "$scratch/classpath.txt")" MigrationAcceptance "$PWD"
