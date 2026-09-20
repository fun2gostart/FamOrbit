#!/bin/sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
VERSION=9.4.1
CACHE="$APP_HOME/.gradle-local"
DIST="$CACHE/gradle-$VERSION"
ZIP="$CACHE/gradle-$VERSION-bin.zip"
if [ ! -x "$DIST/bin/gradle" ]; then
  mkdir -p "$CACHE"
  if [ ! -f "$ZIP" ]; then
    echo "Downloading Gradle $VERSION..."
    if command -v curl >/dev/null 2>&1; then
      curl -L --fail --retry 2 -o "$ZIP" "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip"
    elif command -v wget >/dev/null 2>&1; then
      wget -O "$ZIP" "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip"
    else
      echo "curl or wget is required to bootstrap Gradle $VERSION." >&2
      exit 1
    fi
  fi
  rm -rf "$CACHE/gradle-$VERSION.tmp"
  mkdir -p "$CACHE/gradle-$VERSION.tmp"
  unzip -q "$ZIP" -d "$CACHE/gradle-$VERSION.tmp"
  mv "$CACHE/gradle-$VERSION.tmp/gradle-$VERSION" "$DIST"
  rmdir "$CACHE/gradle-$VERSION.tmp"
fi
exec "$DIST/bin/gradle" "$@"
