#!/usr/bin/env bash
# Install Debian trixie's OpenJDK 21 into DEST: the JDK build that F-Droid's build server uses.
# R8 output depends on the JDK build (Temurin and Debian 21.0.13+9 differ), so the
# release APK must come from Debian's JDK to pass F-Droid's reproducible build check (#1823).
set -euo pipefail

DEST="${1:?usage: install_debian_jdk.sh DEST}"
mkdir -p "$DEST"

docker run --rm -v "$DEST:/out" debian:trixie bash -euo pipefail -c "
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-21-jdk-headless >/dev/null
  JDK=/usr/lib/jvm/java-21-openjdk-amd64
  # Debian links config into /etc and source into absent packages; copy real files only.
  find \"\$JDK\" -xtype l -delete
  cp -rL \"\$JDK/.\" /out/
  chown -R $(id -u):$(id -g) /out
  dpkg-query -W openjdk-21-jdk-headless
"

"$DEST/bin/java" -version 2>&1 | tee /dev/stderr | grep -- -Debian >/dev/null
