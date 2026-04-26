#!/bin/sh
# Add default JVM options here.
DEFAULT_JVM_OPTS="-Xmx64m -Xms64m"
APP_HOME=$(dirname "$0")
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
exec java $DEFAULT_JVM_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"

