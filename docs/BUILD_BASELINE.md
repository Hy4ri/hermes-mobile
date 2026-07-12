# Cassy Control — Build Baseline

## Source

- Upstream: `https://github.com/Hy4ri/hermes-mobile`
- Upstream tag: `v1.14.4`
- Pinned upstream commit: `cc917afcbd922380750cd96785e689fc99f35782`
- Product application ID: `com.akaro.cassycontrol`
- Namespace retained for source compatibility: `com.m57.hermescontrol`
- License: Apache-2.0 (upstream LICENSE retained)

## Reproducible toolchain

- JDK: Eclipse Temurin 21.0.11+10, installed at `/opt/data/jdk-21`
- Android SDK: `/opt/data/android-sdk`
- compileSdk / targetSdk: 36
- minSdk: 26
- Android Build Tools: 36.0.0
- Gradle: wrapper-pinned by the repository
- Kotlin/AGP/Compose: version-catalog-pinned in `gradle/libs.versions.toml`
- ktlint: repository/CI-pinned 1.2.1 at `/opt/data/.local/bin/ktlint`

## Build commands

```bash
export JAVA_HOME=/opt/data/jdk-21
export ANDROID_HOME=/opt/data/android-sdk
export ANDROID_SDK_ROOT=/opt/data/android-sdk
export GRADLE_USER_HOME=/opt/data/.gradle-cassy
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:/opt/data/.local/bin:$PATH"

./gradlew --no-daemon testDebugUnitTest lintDebug ktlintCheck assembleDebug
```

For release signing, load the local restricted signing environment. Never commit it:

```bash
set -a
. /opt/data/secure/cassy-control-signing.env
set +a
./gradlew --no-daemon \
  -PversionName=1.0.1-cassy \
  -PversionCode=101 \
  lintRelease ktlintCheck assembleRelease
```

## Build outputs

- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`
- Delivered release: `/opt/data/workspace/Cassy-Control-v1.0.1.apk`
- SHA-256: `d61d8456f22a3b6fb9458b33189e346d788b76c90f38deb4eef86016c43c21ce`

## Verification gates

1. `ktlintCheck`
2. all debug unit tests
3. Android lint
4. debug and release assembly
5. `apksigner verify --verbose --print-certs`
6. manifest/badging inspection with `aapt2 dump badging`
7. SHA-256 checksum generation
8. installation smoke test on an Android 16 / API 36 target when an emulator or device is available

## Verified release status

- Version: `1.0.1-cassy` (`versionCode` 101)
- Unit tests: 403 passed; 0 failed; 0 skipped
- Release lint: 0 errors (125 warnings and 2 hints, primarily upstream/dependency notices)
- APK: ZIP CRC valid, 402 entries, DEX present, arm64 SQLCipher present
- Signature: APK Signature Scheme v2, RSA 4096, one signer
- Device/emulator install: not run on this build host because `/dev/kvm` and an attached Android device are unavailable
