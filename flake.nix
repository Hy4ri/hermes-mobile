{
  description = "HermesControl - Android app for Hermes agent";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            android_sdk.accept_license = true;
            allowUnfree = true;
          };
        };

        buildToolsVersion = "37.0.0";
        androidSdk =
          (pkgs.androidenv.composeAndroidPackages {
            # Toolchain pinned to the latest available on nixos-unstable (rev ffb3c9b7)
            cmdLineToolsVersion = "22.0";
            platformToolsVersion = "37.0.1";

            # Build tools
            buildToolsVersions = [ buildToolsVersion ];

            # Target platforms: 37.0 provides both the SDK platform for
            # compileSdk 37 AND the published android-37.0 system image the
            # emulator needs to boot an AVD (no 37.1 image exists yet).
            # compileSdk/targetSdk in app/build.gradle.kts stay 37.
            platformVersions = [ "37.0" ];

            # Emulator + system images for local AVD testing
            includeEmulator = false;
            includeSystemImages = false;
            systemImageTypes = [ "google_apis" ];
            abiVersions = [ "x86_64" ];

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

        # Android CLI (agent tooling: Compose preview render, APK analysis,
        # device control). nixpkgs lags upstream, so pin the version here.
        # Upstream ships x86_64-linux and aarch64-darwin builds only.
        androidCliVersion = "1.1.16717390";
        androidCliHashes = {
          x86_64-linux = "sha256-s07Quu7/oqruJyDYJJG5dYNWluLlaEBEkHi+yIVDZWQ=";
          aarch64-darwin = "sha256-6cbhn17YFC4ol+C2i0duF2hjPldAqlS/wjR2fMJkAf4=";
        };
        androidCliPlatforms = {
          x86_64-linux = "linux_x86_64";
          aarch64-darwin = "darwin_arm64";
        };
        androidCli = pkgs.android-cli.overrideAttrs (old: {
          version = androidCliVersion;
          src = pkgs.fetchurl {
            url = "https://dl.google.com/android/cli/${androidCliVersion}/${androidCliPlatforms.${system}}/android-cli";
            hash = androidCliHashes.${system};
          };
          # `preview render` runs a bundled JRE that dlopens AWT's X11 libs.
          # NixOS has no global copies, so give them to the CLI only.
          nativeBuildInputs =
            (old.nativeBuildInputs or [ ])
            ++ pkgs.lib.optional pkgs.stdenv.hostPlatform.isLinux pkgs.makeWrapper;
          postInstall =
            (old.postInstall or "")
            + pkgs.lib.optionalString pkgs.stdenv.hostPlatform.isLinux ''
              wrapProgram $out/bin/android --prefix LD_LIBRARY_PATH : ${
                pkgs.lib.makeLibraryPath (
                  with pkgs;
                  [
                    libx11
                    libxext
                    libxi
                    libxrender
                    libxtst
                  ]
                )
              }
            '';
        });
      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs =
            # android-cli first: cmdline-tools also ships an `android`
            # bootstrap that downloads an unpinned CLI at runtime.
            pkgs.lib.optional (androidCliHashes ? ${system}) androidCli
            ++ (with pkgs; [
              # Java 21 (required for AGP 9.x / Gradle 9.x)
              jdk21

              # Android SDK (platforms, build-tools, platform-tools, emulator, system-images)
              androidSdk

              # Linting
              ktlint
            ]);

          # Point everything at the Nix-managed SDK
          ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
          ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
          JAVA_HOME = "${pkgs.jdk21}";

          # GRADLE_USER_HOME stays at the default ~/.gradle: Nix attrs are not
          # shell-expanded, so "$PWD/..." gave every checkout and worktree its
          # own cache and daemon pool, and idle daemons piled up in RAM.

          # shellHook = "";
        };
      }
    );
}
