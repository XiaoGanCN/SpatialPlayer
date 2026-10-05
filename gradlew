#!/bin/sh
# Minimal Gradle wrapper launcher (POSIX).

APP_BASE_NAME=${0##*/}
APP_HOME=$(cd -P "$(dirname "$0")" > /dev/null 2>&1 && pwd)

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVACMD=$JAVA_HOME/bin/java
else
    JAVACMD=java
    command -v java > /dev/null 2>&1 || { echo "ERROR: JAVA_HOME is not set and no 'java' command could be found." >&2; exit 1; }
fi

exec "$JAVACMD" \
    -Xmx64m -Xms64m \
    "-Dorg.gradle.appname=$APP_BASE_NAME" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
