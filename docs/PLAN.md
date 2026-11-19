# Plan: one app for all 5,000 files

ProAsisteLite is the app that processes the whole collection (decided 2026-11-13). Of the three ProAsiste-type apps on
the Pixel 10 Pro Fold it is the one built for large projects: search keeps only vectors in flat arrays and reads word
counts from a stored index, files are never imported twice, and the graph lives in the same SQLite database.
ProAsisteLocal (`com.plantroom.pocket`) loads every chunk to search, which breaks at this size; the OCR test app is a test.

| # | Step | Status |
|---|---|---|
| 1 | Getting thousands of files in | **Done** (`847aa52`, 2026-11-13) |
| 2 | Choosing the model for answers | **Done** (2026-11-13) |
| 3 | Sorting and tagging | **Done** (2026-11-13) |
| 4 | DocLang reader, then graph tables the user guides | **Done** (2026-11-14) |
| 5 | Skills and actions on files, saved reports and decisions | **Done** (2026-11-14) |
| 6 | Google Drive connection: index files where they live | Planned |
| 7 | Tables in the graph: columns on types, imported spreadsheets, counting questions | **Built** (2026-11-15) |
| 8 | Memory: what you asked, what you prefer, what you do often | **Built** (2026-11-15) |
| 9 | Three tabs: Ask, Documents, Work | **Built** (2026-11-19), not yet checked on the Fold |

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

## 3. Sorting and tagging — done

- Schema 6: `tags`, `source_tags` (each file tag records who set it: by hand, by a rule, by AI), `tag_rules` and
  `tag_queue`. A tag removed by hand is kept hidden, so rules and AI never put it back; a tag added by hand is never
  replaced by them.
- **Rules** (Library → Tags…), on the phone and instant: each subfolder's name; the year in the file name (a role's
  "2154-11" → 2011, a date's, or a plain four-digit year, never a law number); "file name contains … → tag". They run
  over the whole project when added and on every new import.
- **AI**: the user describes tags ("penal: the case is about criminal law"); `CloudTagger` sends each indexed PDF's
  first pages (up to 8,000 characters) and that list to the project's OpenRouter model, which may only choose from the
  list. Runs in the background service after importing and indexing, with progress, Pause and cost in the summary. Off
  in incognito projects; stops at once if OpenRouter refuses the key or the account has no credit.
- **By hand**: a file's detail sheet shows its tags; tap one to remove it, or add one with the project's tags suggested.
- The Library shows tag chips with counts; selected tags filter the Library and search results (a file must carry
  every selected tag). Each file lists its tags.

Checked on the Fold (P90, 16 files): three rules tagged 14 files at once (folders A/B/C, years, "STC" → sentencia);
filtering by 2011 showed its two rulings; a tag removed by hand stayed removed after the rules ran again; AI tagging with
DeepSeek V4.1 Flash tagged 15 PDFs for $0.0058, with "tributario" on the two tax-refund rulings.

Not yet: AI tagging with Gemini Nano (Android may block it in the background; to be tested), AI-suggested new tags, and
tags in the graph (step 4).

## 4. DocLang reader, then graph tables the user guides

