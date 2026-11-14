# ProAsisteLite: offline document search with a project graph

Built on the Plantroom members starter app from The AI Automators. A native Android app that searches photos, text-based PDFs and voice notes with EmbeddingGemma 2 on the device, writes short cited answers with Gemini Nano, and keeps each project's graph in the same SQLite database. Includes Kotlin/Compose source and a fictional sample collection.

## What differs from Plantroom

- Search holds only vectors in compact arrays and reads word counts from a stored word index, so large projects fit in the app's memory.
- The database schema moves forward through numbered migrations.
- A file already in a project is not imported twice.
- Add evidence → Import a folder queues every PDF, photo and recording in a folder and its subfolders, then imports and indexes them in a background service with a notification and a Pause button. It keeps going with the screen off or the app closed, and resumes where it stopped. Each file's subfolder becomes its asset, so search can be filtered by folder.
- Settings → Who writes answers chooses, per project, Gemini Nano on the phone or a model from OpenRouter's catalog; each answer says which model wrote it, what was sent and what it cost. Incognito projects always use Nano.
- Library → Tags… tags files by rules (subfolder, year in the file name, words in the name), by AI choosing from tags you describe, or by hand. Tag chips with counts filter the Library and search.
- A PDF with no text on any page is marked as a scan that needs OCR instead of failing; a PDF with some scanned pages is indexed, and the Library says how many pages couldn't be read.
- `graph/Graph.kt` stores nodes and links per project. Types are plain text, so any schema fits, and every link names the source and page it came from. Multi-step questions run as recursive SQL queries with a step limit and loop protection. Deleting a file removes its links.
- Add evidence offers two ways to fill the graph: Build graph with DeepSeek, or Import a graph file. A court ruling is detected and read in legal mode: two passes, fixed types and relations, and stricter checks that hold doubtful facts back. Up to 3 matching facts join the passages for an answer, cited as graph facts.
- A question asking for a list (which rulings were cited, which justices sat, what was decided) is answered directly from the graph, with no model writing it.
- Jev has its own switch in settings, off by default.
- Cloud table reading is removed. Tables found on the phone are still indexed row by row.

## Install the included app

1. Use an Android 12 or newer device with around 1.5 GB free. Extract the ZIP on your computer.
2. Download `embeddinggemma-2-740m.litertlm` from the official [LiteRT community model repository](https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/tree/main). Use the full 740M model, not a text-only variant.
3. Transfer the APK and the model file to your phone's Downloads folder by USB. Open the APK in your file manager. Android may ask you to permit this installation. This is a locally signed prototype, not a Play Store app.
4. Open ProAsisteLite, choose Set up local search, then Import model file. Select the model from Downloads. The expected SHA256 is `e7a8a2204b91e0f96e92960e84a09a89212e1633dcb7575a9bf3378b4df77f4c`.
5. Select Mercer Court and Index collection. Repeat for Calder House. Keep the app in the foreground during indexing. Once ready, disconnect and search offline.

Try `blocked ventilation` in Mercer Court, or `cracked rubber foot under a water pump` in Calder House. Use a photo query to find visually related evidence. Tap a result to open its original. Add your own evidence and index it before searching.

The additional `tap-hose-photos.zip` contains the four synthetic hose photographs used in the filmed photo-search example. Copy it to Downloads, then use Add evidence, Import a building pack, and index the new records in Library. These are fictional examples, not plumbing guidance.

## Build and customise

Install JDK 17, Android SDK platform 35 and Gradle 8.11.1. Set ANDROID_HOME to your SDK folder, or create your own `local.properties` with `sdk.dir`. No machine-specific SDK location is included.

From this directory:

```sh
gradle assembleDebug
```

The build downloads its Android dependencies, bundles the unpacked `sample-data` into the app and writes `app/build/outputs/apk/debug/app-debug.apk`. You can also import the project into Android Studio using a local Gradle 8.11.1 installation. A Gradle wrapper is not bundled. Review SDK licences yourself during installation.

The sample source files are in `sample-data`. App behaviour and screens are in `app/src/main/java/com/proasiste/lite`. Start by replacing the fictional buildings and sample collection, then adapt the workflow for your use case.

## Costs, privacy and limits

There are no inference API fees and no account is needed. Internet is used on your computer for the model and build downloads. Search and answers run on the phone. The one exception is optional: if you save an OpenRouter key in settings and turn Jev on, Jev reranks passages and checks citations in the cloud, which sends your question, passages and the answer's sentences to OpenRouter and TypeSafe. With a saved key, Build graph with DeepSeek in Add evidence sends the text of the project's indexed PDFs to OpenRouter and DeepSeek, and keeps only facts that pass checks against the cited page; the status line shows how many pages were sent and what it cost. Without a key, and always in incognito projects, the app makes no such calls. The model, source files and index stay in app-private storage; uninstalling deletes them. The keyboard's speech-to-text feature is separate from this app and its offline availability depends on your keyboard and downloaded language packs.

Search quality and speed vary with the device and files. This is a starter project, not a production maintenance or diagnostic system. Similarity is not a diagnosis or an asset identity. PDFs must contain selectable text, with a 1,000-page/100 MB limit; scanned PDFs need OCR elsewhere. Audio imports are limited to 60 seconds/50 MB; in-app recordings stop at 55 seconds. External building packs allow up to 500 records/600 MB uncompressed. A folder import takes up to 20,000 files; indexing runs in the background at roughly 0.4–0.6 s per PDF page on a Pixel 10 Pro Fold.

The video demonstrates a Samsung S21 Ultra. The package does not promise compatibility with every Android 12 device. CPU is the default backend.

## Licence

Code is under the community Members License in `LICENSE.md`. The model and third-party libraries retain their own terms, listed in `THIRD_PARTY.md`. The bundled collection is synthetic and fictional. Model weights, signing keys, SDKs and personal phone data are not included.
