"""
Does EmbeddingGemma 2 on this computer make the same vectors as the app on the phone?

Reads chunks and the vectors the phone stored for them from a copy of the app's database, embeds the same text here
(same document prefix, same 512-token limit), and reports how close each pair is and whether search ranks the same.
Also times embedding on the GPU. Read-only on the database.

Usage: python compare_vectors.py <proasiste.db copy> [--dtype float32|bfloat16] [--n 600]
"""
import argparse, random, sqlite3, struct, time
import numpy as np
import torch
from sentence_transformers import SentenceTransformer

MODEL = r"C:\Users\arman\.cache\huggingface\hub\models--google--embeddinggemma-2\snapshots\f9972ac22e696a105c482202646979e33e4b5a1e"
DOC = "title: none | text: "          # GemmaEmbedder.embedText, isQuery = false
QUERY = "task: question answering | query: "  # GemmaEmbedder.embedText, isQuery = true

ap = argparse.ArgumentParser()
ap.add_argument("db"); ap.add_argument("--dtype", default="float32"); ap.add_argument("--n", type=int, default=600)
a = ap.parse_args()

con = sqlite3.connect(f"file:{a.db}?mode=ro", uri=True)
rows = con.execute("""SELECT s.building, k.text, k.vector FROM chunks k JOIN sources s ON s.id = k.source
                      WHERE s.type = 'pdf' AND k.text IS NOT NULL AND k.text <> '' AND s.building IN ('P03','P04','P90')""").fetchall()
random.seed(7)
sample = random.sample(rows, min(a.n, len(rows)))
phone = np.array([struct.unpack(f"<{len(v)//4}f", v) for _, _, v in sample], dtype=np.float32)
texts = [DOC + t.strip() for _, t, _ in sample]

model = SentenceTransformer(MODEL, device="cuda", model_kwargs={"dtype": getattr(torch, a.dtype)})
model.max_seq_length = 512
model.encode(texts[:8])  # warm up
torch.cuda.synchronize(); t0 = time.time()
desk = model.encode(texts, batch_size=64, normalize_embeddings=True, convert_to_numpy=True).astype(np.float32)
torch.cuda.synchronize(); dt = time.time() - t0

cos = (desk * phone).sum(1)
print(f"{len(sample)} chunks · dtype {a.dtype}")
print(f"cosine desktop vs phone: min {cos.min():.4f}  p05 {np.percentile(cos, 5):.4f}  median {np.median(cos):.4f}  mean {cos.mean():.4f}")
by = {}
for (b, _, _), c in zip(sample, cos): by.setdefault(b, []).append(c)
for b, cs in sorted(by.items()): print(f"  {b}: {len(cs)} chunks, median {np.median(cs):.4f}, min {min(cs):.4f}")
print(f"speed: {len(sample) / dt:.0f} chunks/s on the GPU ({dt * 1000 / len(sample):.2f} ms a chunk; the Fold takes 88 ms)")

# Ranking: each query against all sampled chunks, once with the phone's vectors and once with the desktop's.
queries = ["¿Qué norma se impugna en el requerimiento?", "¿Quién redactó la sentencia?", "plazo para apelar", "votos disidentes del tribunal",
           "How do I clean the egg cooker?", "What is the warranty?", "¿Se acoge o se rechaza el requerimiento?", "debido proceso y derecho a defensa",
           "descale the machine", "proporcionalidad de la sanción", "suspension of the proceedings", "requisitos de admisibilidad"]
q = model.encode([QUERY + x for x in queries], normalize_embeddings=True, convert_to_numpy=True).astype(np.float32)
agree = []
for i, x in enumerate(queries):
    top_p = set(np.argsort(-(phone @ q[i]))[:6]); top_d = set(np.argsort(-(desk @ q[i]))[:6])
    agree.append(len(top_p & top_d) / 6)
print(f"top-6 overlap, phone vs desktop document vectors: mean {np.mean(agree):.2f}, min {min(agree):.2f}")