First, the **DocLang reader**: port `plantroom-mvp/fineparser/to_doclang.py` (DocLang 0.9, https://doclang.ai) to
Kotlin with PDFBox, so each text PDF is read as typed blocks with positions — headings, paragraphs, lists, tables (OTSL,
with merged cells), footnotes, page headers and footers, pictures — in reading order, with paragraphs that continue
across a page break linked. FineReader-tagged PDFs keep their full structure; untagged PDFs fall back to text blocks.
Checked against the Python script's output on the files in `fineparser/out`.

**Done (2026-11-13):** `runtime/DocLang.kt` reads a PDF as DocLang 0.9 (tag tree first, letters by marked-content id,
the script's paragraph repair and page-break threads, loose text placed by position, a text-block fallback for untagged
PDFs) and writes the XML. `fineparser/out` turned out to hold ABBYY FineParser output, so the reference is
to_doclang.py itself, run on six public rulings now in `app/src/test/resources/doclang`: words agree at 98.9–100% and the
tagged files' headings, lists, tables and pictures match exactly. Not ported: text drawn outside the page content (some
signature stamps) and pictures in untagged PDFs. Of the collections, 21 of 30 sampled Gemelo Legal PDFs and all of
FineReader-salida carry tags. **Highlighting:** opening a passage (a search result or an answer's source) opens its page
with the passage highlighted; `runtime/Highlighter.kt` aligns the passage's words with the page's, so it works on any
text PDF without re-indexing. Checked on the Fold on STC 2154-11, page 14.

**Done (2026-11-14), graph from blocks:** `runtime/DocPages.kt` turns a document's blocks into the graph's page text:
no running headers, footers or pictures, text the tag tree left out put back where it sits on the page, footnotes at the
page's end, tables a row per line. Facts are checked against pages where a paragraph split by a page break is also given
whole. Each fact and review item keeps its place (schema 7, `graph_edges.place` and `graph_review.place`:
"page:x0,y0,x1,y1;…" on the 500 grid), kept through approval and same-page corrections and dropped when a fact moves page;
the viewer highlights it, on both pages of a split paragraph, and says where it continues. PDFs the reader finds little
text in keep the old page reader. Untagged PDFs now read as paragraphs (lines grouped by the page's usual spacing).
Before switching, the ruling skeleton was compared on old and new text over seven rulings; that found, and these
changes fix: a considerando mark could join a heading on the line above; a TC ruling naming itself only as "esta
Magistratura Constitucional" was taken for the pending case's court, and its number for the pending case's role (a file
name like "STC 1810-10" now gives the number); signature lists lost names after "los Ministros…" and did not split on
"e". On the Fold, STC 1810-10 went from 24 facts kept / 45 dropped (wrong court) to 41 kept / 5 dropped / 9 held back,
all 41 with a place, for $0.014; a fact opened on its page with its paragraph highlighted.

The graph is built from those blocks:

- headers, footers and footnotes are skipped, so running titles and folio stamps no longer become facts;
- the sections of a ruling (VISTOS, CONSIDERANDO, SE RESUELVE, separate votes, signatures) are found from headings,
  giving the ruling skeleton firmer ground;
- each fact cites its block and box on the page, not only the page, and the viewer can highlight it;
- facts are checked against whole paragraphs, including ones split across pages;
- tables are read cell by cell;
- DeepSeek is sent only the section a fact can come from.

Then **graph tables the user guides**: per project, the user defines node types and links; extraction follows that
definition, and today's legal mode becomes one ready-made definition. The review screen stays as it is.

**Done (2026-11-14), graph types:** Add evidence → Graph types sets each project to **Automatic** (Chilean rulings in
legal mode with the rule-based skeleton, other files free-form; the default) or **My types**: node types with
descriptions, and links with descriptions and the types they may run from and to, stored per project (schema 8,
`graph_schemas`). "Start from Chilean rulings" copies the legal types and links to edit; removing a type removes it from
every link and drops links left without a side. In My types every PDF is read page by page with the project's names as
closed lists in the answer format; facts must pass the usual page checks and run between the types their link allows,
and record the schema version they were built with. Building refuses an incomplete schema. Checked on the Fold: STC
1810-10 with the legal types minus Concepto gave 49 facts, all within the schema and placed, none using Concepto, for
$0.0115. In My types, rulings skip the rule-based skeleton, so Automatic stays best for Chilean rulings.

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

**Done (2026-11-14), part 1:** schema 9 adds `reports` and `decisions` (decisions are only ever added, so their history
stays; deleting a file, report or project takes its decisions along). An answer's "Save as report" keeps the question, the
answer, who wrote it and every passage it was given, in order, so its [n] marks still match, each with its page and place
so it opens highlighted. Library → Reports lists them; a report opens full screen to edit its title and text, open its
sources, decide on it (Relevant, Not relevant, Accepted, Rejected, To check, or your own words, with a note), save it as
PDF (Android's own writer, A4) or Markdown, or delete it; "+ Write a note" starts one by hand. A file's detail sheet
takes decisions too, and "Save as DocLang" writes its .doclang file. Library → Reports also saves all decisions as CSV
and the graph's facts as CSV or JSON (one "proasiste-graph-1" object per file, re-importable). Every file is written
where the user picks through Android's save dialog; nothing is sent. Checked on the Fold: a Nano answer saved as a report
and as a PDF that reads back correctly; STC 1810-10's DocLang file from the phone matches to_doclang.py exactly (same
blocks, page breaks and words). Not yet: Word output, reports in search, decisions on a page, filtering by decision.

**Done (2026-11-14), part 2: actions on files.** Add evidence → Actions on files runs an action on a project's indexed
PDFs or a tag's, in the background service after importing, indexing and AI tagging, with progress, Pause and resume
(schema 10: `skills`, `action_runs`, `action_queue`). Built in: Resumen, Tablas, Clasificar, Fechas y partes, each
answering in the document's language with pages cited; and Convertir a DocLang, which writes a .doclang file per PDF into
a folder the user picks, on the phone and free. Skills are the user's own actions (a name and instructions), kept for
every project. AI actions send each PDF's DocLang text (page-marked, up to 60,000 characters, with a note where it was
cut) and the instructions to the project's OpenRouter model; they never run in an incognito project and stop at once on a
refused key or an empty account. Each run's results go into one report that grows as files finish, a section per file
with the file among its sources. Checked on the Fold: Clasificar on #tributario (3 files) wrote a correct three-section
report with pages; Convertir a DocLang wrote three files whose content matches to_doclang.py (STC 2865-15: same
headings, lists, pictures, paragraphs and page breaks, 99.9% of words). Accented file names are kept whole.

