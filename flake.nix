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

            # Extra packages
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

            # Kotlin compiler
            kotlin

            # Gradle
            gradle

            # Useful utilities
            ktlint # Kotlin linter
          ];

          # Point everything at the Nix-managed SDK
          ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
          ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
          JAVA_HOME = "${pkgs.jdk21}";

          # Gradle needs a writable home
          GRADLE_USER_HOME = "$PWD/.gradle-home";

          shellHook = ''
            echo " HermesControl Android dev shell"
            echo "   Java:              $(java -version 2>&1 | head -1)"
            echo "   Kotlin:            $(kotlin -version 2>&1)"
            echo "   Gradle:            $(gradle --version 2>&1 | grep '^Gradle' || echo 'available')"
            echo "   ANDROID_HOME: $ANDROID_HOME"
            echo ""

            # Make sure the android CLI from ~/.local/bin is on PATH
            export PATH="$HOME/.local/bin:$PATH"

            # Writable gradle home
            mkdir -p "$GRADLE_USER_HOME"
          '';
        };
      }
    );
}
