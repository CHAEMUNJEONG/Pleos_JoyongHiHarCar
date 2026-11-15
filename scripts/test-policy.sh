#!/bin/sh
set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BUILD_DIR=$(mktemp -d "${TMPDIR:-/tmp}/quietdrive-policy-test.XXXXXX")
trap 'rm -rf "$BUILD_DIR"' EXIT HUP INT TERM

javac -Xlint:all -Werror -d "$BUILD_DIR" \
  "$PROJECT_ROOT/app/src/main/java/ai/pleos/quietdrive/AudioPolicy.java" \
  "$PROJECT_ROOT/app/src/main/java/ai/pleos/quietdrive/PositionPolicy.java" \
  "$PROJECT_ROOT/tests/PolicyTest.java"

java -ea -cp "$BUILD_DIR" ai.pleos.quietdrive.PolicyTest