Not yet: Word output; reports in search; decisions on a page; actions on a single chosen file; AI actions with Gemini
Nano (Android may block it in the background).

## After the plan: seeing the graph

**Done** (2026-11-14). A **Graph · N facts** row in the library opens the project's graph, and **See its graph** on a
file's sheet opens that file's. Dots are names (one dot per name, whatever type the model gave it), coloured by the six
commonest types; lines are facts. Drag, pinch to zoom, find a name, tap a dot: its facts are listed beside the graph
(below it on a narrow screen) with file and page, and tapping one opens the page with the fact's paragraph highlighted.
Large graphs show their 300 most linked names. The layout (`graph/GraphPicture.kt`, Fruchterman–Reingold) is worked out
on the phone and comes out the same each time. Checked on the Fold with P03 (370 facts, 384 names).

**Merging names** (schema 11, `graph_aliases`): on a dot's facts, **Merge names…** picks the names that mean the same
(those sharing a word are offered first; any other can be found). Their facts move to the dot kept, but only in files
that mention it, so "Appliance" in the vacuum manual stays the vacuum. Each file remembers its merges, and rebuilding
its graph applies them again. Done on the Fold: "Deluxe Egg Cooker", "DASH Deluxe Egg Cooker", "Egg Cooker" and
"Appliance" merged into "DASH egg cooker" (84 facts, none lost).

**Seeing relations**: pieces of the graph are laid out apart and packed in rows, so pairs and small clusters no longer
shrink to touching dots. A tapped dot moves to the middle with one labelled branch per relation ("has important
safeguard → ×22", "← may use with supervision"), the names it leads to fanned at the end.

Not yet: undoing a merge; places tighter than a whole page on manuals laid out in columns.

## 6. Google Drive connection: index files where they live

**Planned.** Today every import copies the file into the app's storage, so files kept in Drive sit on the phone twice.
This step lets a project keep its files in Drive and hold only what it builds from them on the phone: text chunks,
search index, graph facts and DocLang blocks.

- **Sign in and pick folders.** Google sign-in with Drive read-only access (`drive.readonly`); a project links one or
  more Drive folders, subfolders included. Files are tracked by Drive file ID, so moves and renames don't break them.
- **Index without keeping the file.** Each file is downloaded to a temporary spot, read once (text, DocLang, embeddings,
  graph), then deleted. A `sources` row records the Drive ID, its version (`md5Checksum` / `modifiedTime`) and that it
  lives in Drive rather than in the app's storage.
- **Keep in step.** Drive's changes feed (`changes.list` with a saved page token) finds files added, edited, moved or
  deleted in linked folders: new and edited ones are indexed again, deleted ones leave the project (their facts with
  them). Checked when the app opens and from the background indexer, on Wi-Fi by default.
- **Opening a page.** The viewer fetches the file from Drive when opened, keeping a small cache of recent files;
  "Keep offline" on a file or tag keeps a copy. Without a connection, search results, facts and reports still show,
  and a file that can't be fetched says so.
- **Copy stays an option.** Importing from the phone, or from Drive through the file picker, copies as today; a project
  can mix both kinds of file.

To settle first: Google's verification for the Drive scope (a restricted scope if the app is published; fine for
personal use with test users), and how long 5,000 downloads take on the Fold (indexing stays about 0.6 s a page).

