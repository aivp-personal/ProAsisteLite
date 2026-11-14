# Plan: one app for all 5,000 files

ProAsisteLite is the app that processes the whole collection (decided 2026-11-13). Of the three ProAsiste-type apps on
the Pixel 10 Pro Fold it is the one built for large projects: search keeps only vectors in flat arrays and reads word
counts from a stored index, files are never imported twice, and the graph lives in the same SQLite database.
ProAsisteLocal (`com.plantroom.pocket`) loads every chunk to search, which breaks at this size; the OCR test app is a test.

| # | Step | Status |
|---|---|---|
| 1 | Getting thousands of files in | **Done** (`847aa52`, 2026-11-13) |
| 2 | Choosing the model for answers | **Done** (2026-11-13) |
| 3 | Sorting and tagging | Not started |
| 4 | DocLang reader, then graph tables the user guides | Not started |
| 5 | Skills and actions on files, saved reports and decisions | Not started |

## 1. Getting thousands of files in — done

- Add evidence → Import a folder queues every PDF, photo and recording in a folder and its subfolders (schema 5,
  `import_queue`). Read access is kept, so an import can stop and resume.
- Files are hashed while copied, so a file already in the project is skipped. The subfolder becomes the asset (search
  can be filtered by it); the file's own date becomes the source's date.
- `IndexService` imports and indexes in the background: notification with progress and Pause, partial wake lock, works
  with the screen off or the app closed, resumes after Android stops it. The app stays usable meanwhile.
- A PDF with no text on any page is marked as a scan needing OCR; a partly scanned PDF is indexed and the Library says
  how many pages had no text.
- Measured on the Fold: about 0.4–0.6 s per PDF page (73–88 ms per 60-word chunk). 5,000 files of ~32 pages is roughly
  a day of background indexing.

Still to check at full scale: a run of thousands of files, and search speed in a project that large. OCR is not built:
scans need OCR on the computer (FineReader), then a new import.

## 2. Choosing the model for answers — done

- Settings → "Who writes answers" sets, per project, Gemini Nano on the phone or any text model in OpenRouter's live
  catalog (`/api/v1/models`, no key needed to list it), searchable, with prices per million tokens. The newest Claude
  Opus, Claude Sonnet, DeepSeek, Qwen and Gemini are suggested first. The choice is stored with the project.
- `CloudAnswerer` sends the same prompt and citation format Nano gets (up to five sentences instead of three), streams
  the answer, and sends no sampling settings (some current models, Claude Opus 5.5 among them, reject them). Jev's
  checks and the citation fixes work unchanged.
- The card names the model and says what left the phone and what it cost ("Your question and 6 passages went to
  OpenRouter (provider) · $0.0001"). The shared text names the model too.
- Incognito projects always answer with Nano, and say so. Without a saved key, or when OpenRouter fails, Nano answers
  and the card says why.

Decisions taken: Nano stays the default for every project; the whole catalog is offered, with suggestions first;
choosing a cloud model for a project is the consent, so searches don't ask each time, but every answer states what was
sent. Checked on the Fold: DeepSeek V4.1 Flash answered a question on P90 with citations for $0.0001; with incognito on,
Nano answered and nothing left the phone.

## 3. Sorting and tagging

- A tags table: several tags per file (type, year, court, case…).
- Tags filled by rules (folder, file name), by the chosen model, or by hand.
- Search and the Library filtered by tag, with counts per tag.

## 4. DocLang reader, then graph tables the user guides

First, the **DocLang reader**: port `plantroom-mvp/fineparser/to_doclang.py` (DocLang 0.9, https://doclang.ai) to
Kotlin with PDFBox, so each text PDF is read as typed blocks with positions — headings, paragraphs, lists, tables (OTSL,
with merged cells), footnotes, page headers and footers, pictures — in reading order, with paragraphs that continue
across a page break linked. FineReader-tagged PDFs keep their full structure; untagged PDFs fall back to text blocks.
Checked against the Python script's output on the files in `fineparser/out`.

The graph is then built from those blocks:

- headers, footers and footnotes are skipped, so running titles and folio stamps no longer become facts;
- the sections of a ruling (VISTOS, CONSIDERANDO, SE RESUELVE, separate votes, signatures) are found from headings,
  giving the ruling skeleton firmer ground;
- each fact cites its block and box on the page, not only the page, and the viewer can highlight it;
- facts are checked against whole paragraphs, including ones split across pages;
- tables are read cell by cell;
- DeepSeek is sent only the section a fact can come from.

Then **graph tables the user guides**: per project, the user defines node types and links; extraction follows that
definition, and today's legal mode becomes one ready-made definition. The review screen stays as it is.

DocLang describes layout, not legal meaning: the skeleton and the model still decide what is a considerando or a
dissent. Scans still need OCR first.

## 5. Skills and actions on files, saved reports and decisions

- **Actions** on chosen files, a tag or a whole project, run in the background with the chosen model: summarise,
  extract a table, classify, find dates and parties, and **Convert to DocLang** (a `.doclang` file beside each PDF, or
  exported to a folder; reuses the step 4 reader, about 90 ms per page, no model). Results cite their page and are
  searchable. The user can save their own actions as reusable skills.
- **Saved reports**: keep an answer as a report — question, answer, cited passages with file and page, the model that
  wrote it, the date. Listed in the Library, searchable, editable.
- **Decisions**: the user's own dated decisions on a file, a page or a report ("relevant", "accepted", or a written
  conclusion), filterable by tag. Graph review decisions are already kept with date and reason.
- **Export**: reports and decisions as PDF, Word or Markdown; decisions and graph facts as CSV or JSON, for Excel or the
  Gemelo Legal tools on the computer.
