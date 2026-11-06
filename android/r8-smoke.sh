#!/usr/bin/env bash
# Verifies that R8 shrinking of the release build doesn't break the Claude SDK
# at runtime. Runs R8 in Java-classfile mode over the assistant code + its
# dependencies with the exact merged rules the Android build used
# (app/build/outputs/mapping/release/configuration.txt), then runs
# com.fastwake.app.R8Smoke against a local mock Claude server.
#
# usage: ./r8-smoke.sh   (after ./gradlew assembleRelease compileReleaseUnitTestKotlin)
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_HOME:?set ANDROID_HOME}"
: "${JAVA_HOME:?set JAVA_HOME}"
R8_JAR="$ANDROID_HOME/build-tools/35.0.0/lib/d8.jar"
WORK=build/r8-smoke; rm -rf "$WORK"; mkdir -p "$WORK/in"

# Program input: the Android-independent assistant code plus the smoke main.
cp -r app/build/tmp/kotlin-classes/release/com "$WORK/in/" 2>/dev/null || true
find "$WORK/in/com/fastwake/app" -mindepth 1 -maxdepth 1 ! -name assistant -exec rm -rf {} +
mkdir -p "$WORK/in/com/fastwake/app"
cp app/build/tmp/kotlin-classes/releaseUnitTest/com/fastwake/app/R8Smoke*.class "$WORK/in/com/fastwake/app/"
(cd "$WORK/in" && "$JAVA_HOME/bin/jar" cf ../app-classes.jar .)

# Dependency jars, same versions as the APK's runtime classpath.
COORDS="
com.anthropic anthropic-java-core 3.6.0
com.anthropic anthropic-java-client-okhttp 3.6.0
com.anthropic anthropic-java 3.6.0
com.fasterxml.jackson.core jackson-annotations 2.19.4
com.fasterxml.jackson.core jackson-core 2.19.4
com.fasterxml.jackson.core jackson-databind 2.19.4
com.fasterxml.jackson.datatype jackson-datatype-jdk8 2.19.4
com.fasterxml.jackson.datatype jackson-datatype-jsr310 2.19.4
com.fasterxml.jackson.module jackson-module-kotlin 2.19.4
com.fasterxml classmate 1.7.0
com.github.victools jsonschema-generator 4.38.0
com.github.victools jsonschema-module-jackson 4.38.0
com.github.victools jsonschema-module-swagger-2 4.38.0
com.standardwebhooks standardwebhooks 1.2.0
io.opentelemetry opentelemetry-api 1.62.0
io.opentelemetry opentelemetry-common 1.62.0
io.opentelemetry opentelemetry-context 1.62.0
io.swagger.core.v3 swagger-annotations 2.2.50
org.jetbrains.kotlin kotlin-reflect 1.9.25
org.slf4j slf4j-api 2.0.16
com.squareup.okhttp3 okhttp 4.12.0
com.squareup.okio okio-jvm 3.6.0
org.jetbrains.kotlin kotlin-stdlib 2.0.21
"
# Everything is shrunk together, exactly as in the APK (R8Smoke itself uses no
# libraries: its mock server is a raw ServerSocket).
jar_for() { find ~/.gradle/caches/modules-2/files-2.1/"$1"/"$2"/"$3" -name "$2-$3.jar" | head -1; }
PROGRAM=("$WORK/app-classes.jar")
while read -r g a v; do [ -n "$g" ] && PROGRAM+=("$(jar_for "$g" "$a" "$v")"); done <<<"$COORDS"

# Rules: the exact merged configuration from the Android build, minus lines
# that only make sense on Android or write files, plus the smoke entry point.
grep -vE '^-(printconfiguration|printmapping|printseeds|printusage|android|dontpreverify)' \
    app/build/outputs/mapping/release/configuration.txt > "$WORK/rules.pro"
cat >> "$WORK/rules.pro" <<'EOF'
-keep class com.fastwake.app.R8Smoke { public static void main(java.lang.String[]); }
-dontwarn android.**
-dontwarn dalvik.**
-dontwarn com.fastwake.app.**
-dontwarn java.lang.invoke.**
-ignorewarnings
EOF

"$JAVA_HOME/bin/java" -cp "$R8_JAR" com.android.tools.r8.R8 --classfile --release \
    --lib "$JAVA_HOME" --pg-conf "$WORK/rules.pro" \
    --output "$WORK/shrunk.jar" "${PROGRAM[@]}"

IN=$(cat "${PROGRAM[@]}" | wc -c); OUT=$(wc -c < "$WORK/shrunk.jar")
echo "R8: $((IN / 1024 / 1024)) MB of jars -> $((OUT / 1024 / 1024)) MB shrunk"

"$JAVA_HOME/bin/java" -cp "$WORK/shrunk.jar" com.fastwake.app.R8Smoke
