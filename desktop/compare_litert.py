"""
The phone's own EmbeddingGemma 2 file (embeddinggemma-2-740m.litertlm) run on this computer through MediaPipe's
Universal Embedder, the same task and options the app uses (GemmaEmbedder.kt): L2-normalised, 512 input tokens,
"title: none | text: " before each document. Compares each vector with the one the phone stored for the same chunk,
and times it. Read-only on the database.

Usage: python compare_litert.py <proasiste.db copy> <model.litertlm> [--delegate CPU|GPU] [--n 300]
"""
import argparse, random, sqlite3, struct, tempfile, time
import numpy as np
from mediapipe.tasks.python.core.base_options import BaseOptions
from mediapipe.tasks.python.retrieval.universal_embedder import UniversalEmbedder, UniversalEmbedderOptions

DOC = "title: none | text: "

ap = argparse.ArgumentParser()
ap.add_argument("db"); ap.add_argument("model"); ap.add_argument("--delegate", default="CPU"); ap.add_argument("--n", type=int, default=300)
a = ap.parse_args()

con = sqlite3.connect(f"file:{a.db}?mode=ro", uri=True)
rows = con.execute("""SELECT s.building, k.text, k.vector FROM chunks k JOIN sources s ON s.id = k.source
                      WHERE s.type = 'pdf' AND k.text IS NOT NULL AND k.text <> '' AND s.building IN ('P03','P04','P90')""").fetchall()
random.seed(7)
sample = random.sample(rows, min(a.n, len(rows)))
phone = np.array([struct.unpack(f"<{len(v)//4}f", v) for _, _, v in sample], dtype=np.float32)

options = UniversalEmbedderOptions(base_options=BaseOptions(model_asset_path=a.model, delegate=getattr(BaseOptions.Delegate, a.delegate)),
                                   l2_normalize=True, max_input_length=512, cache_dir=tempfile.mkdtemp(prefix="eg2-"))
t0 = time.time()
emb = UniversalEmbedder.create_from_options(options)
print(f"loaded on {a.delegate} in {time.time() - t0:.1f} s")


def vec(text):
    r = emb.embed_text(DOC + text.strip())
    v = np.array(r.embeddings[0].embedding, dtype=np.float32)
    return v / np.linalg.norm(v)


vec("warm up")
t0 = time.time()
desk = np.array([vec(t) for _, t, _ in sample])
dt = time.time() - t0
cos = (desk * phone).sum(1)
print(f"{len(sample)} chunks: cosine to the phone's vectors min {cos.min():.5f}  p05 {np.percentile(cos, 5):.5f}  median {np.median(cos):.5f}")
print(f"identical within 0.999: {(cos >= 0.999).mean() * 100:.1f}%")
print(f"speed: {dt * 1000 / len(sample):.1f} ms a chunk, one at a time (the Fold takes 88 ms)")
