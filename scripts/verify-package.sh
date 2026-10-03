#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
version="$(mvn -B -ntp -q org.apache.maven.plugins:maven-help-plugin:3.5.2:evaluate -Dexpression=project.version -DforceStdout)"
test -f "target/shieldlabs-java-${version}.jar"
mvn -B -ntp org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies \
  -DincludeScope=runtime -DoutputDirectory="$work/lib"
cp "target/shieldlabs-java-${version}.jar" "$work/lib/"
cp scripts/consumer/Consumer.java "$work/"
javac --release 11 -Xlint:all -Werror -cp "$work/lib/*" -d "$work" "$work/Consumer.java"
java -cp "$work:$work/lib/*" Consumer
