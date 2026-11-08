# Third-party components

The members licence applies to The AI Automators app code, not the components below.

- EmbeddingGemma 2 model: Google, Apache-2.0. Downloaded separately from the LiteRT community repository linked in README.
- AndroidX, Jetpack Compose, Kotlin, Kotlin coroutines, MediaPipe Tasks Retrieval and LiteRT-LM: Apache-2.0. Versions are pinned in the Gradle files.
- PdfBox-Android: Apache-2.0.
- ML Kit GenAI Prompt API (`com.google.mlkit:genai-prompt`, beta): Google, ML Kit terms of service. It calls Gemini Nano, which Android AICore provides on supported phones; the model is not bundled in the APK.
- Open Sauce Sans (Light, Regular, Medium, SemiBold): The Open Sauce Sans Authors, SIL Open Font License 1.1, from github.com/marcologous/Open-Sauce-Fonts. Licence in `licenses/OpenSauceSans-OFL.txt`.
- Noto Serif (variable): The Noto Project Authors, SIL Open Font License 1.1, from github.com/google/fonts. Licence in `licenses/NotoSerif-OFL.txt`.
- Jev (TypeSafe) through OpenRouter: an optional cloud service used with your own OpenRouter key, under their terms. No code from it is bundled.
- Qwen 3.8 Flash (Alibaba) through OpenRouter: an optional cloud service for reading tables, used with your own OpenRouter key, under their terms. No code from it is bundled.
- The bundled photos and voice notes were synthetically generated for this fictional maintenance dataset; reports and metadata are fictional. They are example material, not real inspection records.

The provided APK contains runtime dependencies under their respective licences. Obtain dependency notices from their published artefacts when producing a release of your own. The full source and dependency coordinates are included for inspection and rebuilding.