## 7. Tables in the graph: columns on types, imported spreadsheets, counting questions

**Planned.** Questions that count or filter across documents ("how many rulings rejected art. 60", "contracts ending
in 2027") should be answered exactly from data, not through search: the golden set (2026-11-05) got 1 of 10
cross-ruling questions right. The idea follows FalkorDB's structured data knowledge graph (falkordb.com, 2026-11-10),
built on the app's own SQLite graph: a table row and a document's facts about the same name land on the same dot,
because both use `Graph.keyOf`. Nothing here is specific to one kind of document; rulings are the first case.

**7a. Columns on graph types, filled from documents.** In Graph types, a node type can declare columns, each with a
value type: text, number, date, yes/no or list (Contract: signed on, amount, ends on; Appliance: model, wattage;
Ruling: date, outcome, subject). Each dot of that type gets its values when its file's graph is built:

- Legal mode fills a ruling's columns with the rule-based skeleton, no model: case number, date, type, filer,
  challenged norms, and the outcome read from SE RESUELVE (upheld, rejected, partly upheld, inadmissible, or "unclear",
  never guessed). Start by measuring how complete each column is across P04's 173 rulings.
- Other projects ask the model for each dot's column values along with its facts.
- A value must be on its cited page and read as its type (a date as a date, a number as a number); doubtful values go
  to review like held-back facts. Every value keeps its file and page, so it opens highlighted.
- Columns that are really links (a ruling's judges, an appliance's parts) come from the links themselves, not
  extraction.
- A column no rule can fill, such as a ruling's subject, is filled by the model or from the project's tags.

**7b. Importing your own tables.** CSV (delimiter and decimal style detected), .xlsx read directly (each sheet a
table; cached formula values, dates from date-formatted cells, merged cells filled; old .xls and protected workbooks
refused), and Google Sheets once step 6 exists.

- The app measures each column (type, fill, uniqueness) and proposes a mapping: key, name, type, column types, and
  columns holding another table's key, which become links. The user confirms or edits it. Every cell is checked
  before anything is written; one failure writes nothing.
- A row lands on the dot with the same name and type. A link to a row not loaded yet makes a placeholder dot, filled
  when its table arrives, so files can be loaded in any order.
- Importing the same file again re-syncs it in one transaction: changed rows update, new rows arrive, removed rows go
  (a dot a document still mentions keeps its document facts). An unchanged file is skipped by its fingerprint;
  removing a table takes back exactly what it wrote.

**One store for both** (schema 12; back up the phone's database first): each value records its dot, its column, the
table or file that wrote it, its page when it came from a document, and its type. Sources never overwrite each other;
where they disagree, both values show. Library → Tables lists one table per type with rows; each saves as .xlsx, so
the user can correct it in Excel and import it back, the corrections sitting beside the extracted values.

**7c. Questions answered from tables.** Fixed patterns, no model: count, average, minimum and maximum, lists filtered
by column values, and grouping ("outcomes per year", "who dissents most"), answered from SQLite like today's list
answers, with each row openable. The card says which table answered. Optionally, the project's OpenRouter model
writes a read-only query for questions the patterns don't cover; it sees the question and the table layout, never the
data. Never in incognito projects, never with Gemini Nano.

**Built (2026-11-15): 7a for rulings, and 7c.** Schema 12 adds `graph_values` (a value per node, column, file and
writer; nodes with values survive graph rebuilds; merges carry them). Graph types can declare columns (`ColumnDef`:
text, number, date, yes/no, list), kept in the schema JSON; a ruling has ten built in (`RulingColumns`): Fecha, Tipo,
Resultado, Requirente, Gestión, Normas impugnadas, Ministros, Redactor, Disidentes, Previenen. Library → Rulings table →
Read from files reads them with the skeleton, on the phone, no model. Measured on P04's 173 rulings (copied from the
phone): type 172, date 168, outcome 164, filer 146 (the 11 preventive controls have none), case 140, norms 132,
judges 173, drafter 95. Getting there fixed the skeleton on DocLang text, which has no blank lines between header
parts: the filer now comes from VISTOS when needed (was 0 of 173, so norms and case were 0 too), SE DECLARA is read for
preventive controls, and unnumbered or stamp-interrupted decided points are found. A question that counts, lists or
groups rulings ("¿cuántos fallos fueron rechazados?", "¿en qué fallos disintió Silva?", "fallos por resultado",
"¿qué fallos impugnan el art. 60 de la ley 18.695?") is answered from the columns before search, each row citing its
page; the card says "counted from the rulings table". Checked on the Fold with P04's two rulings. Not yet: columns
for My types filled by the model, link columns, a row's values on the graph screen, saving the table, and P04's 173
rulings in this app (they are in ProAsisteLocal only).

**7d. Likely duplicates.** Names that probably mean the same, found by rules (case, accents, "Rol 17.292-25" and
"17292-2025") and by similarity with EmbeddingGemma on the phone, offered for the user to confirm with Merge names.
Nothing merges on its own until a merge can be undone.

**Checked by:** tests for the .xlsx reader (dates, merged cells, formulas, decimal styles), the mapping checks and
re-sync (changed, new and removed rows, a row a document still mentions); on the Fold, P04's rulings (legal mode),
P03's manuals (model-filled columns, such as an appliance's model and wattage) and an imported spreadsheet joining one
of them; then the golden set's 10 cross-ruling questions, which this step should mostly win, and all 61 so nothing
else gets worse.

