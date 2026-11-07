# ProAsisteLite

An Android app that searches your PDFs on the phone, with SQLite for documents and search and FalkorDB for the graph.
Nothing leaves the device: the app has no internet permission.

## What it does

- Keeps documents in separate projects, and deletes files or whole projects with everything derived from them.
- Accepts PDFs with selectable text, up to 100 MB and 1000 pages each. Scanned PDFs aren't supported yet.
- Finds passages by meaning and by exact words, and shows the document and page for every result.
- Runs a graph per project in FalkorDB, where every edge names its document and page. Nothing fills it yet.

## Build

Needs JDK 17, Gradle 8.11, the Android SDK (path in `local.properties`), Python 3 and Docker. arm64 phones only.

```bash
python native/build_native.py
```

```bash
gradle assembleDebug testDebugUnitTest
```

```bash
gradle connectedDebugAndroidTest
```

The last one runs the graph tests against real FalkorDB on a connected phone. The app needs the model file
`embeddinggemma-2-740m.litertlm` (485 MB) on first run, and checks its checksum before using it.

## How FalkorDB runs on Android

FalkorDB has no Android build. FalkorDBLite, its embedded package, needs Python or Node, so the app starts the same two
parts itself: the official Linux arm64 Redis and FalkorDB module, as a child process on a private socket.

Those binaries expect glibc, so the app carries a copy. Android's app sandbox refuses a few system calls glibc makes:

| Piece | Why |
|---|---|
| `native/launcher.c` | Glibc's loader calls `set_robust_list` and `rseq` before anything can catch the refusal, so this installs a handler first and starts the loader in the same process |
| `native/patch_glibc.py` | `fork` and new threads make those calls with signals blocked, where a refusal kills the process, so the app's glibc copy skips them |
| `FalkorServer` | Android installs only `lib*.so` files, so it links them back to their real names, then starts and supervises the server |

This isn't a supported setup: an Android or FalkorDB update can break it. So only `falkor/` knows about it, the app talks to
`GraphStore`, and search keeps working from SQLite if the server won't start.

## How the code is organised

| Folder | Job |
|---|---|
| `data` | SQLite database, numbered migrations, projects, documents, passages and the graph cleanup queue |
| `importing` | Copying files in and enforcing the limits |
| `pdf` | Reading pages of text |
| `text` | Splitting pages into passages, and search terms |
| `embedding` | The on-device embedding model |
| `indexing` | Turning pending documents into passages |
| `search` | Low-memory hybrid search |
| `graph` | `GraphStore`, its FalkorDB implementation and the cleanup of deleted data |
| `falkor` | Starting the server and talking to it |
| `ui` | The screen and its state |

## Rules for changes

- A schema change is a new entry in `AppDatabase.MIGRATIONS`. Never edit one that has shipped.
- Anything derived from a document must disappear when the document does. In SQLite that's foreign keys; for the graph it's
  the `graph_cleanups` queue, written in the same transaction as the delete.
- Every search result and graph edge keeps its document and page.
- New features come with tests.
