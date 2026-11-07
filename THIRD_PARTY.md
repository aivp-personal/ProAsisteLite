# Third-party components

Versions are pinned in the Gradle files and `native/build_native.py`.

- FalkorDB 6.0.4 (bundled in the APK): SSPL-1.0. Review before distributing the app to anyone.
- Redis 8.10.2 (bundled in the APK): check its licence terms before distributing the app to anyone.
- GNU C Library, libstdc++, libgcc, OpenSSL, zlib and zstd (bundled in the APK, from the FalkorDB image): their own licences. The glibc copy is modified by `native/patch_glibc.py`, and the LGPL has conditions for distributing it.
- EmbeddingGemma 2 model (not bundled, imported by the user): Google. Check the model's own licence terms before distributing.
- AndroidX, Jetpack Compose, Kotlin, Kotlin coroutines, MediaPipe Tasks Retrieval and LiteRT-LM: Apache-2.0.
- PdfBox-Android: Apache-2.0.