Not in this step: photos and voice notes get no columns; Parquet or database connections; tables of millions of
rows; automatic merging. 7a and 7c don't need step 6 and can come first.

## 8. Memory: what you asked, what you prefer, what you do often

**Planned.** After FalkorDB's agent memory design (falkordb.com, 2026-11-03), sized for one person on one phone. The
app forgets every question once it's answered, and repeated instructions ("answer in Spanish", "always give the vote
count") have to be typed each time. The post's three kinds of memory map onto the app; the part about serving
100,000 customers (shards, replicas, database accounts) doesn't apply.

**What the app has today.** Projects are kept apart: every graph, search and action is limited to its project, which
is the post's one-graph-per-customer rule; incognito projects never leave the phone. Some memory exists already:
reports, decisions, merges (`graph_aliases`), review verdicts and skills.

**8a. Episodic: a dated log of each project's questions.** Each search keeps its question, the answer, who wrote
it, the passages it used, and what was done next (sources opened, saved as a report, decided). Library → History
lists them, newest first, searchable; any one reopens with its sources highlighted, or becomes a report. Kept until
deleted (one entry, a date range, or all), and never kept in incognito projects. Only the log's text and links are
stored, not copies of passages, so it stays small.

**8b. Semantic: what you prefer and what the project knows.** A short list of memories per project, plus a few for
every project: preferences ("answer in Spanish", "keep answers to five lines") and standing facts ("Minister X's
name is also spelled Y", "in this project 'the law' means Ley 21.000"). Each is added by you, or suggested by the app
when you correct an answer or repeat an instruction, and kept only when you accept it. Relevant memories join the
passages sent for an answer, marked as yours and never cited as a source. Settings → Memory lists, edits and deletes
them. Like everything else sent with a question, they go to OpenRouter only when the project's answers use it.

**8c. Procedural: skills from what you do often.** When the same kind of request recurs in the log (three tables of
votes, three summaries in one format), the app offers to save it as a skill, with its instructions drafted from your
own words to edit before saving. Skills stay what they are today, kept for every project.

**8d. Shared knowledge, joined at answer time.** Some knowledge belongs to every project: name spellings of judges
and norms, graph types, everyday preferences. It lives once, apart from the projects, and is joined with the
project's own at answer time, never copied into each project. A project's own memory wins where the two disagree.
Incognito projects can read shared knowledge but never add to it.

**8e. A retrieval budget.** Measure where an answer's time goes today on the Fold (embedding the question, ranking,
graph facts, memories, Jev) and set a budget: about one second before the model starts writing. Memory lookups must
fit inside it. Also measure each project's database size, so growth from the log shows early.

**Checked by:** tests for the log (kept, reopened, deleted, never in incognito), for memories joining an answer
without being cited, and for shared knowledge not leaking a project's own data into another; on the Fold, a
preference given once and honoured in the next ten answers, a repeated request offered as a skill, and the retrieval
budget met on P04; then the golden set again, so memory doesn't make answers worse.

Not in this step: memories the app saves without your yes; memories shared between people or phones; anything from
one project reaching another except shared knowledge you added.

## After the plan: indexed packs (2026-11-15)

