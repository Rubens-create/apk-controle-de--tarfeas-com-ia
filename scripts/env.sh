#!/usr/bin/env bash
# Optional local toolchain. CI uses setup-java and setup-android.
_TASK_AI_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
_TASK_AI_NATIVE="$(cygpath -m "$_TASK_AI_ROOT" 2>/dev/null || printf '%s' "$_TASK_AI_ROOT")"
if [ -d "$_TASK_AI_ROOT/.tooling/java" ]; then
  export JAVA_HOME="$_TASK_AI_NATIVE/.tooling/java"
  export PATH="$_TASK_AI_ROOT/.tooling/java/bin:$PATH"
fi
if [ -d "$_TASK_AI_ROOT/.tooling/android-sdk" ]; then
  export ANDROID_HOME="$_TASK_AI_NATIVE/.tooling/android-sdk"
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
  export PATH="$_TASK_AI_ROOT/.tooling/android-sdk/platform-tools:$_TASK_AI_ROOT/.tooling/android-sdk/emulator:$PATH"
fi
