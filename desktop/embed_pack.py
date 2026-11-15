"""
The desktop's second step for an indexed pack: embeds every piece DesktopPackBuild wrote, with the app's own model file
(embeddinggemma-2-740m.litertlm) through MediaPipe's Universal Embedder on the CPU, set up exactly as GemmaEmbedder.kt:
L2-normalised, 512 input tokens, "title: none | text: " before each piece. Measured against the phone's own vectors:
cosine 0.9995 or better. Several worker processes share the work; only pieces without a vector are embedded, so a
stopped run resumes.

Usage: python embed_pack.py <proasiste-index.db> <embeddinggemma-2-740m.litertlm> [--workers 12]
"""
import argparse, hashlib, multiprocessing as mp, sqlite3, tempfile, time
import numpy as np

MODEL_SHA256 = "e7a8a2204b91e0f96e92960e84a09a89212e1633dcb7575a9bf3378b4df77f4c"  # GemmaEmbedder.MODEL_SHA256
DIMENSIONS = 768
DOC = "title: none | text: "
BATCH = 64

_embedder = None


def _start(model):
    global _embedder
    from mediapipe.tasks.python.core.base_options import BaseOptions
    from mediapipe.tasks.python.retrieval.universal_embedder import UniversalEmbedder, UniversalEmbedderOptions
    options = UniversalEmbedderOptions(base_options=BaseOptions(model_asset_path=model, delegate=BaseOptions.Delegate.CPU),
                                       l2_normalize=True, max_input_length=512, cache_dir=tempfile.mkdtemp(prefix="eg2-"))
    _embedder = UniversalEmbedder.create_from_options(options)


def _vector(text):
    """GemmaEmbedder.embedText: a piece over the model's 512 tokens is embedded from its first 85% of words, again
    until it fits. The same rule as the app's, so both make the same vector."""
    t = text.strip()
    for _ in range(12):
        try:
            return _embedder.embed_text(DOC + t).embeddings[0].embedding
        except ValueError as e:
            if "exceeds maximum supported" not in str(e): raise
            words = t.split()
            t = " ".join(words[:max(1, int(len(words) * 0.85))])
    raise ValueError("piece still too long after cutting it down")


def _embed(batch):
    out = []
    for sha, ordinal, text in batch:
        v = np.array(_vector(text), dtype=np.float64)
        if v.shape != (DIMENSIONS,) or not np.isfinite(v).all() or np.linalg.norm(v) == 0:
            raise ValueError(f"bad embedding for {sha[:12]}#{ordinal}")
        out.append((np.asarray(v / np.linalg.norm(v), dtype="<f4").tobytes(), sha, ordinal))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pack"); ap.add_argument("model"); ap.add_argument("--workers", type=int, default=12)
    a = ap.parse_args()
    h = hashlib.sha256()
    with open(a.model, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""): h.update(b)
    if h.hexdigest() != MODEL_SHA256:
        raise SystemExit("This isn't the app's model file: its checksum doesn't match GemmaEmbedder.MODEL_SHA256.")

    db = sqlite3.connect(a.pack)
    db.execute("INSERT OR REPLACE INTO meta(name, value) VALUES ('model_sha256', ?)", (MODEL_SHA256,)); db.commit()
    todo = db.execute("SELECT sha256, ordinal, text FROM chunks WHERE vector IS NULL ORDER BY sha256, ordinal").fetchall()
    total = db.execute("SELECT COUNT(*) FROM chunks").fetchone()[0]
    print(f"{total} pieces, {len(todo)} to embed with {a.workers} workers", flush=True)
    batches = [todo[i:i + BATCH] for i in range(0, len(todo), BATCH)]
    started = time.time(); done = 0
    with mp.get_context("spawn").Pool(a.workers, initializer=_start, initargs=(a.model,)) as pool:
        for rows in pool.imap_unordered(_embed, batches):
            db.executemany("UPDATE chunks SET vector = ? WHERE sha256 = ? AND ordinal = ?", rows); db.commit()
            done += len(rows)
            if done % (BATCH * 50) < BATCH or done == len(todo):
                rate = done / max(time.time() - started, 1e-6)
                print(f"{done}/{len(todo)} · {rate:.0f} a second · {(len(todo) - done) / max(rate, 1e-6) / 60:.0f} min left", flush=True)
    left = db.execute("SELECT COUNT(*) FROM chunks WHERE vector IS NULL").fetchone()[0]
    print(f"done in {(time.time() - started) / 60:.1f} min; {left} pieces still without a vector", flush=True)


if __name__ == "__main__":
    main()
