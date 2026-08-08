{
  description = "HermesControl - Android app for Hermes agent";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = {
    self,
    nixpkgs,
    flake-utils,
  }:
    flake-utils.lib.eachDefaultSystem (
      system: let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            android_sdk.accept_license = true;
            allowUnfree = true;
          };
        };

        buildToolsVersion = "36.0.0";
        androidSdk =
          (pkgs.androidenv.composeAndroidPackages {
            # Command-line & platform tools
            cmdLineToolsVersion = "11.0";
            platformToolsVersion = "36.0.0";

            # Build tools
            buildToolsVersions = [buildToolsVersion];

            # Target platforms (36 required by AGP 9.0.1 / compileSdk 36)
            platformVersions = ["36"];

            # Emulator + system images for local AVD testing
            includeEmulator = true;
            includeSystemImages = true;
            systemImageTypes = ["google_apis"];
            abiVersions = ["x86_64"];

            # NDK (not needed for this project, but handy)
            includeNDK = false;

            # Extra licenses
            extraLicenses = [
              "android-googletv-license"
              "android-sdk-arm-dbt-license"
              "android-sdk-license"
              "android-sdk-preview-license"
              "google-gdk-license"
              "intel-android-extra-license"
              "intel-android-sysimage-license"
              "mips-android-sysimage-license"
            ];
          }).androidsdk;
      in {
        devShells.default = pkgs.mkShell {
          buildInputs = with pkgs; [
            # Java 21 (required for AGP 9.x / Gradle 9.x)
            jdk21

            # Android SDK (platforms, build-tools, platform-tools, emulator, system-images)
            androidSdk
          ];

          # Point everything at the Nix-managed SDK
          ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
          ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
          JAVA_HOME = "${pkgs.jdk21}";

          # Gradle needs a writable home
          GRADLE_USER_HOME = "$PWD/.gradle-home";

          shellHook = ''
            echo " HermesControl Android dev shell"
            echo "   Java:         $(java -version 2>&1 | head -1)"
            echo "   ANDROID_HOME: $ANDROID_HOME"
            echo ""

            # Ensure Android CLI tools and local user binaries are on PATH
            export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$HOME/.local/bin:$PATH"

            # Writable gradle home
            mkdir -p "$GRADLE_USER_HOME"

            # ADB Screen Resolution Aliases
            alias avd-phone='adb shell wm size 1440x3120 && adb shell wm density 500'
            alias avd-phone-fhd='adb shell wm size 1080x2400 && adb shell wm density 420'

            alias avd-tablet='adb shell wm size 1600x2560 && adb shell wm density 320'
            alias avd-tablet-land='adb shell wm size 2560x1600 && adb shell wm density 320'

            alias avd-reset='adb shell wm size reset && adb shell wm density reset'

            # HermesControl app logcat (filtered to the app process only)
            alias logcat-app='adb logcat --pid=$(adb shell pidof com.m57.hermescontrol)'

            echo "Display Presets Loaded:"
            echo "  avd-phone        -> 1440x3120 (500 DPI) [QHD+ Flagship]"
            echo "  avd-phone-fhd    -> 1080x2400 (420 DPI) [FHD+ Flagship]"
            echo "  avd-tablet       -> 1600x2560 (320 DPI) [Portrait Tablet]"
            echo "  avd-tablet-land  -> 2560x1600 (320 DPI) [Landscape Tablet]"
            echo "  avd-reset        -> Reset size & density back to AVD defaults"
            echo "  logcat-app       -> Logcat for the HermesControl app process only"
            echo ""
          '';
        };
      }
    );
}
