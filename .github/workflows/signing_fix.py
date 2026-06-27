import os

files = [
    r".github\workflows\build.yml",
    r".github\workflows\build_quick.yml",
    r".github\workflows\release.yml",
]

keystore_step = """      - name: Generate test keystore for CI
        run: |
          keytool -genkeypair -v \\
            -keystore app/ci-test.keystore \\
            -storepass android \\
            -alias androiddebugkey \\
            -keypass android \\
            -keyalg RSA \\
            -keysize 2048 \\
            -validity 10000 \\
            -dname "CN=CI Test,O=Metrolist,C=US"

"""

signing_block = """      - name: Check signing secrets
        id: check_secrets
        run: |
          if [ -n "${{ secrets.ANDROID_SIGNING_KEY }}" ] || [ -n "${{ secrets.KEYSTORE }}" ]; then
            echo "available=true" >> "$GITHUB_OUTPUT"
          else
            echo "available=false" >> "$GITHUB_OUTPUT"
          fi

      - name: Sign APK
        if: steps.check_secrets.outputs.available == 'true'
        uses: ilharp/sign-android-release@v2.0.0
        with:
          releaseDir: app/build/outputs/apk/${{ matrix.variant }}/release/
          signingKey: ${{ secrets.ANDROID_SIGNING_KEY || secrets.KEYSTORE }}
          keyAlias: ${{ secrets.ANDROID_KEY_ALIAS || secrets.KEY_ALIAS }}
          keyStorePassword: ${{ secrets.ANDROID_KEYSTORE_PASSWORD || secrets.KEYSTORE_PASSWORD }}
          keyPassword: ${{ secrets.ANDROID_KEY_PASSWORD || secrets.KEY_PASSWORD }}
          buildToolsVersion: 35.0.0

      - name: Sign APK with test keystore
        if: steps.check_secrets.outputs.available != 'true'
        uses: ilharp/sign-android-release@v2.0.0
        with:
          releaseDir: app/build/outputs/apk/${{ matrix.variant }}/release/
          signingKey: app/ci-test.keystore
          keyAlias: androiddebugkey
          keyStorePassword: android
          keyPassword: android
          buildToolsVersion: 35.0.0"""

quick_signing_block = """      - name: Check signing secrets
        id: check_secrets
        run: |
          if [ -n "${{ secrets.ANDROID_SIGNING_KEY }}" ] || [ -n "${{ secrets.KEYSTORE }}" ]; then
            echo "available=true" >> "$GITHUB_OUTPUT"
          else
            echo "available=false" >> "$GITHUB_OUTPUT"
          fi

      - name: Sign APK
        if: steps.check_secrets.outputs.available == 'true'
        uses: ilharp/sign-android-release@v2.0.0
        with:
          releaseDir: app/build/outputs/apk/foss/release/
          signingKey: ${{ secrets.ANDROID_SIGNING_KEY || secrets.KEYSTORE }}
          keyAlias: ${{ secrets.ANDROID_KEY_ALIAS || secrets.KEY_ALIAS }}
          keyStorePassword: ${{ secrets.ANDROID_KEYSTORE_PASSWORD || secrets.KEYSTORE_PASSWORD }}
          keyPassword: ${{ secrets.ANDROID_KEY_PASSWORD || secrets.KEY_PASSWORD }}
          buildToolsVersion: 35.0.0

      - name: Sign APK with test keystore
        if: steps.check_secrets.outputs.available != 'true'
        uses: ilharp/sign-android-release@v2.0.0
        with:
          releaseDir: app/build/outputs/apk/foss/release/
          signingKey: app/ci-test.keystore
          keyAlias: androiddebugkey
          keyStorePassword: android
          keyPassword: android
          buildToolsVersion: 35.0.0"""

moved_unsigned_removal = {
    r".github\workflows\build.yml": """      - name: Upload Signed APK
        uses: actions/upload-artifact@v6
        with:
          name: ${{ matrix.variant == 'gms' && 'app-with-Google-Cast' || 'app-release' }}
          path: app/build/outputs/apk/${{ matrix.variant }}/release/out/*

  build_debug:""",
    r".github\workflows\build_quick.yml": """      - name: Upload Signed APK
        uses: actions/upload-artifact@v6
        with:
          name: app-universal-test-release
          path: app/build/outputs/apk/foss/release/out/*
""",
    r".github\workflows\release.yml": """      - name: Upload Signed APKs
        uses: actions/upload-artifact@v6
        with:
          name: ${{ matrix.variant == 'gms' && 'Metrolist-with-Google-Cast' || matrix.variant == 'izzy' && 'Metrolist-izzy' || 'Metrolist' }}
          path: app/build/outputs/apk/${{ matrix.variant }}/release/out/*.apk

  create-release:""",
}

for path in files:
    with open(path, "r") as f:
        content = f.read()

    # Insert keystore step before the build step
    if "      - name: Build Release APK and Run Lint" in content:
        content = content.replace(
            "      - name: Build Release APK and Run Lint",
            keystore_step + "      - name: Build Release APK and Run Lint",
        )
    elif "      - name: Build Release APK (No Lint)" in content:
        content = content.replace(
            "      - name: Build Release APK (No Lint)",
            keystore_step + "      - name: Build Release APK (No Lint)",
        )
    elif "      - name: Build and Lint Release APK" in content:
        content = content.replace(
            "      - name: Build and Lint Release APK",
            keystore_step + "      - name: Build and Lint Release APK",
        )

    # Replace signing block
    if "Check signing secrets" in content:
        if path == r".github\workflows\build_quick.yml":
            content = content.replace(signing_block.replace("${{ matrix.variant }}", "foss"), quick_signing_block)
        else:
            content = content.replace(signing_block, signing_block)

    # Remove conditional uploads and make them unconditional
    for old, new in moved_unsigned_removal.items():
        if path == old and old in content:
            content = content.replace(old, new)

    with open(path, "w") as f:
        f.write(content)

    print(f"Updated {path}")
