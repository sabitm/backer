{
  description = "Nix flake for native Android (Kotlin) dev";

  inputs = {
    nixconfig.url = "github:sabitm/nix-config";
    nixpkgs.follows = "nixconfig/nixpkgs";
  };

  outputs = { self, nixpkgs, ... }:
  let
    system = "x86_64-linux";
    pkgs = import nixpkgs {
      inherit system;
      config = {
        allowUnfree = true;
        android_sdk.accept_license = true;
      };
    };

    libPath = with pkgs; lib.makeLibraryPath [
      libGL
      libxkbcommon
      wayland
      fontconfig
    ];

    comp = pkgs.androidenv.composeAndroidPackages {
      buildToolsVersions = [ "35.0.0" "36.0.0" ];
      platformVersions = [ "33" "34" "35" "36" ];
      includeCmake = false;
      includeNDK = false;
      includeSystemImages = false;
      includeEmulator = false;
    };
  in
  {
    devShells.${system}.default = pkgs.mkShell {
      buildInputs = [
        comp.androidsdk
        pkgs.javaPackages.compiler.openjdk21
        pkgs.gradle
        pkgs.kotlin
      ];

      LD_LIBRARY_PATH = libPath;
      JAVA_HOME = "${pkgs.javaPackages.compiler.openjdk21}";
      ANDROID_HOME = "$HOME/.cache/backer/android-sdk";
      ANDROID_SDK_ROOT = "$HOME/.cache/backer/android-sdk";

      # Gradle and sdkmanager need a writable SDK, so stage a copy
      # in ~/.cache on first shell entry. local.properties is git-ignored.
      shellHook = ''
        NIX_SDK="${comp.androidsdk}/libexec/android-sdk"
        CACHE_SDK="$HOME/.cache/backer/android-sdk"
        STAMP="$CACHE_SDK/.nix-sdk-stamp"
        if [ ! -f "$STAMP" ] || [ "$(cat "$STAMP")" != "$NIX_SDK" ]; then
          echo "Staging writable Android SDK copy..."
          rm -rf "$CACHE_SDK"
          mkdir -p "$CACHE_SDK"
          cp -r "$NIX_SDK"/. "$CACHE_SDK"/
          chmod -R u+w "$CACHE_SDK"
          echo "$NIX_SDK" > "$STAMP"
        fi
        printf 'sdk.dir=%s\n' "$CACHE_SDK" > local.properties
        SDKMANAGER=$(ls -d "$CACHE_SDK"/cmdline-tools/*/bin/sdkmanager 2>/dev/null | sort | tail -1)
        if [ -n "$SDKMANAGER" ]; then
          yes | "$SDKMANAGER" --sdk_root="$CACHE_SDK" --licenses > /dev/null 2>&1 || true
        fi
        echo "Android SDK: $CACHE_SDK"
        echo "Java: ${pkgs.javaPackages.compiler.openjdk21}"
      '';
    };
  };
}
