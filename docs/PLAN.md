# Plan: one app for all 5,000 files

ProAsisteLite is the app that processes the whole collection (decided 2026-11-13). Of the three ProAsiste-type apps on
the Pixel 10 Pro Fold it is the one built for large projects: search keeps only vectors in flat arrays and reads word
counts from a stored index, files are never imported twice, and the graph lives in the same SQLite database.
ProAsisteLocal (`com.plantroom.pocket`) loads every chunk to search, which breaks at this size; the OCR test app is a test.

| # | Step | Status |
|---|---|---|
| 1 | Getting thousands of files in | **Done** (`847aa52`, 2026-11-13) |
| 2 | Choosing the model for answers | Next |
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

## 2. Choosing the model for answers

- In settings, per project: answers written by Gemini Nano on the phone, or by a model chosen from OpenRouter
  (DeepSeek, Claude, Qwen…), with the key already saved.
- Incognito projects always stay on the phone.
- Each answer says which model wrote it and what left the phone.

To decide: which OpenRouter models to offer; whether Nano stays the default; whether passages may leave the phone
outside incognito without asking each time.

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
