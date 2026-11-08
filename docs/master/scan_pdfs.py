"""Read-only scan of the legal PDF collection: the role each file claims, from its name and its first page.
Usage: python -I scan_pdfs.py <root> <out.csv>"""
import csv
import os
import re
import sys
import time

from pypdf import PdfReader

sys.stdout.reconfigure(encoding="utf-8")
root, out = sys.argv[1], sys.argv[2]
ROLE = re.compile(r"Rol(?:es)?\s*N?[°º]?\s*(\d{1,3}(?:\.\d{3})+|\d{1,6})(?![\d.])(?:\s*-\s*(\d{2,4}))?(?:\s*-?\s*([A-Z]{3,4}))?", re.I)
NAME_ROLE = re.compile(r"(?<!\d)(\d{1,3}(?:[._]\d{3})+|\d{3,6})\s*[-_]\s*(\d{2,4})(?:\s*[-_]?\s*([A-Z]{3,4}))?", re.I)
COURT = [("TC", re.compile(r"tribunal\s+constitucional", re.I)), ("CS", re.compile(r"corte\s+suprema", re.I)),
         ("CA", re.compile(r"corte\s+de\s+apelaciones", re.I)), ("TER", re.compile(r"tribunal\s+electoral", re.I))]

rows, start = [], time.time()
files = [os.path.join(d, f) for d, _, fs in os.walk(root) for f in fs if f.lower().endswith(".pdf")]
for i, path in enumerate(files):
    rel = os.path.relpath(path, root)
    name_hit = NAME_ROLE.search(os.path.basename(path))
    first, pages, court, page_hit, err = "", 0, "", None, ""
    try:
        r = PdfReader(path)
        pages = len(r.pages)
        first = (r.pages[0].extract_text() or "")[:3000] if pages else ""
        court = next((c for c, rx in COURT if rx.search(first)), "")
        page_hit = ROLE.search(first)
    except Exception as e:
        err = type(e).__name__
    rows.append({
        "file": rel, "pages": pages, "court": court, "error": err,
        "name_number": (name_hit.group(1).replace("_", ".") if name_hit else ""), "name_year": (name_hit.group(2) if name_hit else ""), "name_suffix": (name_hit.group(3) or "").upper() if name_hit else "",
        "page_number": (page_hit.group(1) if page_hit else ""), "page_year": (page_hit.group(2) or "") if page_hit else "", "page_suffix": (page_hit.group(3) or "").upper() if page_hit else "",
        "first_chars": len(first),
    })
    if i % 500 == 0: print(f"{i}/{len(files)} {time.time() - start:.0f}s", flush=True)
with open(out, "w", newline="", encoding="utf-8") as f:
    w = csv.DictWriter(f, fieldnames=list(rows[0]))
    w.writeheader(); w.writerows(rows)
print(f"{len(rows)} PDFs scanned in {time.time() - start:.0f}s -> {out}")
