#!/usr/bin/env sh
set -eu

if ! command -v gradle >/dev/null 2>&1; then
    printf '%s\n' 'Gradle 8.13 is required. Install Gradle or use the included GitHub Actions workflow.' >&2
    exit 1
fi

gradle --no-daemon clean :app:assembleDebug