**Built.** A large folder can be indexed on the computer and searched on the phone. `DesktopPackBuild` (a JVM run of the
app's own `Engine.pdfPieces`) reads and splits each PDF; `desktop/embed_pack.py` embeds the pieces with the app's model
file through MediaPipe on the CPU (cosine 0.9995+ to the phone's own vectors; the Hugging Face weights are not
interchangeable at ~0.985). Both write `proasiste-index.db` beside the PDFs. On the phone, Import a folder loads it first
(schema 13: `prebuilt_files`, `prebuilt_chunks`, keyed by SHA-256, refused for another index version or model), and the
background indexer takes a matching file's chunks from it instead of the model. Checked on the Fold: 20 of Dr. García's
rulings (1,317 pages) read in 5 s and embedded in 2 min here, indexed in seconds there, chunks and vectors identical,
search and a Nano answer working. How to make one: `desktop/README.md`. Dr. García's whole folder (2,764 PDFs) would take
about 5 min to read and about 2 h to embed.

## Steps 7 and 8 finished (2026-11-15)

**7, the rest.** 7a: Graph types declare columns per type (text, number, date, yes/no, list); the graph build asks
the model for their values, kept only when the page shows them (a date's year and day, a number's digits, most of a
text's words); relations become list columns ("CITA_PRECEDENTE →", "← INTEGRA", left out above 8 names a node on
average); a name's values show on the graph screen. 7b (schema 14, `table_maps`): Add evidence → Import a table reads
CSV (delimiter found by counting) and .xlsx (no library: shared strings, date styles, saved formula values, merged
cells; .xls and protected files refused); the app proposes a mapping (type joined to the graph's names when a column
matches them, name column, kinds, links) for the person to confirm, checks every cell first, keeps the file as a source
of type "table", and re-syncs on a second import (changed rows update, new arrive, removed go; a node a document still
mentions stays). Rows spelled another way join the graph's name ("Daniela Marzi Muñoz" → "Daniela Beatriz Marzi
Muñoz"). Tables save as .xlsx. 7d: "Same name?" on the graph screen pairs names spelled two ways (same first name and
both surnames, or the same case number), each merged only on a yes. Checked on the Fold: the Tribunal's list of 39
ministers imported as a Ministro table.

**8 (schema 15, `history`, `memories`).** 8a: every answer is kept with its question, writer, sources, sources opened
and the report it became; Library → History searches, reopens (sources highlighted as before) and deletes; nothing kept
for incognito projects. 8b: Library → Notes keeps preferences and facts, for this project or all projects; they go in
the answer prompt as the user's notes, after the rules and never numbered, so never cited; a question with an
instruction ("en máximo 3 líneas", "responde en español", "always …") offers to keep it. 8c: a request asked three
times (same words without names and numbers) is offered in Actions as a skill, drafted from the question. 8d: notes for
all projects are read by every project; an incognito project can't add or change them. 8e: every search logs where its
time goes (`ProAsisteTiming`) and shows it: on the Fold, P93 (11,669 pieces) took 177 ms before writing (question 121,
search 50) once warm, 1,319 ms on the first search after start (loading the index); the one-second budget holds when
warm. Checked on the Fold: a suggested note kept, then "1 of your notes went with the question", History and Notes.

**Also:** a piece over the model's 512 tokens is embedded from its first 85% of words until it fits, on the phone and in
`desktop/embed_pack.py` alike; before, it failed its whole file. Not yet: semantic name matching with EmbeddingGemma for
7d, model-written queries for 7c, the cold first search.

## 9. Three tabs: Ask, Documents, Work

**Planned (2026-11-19).** The bottom bar today is Search, Library and a wide **Add evidence** button whose sheet mixes
three kinds of thing: ways to bring files in (photo, voice note, files, folder, table, project pack, graph file),
graph work (Build graph with DeepSeek, Graph types) and running work (Actions on files). Reports, History, Notes and
the Graph sit as rows inside Library. With 5,000 files nobody browses a list; they ask. And what a lawyer bills for,
reports and decisions, is three taps deep. The bar becomes three tabs, each one job:

| Tab | Spanish | For | Holds |
|---|---|---|---|
| **Ask** | Consultar | Asking the collection | Question box, answer card with cited sources, search results, graph answers, "See its graph" |
| **Documents** | Documentos | Managing the collection | File list, folder and tag filters, indexing status, Tags…, and the **+** |
| **Work** | Trabajo | Keeping what you produced | Running actions, reports, decisions, skills, history |

