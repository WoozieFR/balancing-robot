#!/usr/bin/env bash
# Shared Android build environment for balancing-robot.

export ANDROID_TOOLCHAIN="${ANDROID_TOOLCHAIN:-/home/woozie/robot/android-toolchain}"
export ANDROID_HOME="${ANDROID_HOME:-/home/woozie/robot/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME="${JAVA_HOME:-$ANDROID_TOOLCHAIN/jdk-17}"
export GRADLE_HOME="${GRADLE_HOME:-$ANDROID_TOOLCHAIN/gradle-9.6.0}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$GRADLE_HOME/bin:$PATH"
