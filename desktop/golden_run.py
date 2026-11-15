"""
Asks the golden questions in ProAsisteLite on the phone and records each answer, for grading against the golden answers.

Drives the app through adb and uiautomator: checks the open project before every question (stops if it isn't the one
asked for), types the question (accents and ¿? dropped: the app folds them anyway), runs the search, waits for the
answer to finish, and saves the answer card (writer, text, cited sources) and the search timing to a JSON file after
each question, so a stopped run resumes. Nothing is graded here.

Usage: python golden_run.py <golden.xlsx> <results.json> --project P04 [--only Q01,Q02]
"""
import argparse, json, os, re, subprocess, sys, time, unicodedata
import openpyxl

ADB = r"C:\Users\arman\android-dev\sdk\platform-tools\adb.exe"
os.environ["MSYS_NO_PATHCONV"] = "1"
sys.stdout.reconfigure(encoding="utf-8")


def sh(*a): return subprocess.run([ADB, *a], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def nodes():
    for _ in range(4):
        sh("shell", "uiautomator", "dump", "/sdcard/ui.xml"); x = sh("exec-out", "cat", "/sdcard/ui.xml")
        if "<hierarchy" in x: break
        time.sleep(1)
    out = []
    for m in re.finditer(r"<node [^>]*>", x):
        n = m.group(0); g = lambda k: (re.search(k + r'="([^"]*)"', n) or [None, ""])[1]
        b = [int(v) for v in re.findall(r"\d+", g("bounds"))]
        out.append((g("text").replace("&#10;", "\n").replace("&amp;", "&").replace("&quot;", '"').replace("&lt;", "<").replace("&gt;", ">"), g("content-desc"), b))
    return out


def tap_xy(x, y): sh("shell", "input", "tap", str(x), str(y))


def find(ns, *labels):
    for t, d, b in ns:
        if t in labels or d in labels: return b
    return None


def project(ns):
    # The project code at the top left ("P04"), never the search box's text, which a scroll can move up there.
    texts = [t for t, _, b in ns if b and b[1] < 520 and b[1] > 380 and b[0] < 200 and re.fullmatch(r"P\d{2,3}", t)]
    return texts[0] if texts else ""


def plain(q):
    q = "".join(ch for ch in unicodedata.normalize("NFD", q) if unicodedata.category(ch) != "Mn")
    q = re.sub(r"[^A-Za-z0-9 .,\-]", " ", q)
    return re.sub(r"\s+", " ", q).strip()


def ask(q, want):
    ns = nodes()
    if project(ns) != want: raise SystemExit(f"Open project is {project(ns)!r}, not {want}: stopping.")
    b = find(ns, "Borrar búsqueda", "Clear search")
    if b: tap_xy((b[0] + b[2]) // 2, (b[1] + b[3]) // 2); time.sleep(1); ns = nodes()
    b = find(ns, "Describe lo que buscas", "Describe what you're looking for")
    if not b: raise SystemExit("Search box not found")
    tap_xy((b[0] + b[2]) // 2, (b[1] + b[3]) // 2); time.sleep(1)
    sh("shell", "input", "text", plain(q).replace(" ", "%s")); time.sleep(1)
    sh("shell", "input", "keyevent", "111"); time.sleep(1)
    ns = nodes()
    btns = [b for t, d, b in ns if t in ("Buscar", "Search") and b[0] > 1500]
    if not btns: raise SystemExit("Search button not found")
    b = btns[0]; tap_xy((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
    started = time.time(); last = None; stable = 0
    while time.time() - started < 150:
        time.sleep(4)
        ns = nodes()
        texts = [t for t, _, _ in ns if t]
        busy = any(re.search(r"leyendo|reading|comprobando|checking|Buscando|Searching", t) for t in texts)
        header = next((t for t in texts if re.match(r"^(RESPUESTA|ANSWER)", t)), "")
        snap = (header, tuple(texts))
        if header and not busy:
            stable = stable + 1 if snap == last else 0
            if stable >= 1: break
        last = snap
    # The answer is the text right after the header; sources are lines naming a file and page.
    i = texts.index(header) if header in texts else -1
    answer = texts[i + 1] if i >= 0 and i + 1 < len(texts) else ""
    sources = [t for t in texts if re.search(r"\.(pdf|PDF) · (Página|Page)", t)]
    timing = next((t for t in texts if re.search(r"ms (antes de escribir|before writing)", t)), "")
    return {"header": header, "answer": answer, "sources": sources, "timing": timing, "seconds": round(time.time() - started)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("golden"); ap.add_argument("results"); ap.add_argument("--project", required=True); ap.add_argument("--only", default="")
    a = ap.parse_args()
    ws = openpyxl.load_workbook(a.golden)["Golden Q&A"]
    rows = [r for r in ws.iter_rows(min_row=2, values_only=True) if r[0]]
    only = set(filter(None, a.only.split(",")))
    done = json.load(open(a.results, encoding="utf-8")) if os.path.exists(a.results) else {}
    for r in rows:
        qid, question = r[0], r[1]
        if (only and qid not in only) or qid in done: continue
        print(qid, question, flush=True)
        done[qid] = {"question": question, **ask(question, a.project)}
        print("  ->", done[qid]["header"], "|", done[qid]["answer"][:140].replace("\n", " "), flush=True)
        json.dump(done, open(a.results, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"{len(done)} answers in {a.results}")


if __name__ == "__main__":
    main()