Settings and Notes move behind the gear at the top. The project picker stays where it is.

**Ask** (today's Search screen, `plantroom.nav-search`)
- The answer card stays first. List questions answered from the graph show here as today.
- The graph has no tab of its own until it proves itself (golden set, see below): it opens from an answer's graph
  facts, from "See its graph" on a file, and from a **Graph · N facts** link on Ask. Graph review and "Same name?" stay
  on the graph screen.
- Long-press an answer → **Save to Work** (saves the report, as Save as report does now).

**Documents** (today's Library)
- File list with the existing chips (All, Photos, Voice notes, Reports) and tag chips; folder (asset) filter.
- One status line on top: "4,812 indexed · 23 need OCR · 2 failed · importing 140 of 900", opening a housekeeping view
  (failed files, scans, the import queue with Pause).
- **+** as a floating button on this screen only. Its sheet keeps only ways to add: Take a photo, Record a voice note,
  Import files, Import a folder, Import a table, Import a project pack, Import a graph file.
- **Build graph with DeepSeek** and **Graph types** leave the add sheet for the graph screen's menu.

**Work** (new; today's Reports sheet, History and Actions on files)
1. *In progress*: background Actions on files runs with progress and Pause (today only in the notification). A
   finished run becomes its report.
2. *Reports*: saved answers and action results, newest first, with date, tags and decision count; export PDF or
   Markdown.
3. *Decisions*: the dated, add-only log across reports and files as a timeline, filtered by project, file or tag;
   export CSV.
4. *Skills*: your own actions and the ones suggested after a request is asked three times (8c); run on a project or a
   tag. **Actions on files** moves here from the add sheet.
5. *History*: today's Library → History (search, reopen, delete; none for incognito projects).
- Filter row: This project / All projects, tag chips. Exports of graph facts and tables move here from Reports.
- A badge on the tab when a background run finishes.

**Code:** `ui/PlantroomScreen.kt` holds the bar (`bottomBar`, a `library` flag and `navigate(toLibrary)`) and the add
sheet (`capture`). The flag becomes a three-value tab; the Library column's Reports, History, Notes and Graph rows move
to Work, Ask and the gear. `ui/ReportsScreen.kt` and `ui/MemoryScreen.kt` become Work's sections rather than sheets.
Test tags follow the names (`plantroom.nav-ask`, `plantroom.nav-documents`, `plantroom.nav-work`,
`plantroom.add`). `desktop/golden_run.py` drives the Search screen through uiautomator, so it is checked against
the new layout. No schema change.

**Checked by:** on the Fold, every action that was reachable before is reachable after (each row of the add sheet and of
Library, tapped); a question, Save to Work, a decision on it, and CSV export; an Actions run
seen in progress and then as a report; the golden set run through `golden_run.py` on the new layout, answers unchanged.
Before moving anything, count from `history` which screens are used (sources opened, reports made) so the order of
sections in Work follows real use.

Not in this step: a Graph tab (later, if the golden set shows the graph earning it); team sharing of reports and
decisions (the web version); indexing search from DocLang blocks and keeping identifiers such as "17.292-26" or BL
numbers whole in the word index, which are separate changes with their own golden-set check.

**Built (2026-11-19), not yet checked on the Fold.** `ui/PlantroomScreen.kt`: a `tab` (ask, documents, work) replaces the
`library` flag, the bar is three `NavTab`s (`plantroom.nav-ask`, `-documents`, `-work`), and the **+**
(`plantroom.add`) is a floating button on Documents. The add sheet is now **Add documents** with seven rows. Ask, when
no question is open, shows a **Graph and tables** section: Build graph with DeepSeek, Graph types, and the Tables,
Graph and Graph review rows that were in Library. Work (`WorkPage`) holds Actions on files, History and the reports;
`ReportsSheet` became `ReportsSection` in `ui/ReportsScreen.kt`, shown in Work with the exports. Notes for answers
moved to settings. Changed from the plan above: the graph tools sit on Ask rather than in the graph screen's menu (that
screen only opens once a graph exists); Work's decisions are each report's own and the CSV export, not yet a timeline
across reports and files; no badge when a run ends; no long-press Save to Work (Save as report on the answer card does
it). Skills stay inside Actions on files.
