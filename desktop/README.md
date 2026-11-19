# Indexed packs: index a large folder on this computer, search it on the phone

The phone indexes about 0.6 s a page. A folder of thousands of PDFs is faster to index here and copy over: the
computer reads and splits every PDF with the app's own code, embeds the pieces with the app's own model file
(EmbeddingGemma 2, `embeddinggemma-2-740m.litertlm`, on the CPU through MediaPipe 1.1.0, as the phone does), and writes
`proasiste-index.db` beside the PDFs. On the phone, **Documents → + → Import a folder** on that folder loads the index
first; each file whose contents match is then indexed in a moment instead of with the model.

Measured (2026-11-15): vectors made here match the phone's at cosine 0.9995 or better; reading runs at about 260 pages a
second; embedding at about 100 pieces a second; 20 rulings (1,317 pages, 11,669 pieces) took 5 s to read and 2 min to
embed, and indexed on the Fold in seconds with identical chunks and vectors. Don't use the full-size weights from
Hugging Face: their vectors differ (cosine ~0.985) because the phone's file is quantised.

## Making a pack

1. Put the PDFs in one folder (subfolders are fine; they become the files' assets on the phone). Copies with the same
   contents are read once.
2. Read and split them, with the app's own reader (run from `ProAsisteLite`):

   ```bash
   PACK_SRC="C:/path/to/folder" PACK_OUT="C:/path/to/folder/proasiste-index.db" gradle testDebugUnitTest --tests '*DesktopPackBuild' --rerun
   ```

   Progress goes to `proasiste-index.db.log`. A stopped run resumes.
3. Embed the pieces (resumes too):

   ```bash
   desktop/.venv/Scripts/python.exe -I desktop/embed_pack.py "C:/path/to/folder/proasiste-index.db" "C:/path/to/embeddinggemma-2-740m.litertlm" --workers 12
   ```

   The model file must be the app's (its checksum is checked).
4. Copy the folder to the phone, `proasiste-index.db` included, for example:

   ```bash
   adb push "C:/path/to/folder" /sdcard/Documents/ProAsisteLite/<name>
   ```

5. On the phone, in the project it belongs to: **Documents → + → Import a folder**, pick it. The status line says how many
   files came with their index ready.

A pack is tied to the app's index version (`GemmaEmbedder.INDEX_VERSION`): after a change to how text is split or
embedded, make it again; the phone refuses an old one. Files the pack doesn't cover (Word files aren't imported;
photos and recordings are embedded on the phone) are indexed on the phone as usual.

## Environment

`desktop/.venv` (Python 3.13): mediapipe 1.1.0 for the embedder; torch (CUDA), transformers 5.23 and
sentence-transformers 6.3 only for `compare_vectors.py`. `compare_litert.py` re-checks the match against a copy of the
phone's database.
