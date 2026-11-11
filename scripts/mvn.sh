#!/usr/bin/env bash
# Runs Maven with JDK 25 inside Docker (for machines without JDK 25 installed).
# Usage: scripts/mvn.sh <maven args>   e.g. scripts/mvn.sh test
set -euo pipefail
cd "$(dirname "$0")/../backend"
mkdir -p "$HOME/.m2"
exec docker run --rm -it --network host \
  --user "$(id -u):$(id -g)" \
  -e MAVEN_CONFIG=/var/maven/.m2 \
  -v "$HOME/.m2":/var/maven/.m2 \
  -v "$PWD":/app -w /app \
  maven:3.9-eclipse-temurin-25 \
  mvn -Duser.home=/var/maven "$@"
