# Graph strategy: a clean legal graph

The project graph exists to see across rulings: which rulings cite a precedent, who dissents most, outcomes per
article. That only works if one ruling, norm or minister is one node everywhere. This document is the plan for
getting there and the record of where it stands. Update the status lines as work lands; keep the reasoning when a
decision changes, with the date.

## Why a graph gets dirty

Everything seen so far comes down to four causes:

1. One thing with several names: "Rol 9.431" and "Rol 9.431-20-INA"; "Ley 18.695" and "Ley Orgánica Constitucional
   de Municipalidades".
2. Mistakes in AI-extracted facts: wrong page, wrong body of law, an inferred petition, an invented inciso.
3. Repeats: the same link in other words, from two passes or two runs.
4. Links that aren't links: a run where every fact pointed at the ruling itself.

Each stage below closes one of these.

## Principles

- Rules make the skeleton; AI fills in only what rules can't.
- Every name is resolved against a master list before it enters the graph. Unknown names are flagged, never
  silently created.
- Every fact keeps its provenance: source, page, extractor, prompt version, date. A bad batch can be removed or
  rebuilt without touching the rest.
- Cleanliness is measured against questions whose answers are already verified, before scaling up.
- Bulk work runs on the PC or server; the phone holds one case's documents and resolves them against a reference
  pack built from the master lists.

## Stages

### 1. Identity before extraction — in progress

Keys per entity type:

- Ruling: `court:number[-year]`, e.g. `TC:9431`, `CS:1234-2020`. Courts whose numbering restarts each year keep the
  year. (Replaces the main-number-only merge, which would have merged CS 1234-2020 with CS 1234-2021.)
- Norm: `body:article[:inciso]` with an alias table, e.g. "LOC de Municipalidades" → `LEY:18695`.
- Minister: a fixed list of full names and known variants, from signature pages.

Master lists start from the PC graph and the Tribunal's own data, checked against the PDF collection.

Status (2026-11-08): ruling key done in the app (`LegalFacts.rulingKey`, `canonicalRuling`): `TC:17292` for the
Tribunal Constitucional, `CS:1234-2020` where numbering restarts; the canonical node name is "STC Rol 17.292" with no
year or suffix. Master list of roles: the PC graph `tc_fallos` on port 6379 needs the user's credentials, so the
first sources were the P04 experiment graph (173 own rulings, 1,356 cited causes) and a read-only scan of the PDF
collection's names and first pages (`scratchpad/master/scan_pdfs.py`). Norm aliases and minister list: not started.

#### Matching and survivorship

Two decisions, kept apart. Matching decides whether two records are the same entity. Survivorship decides which
value wins once they are.

Matching runs in three bands. An exact key match merges on its own. A near match (edit distance for names, embedding
similarity for people and courts; never for role numbers, articles or dates) goes to the steward. Anything below is a
new entity. No model ever merges two entities silently.

Survivorship rules, per attribute, so they can be audited and changed here rather than found in code:

| Attribute | Wins | Why |
|---|---|---|
| Ruling role | File name, then page 1 | 58 inadmissibility rulings show a filing or resolution number on page 1 (2026-11-08 scan) |
| Ruling year | 4-digit form over 2-digit; the file name over the text when they differ | Two-digit years are ambiguous and one text had a misread year |
| Ruling date | The date on page 1 of the ruling itself | Cited dates come from other rulings |
| Minister name | Signature page, longest form | Mentions in the text are abbreviated or carry titles |
| Who sat, drafted, dissented, concurred | Signature page and vote headings (rules), over AI facts | Formulaic text; the AI fact is attached, never the source of truth |
| Outcome | "SE RESUELVE" (rules), over AI facts | Same |
| Norm name | The alias table's canonical form | "LOC de Municipalidades" and "Ley N° 18.695" are one norm |
| A fact's page | The page whose text passes the checks | The model's page label is wrong often enough to distrust |

Status: these rules are applied in the scan and the ruling key; not yet applied uniformly in the app.

### 2. Rule-based skeleton — done (2026-11-08)

Fixed patterns, no AI, on the formulaic parts of a ruling (`runtime/RulingSkeleton.kt`): header (role, date, court,
type, requirente, challenged norms, pending case), the court's considerandos (ordinal words such as "DECIMOCUARTO:"
mapped to numbers, with their section headings and the rulings each one cites), "SE RESUELVE" (one fact per decided
point), separate votes (dissents and concurrences with their ministers, sense, author and their own "1°."-numbered
considerandos, kept apart from the court's), and the signature page (who sat, who drafted). Everything is in Spanish
and cites its page. Descended from `plantroom-mvp/graph_experiment/extract.py`, which answered all 10 verified P04
questions.

The skeleton runs before DeepSeek. Its node names are given to the model so it reuses them; an AI fact whose words sit
in a dissent is re-attached to that Voto; an AI fact naming a ruling or minister the skeleton never saw is held for
review. Test resources: `app/src/test/resources/rulings/` (two real rulings, one with a four-minister dissent).

On the phone with both test rulings: 232 facts (139 from rules, the rest AI), 135 nodes, no ruling under two names, 15
nodes shared by both rulings including the seven ministers who sat on both, 16 facts held for review, 7 dropped.

### 3. AI facts attached to the skeleton — partly done

DeepSeek legal mode adds reasoning, parties' arguments, how the court characterises a norm, and the sense of each
citation. Two rules: an AI fact may only link entities that exist in the skeleton or master list (otherwise it goes
to review), and every fact keeps its provenance.

Provenance and audit trail. Every fact stores its source document, page, extractor (DeepSeek, Qwen, rules, a
reviewed import), prompt version and date. Every merge and every survivorship decision is recorded: what was merged,
by which rule or by which steward decision, when. The point is reversibility: a bad batch can be removed by its
provenance, and a wrong merge can be split again. A merge that cannot be traced is one that cannot be undone.

Status: legal mode runs (two passes, fixed types and relations, naming rules) after the skeleton, and AI facts may
only name rulings and ministers the skeleton found in the text or the project graph already holds (norms are not yet
checked against a list). Every fact now stores its extractor ("reglas", "deepseek/<model>@<prompt version>",
"revisado", or "" for an import), and every review decision is kept with its date (schema 3, 2026-11-08). Merges are
not logged yet.

### 4. Checks, then review — review screen done (2026-11-08)

Checks in place: numbers on the page, most words on the page, body of law matches the text, relation runs the right
way, one fact per link, and a fact may only name rulings and ministers the skeleton found or the project graph already
holds.

Held-back facts are queued in `graph_review` with their reason, extractor and prompt version. The Library shows
"Revisión del grafo · N datos pendientes" as its own row; the screen groups items by document, shows the fact as the
model wrote it with its reason in plain words, opens the cited page, and offers Aprobar, Corregir página (the page
where the checks would pass is proposed), Editar (names, types, relation, page) and Rechazar. Approved facts enter the
graph with extractor "revisado"; decisions stay as the record and a new extraction replaces only pending items.
"Muestra" draws ten kept AI facts at random so the errors that got through can be counted; rejecting one removes it
and records it. Not yet: approving a Norma body mismatch should add the alias to a table (aliases are still read from
the text), and "apply to all" for a repeated reason.

### 5. Measure before scaling — not started

1. 10 rulings: zero rulings, norms or ministers appearing as two nodes, checked by hand.
2. P04's 173 rulings: the 10 questions in `plantroom-mvp/graph_experiment/RESULTS.md` must match. Each mismatch
   points at a defect.
3. The full collection, only after step 2 passes.

Track the ratio of held-back to kept facts over time; a rise means the prompt or the master list needs work.

### 6. Cross-ruling queries and entity-scoped retrieval — list answers done, rest not started

`GROUP BY` queries for counting questions (most cited, who dissents most, outcomes per article); the recursive CTE in
`Graph.reach()` for chains of citations. Answers always say which ruling each item comes from.

Entity-scoped retrieval: the graph draws the boundary, similarity ranks within it. When a question names a ruling, a
norm or a minister, resolve that entity first (by key or alias), restrict the candidate passages to the documents and
pages linked to it, and only then rank by embedding and words. Today ranking runs over the whole project and graph
facts join at the end, which is fine with one ruling loaded and wrong with 173: "what did the court say about art. 60
in Rol 17.292" would pull passages from every ruling that mentions art. 60. Never the reverse order; ranking first
over the whole project and filtering after leaves the wrong ruling's passages in the candidate list.

Status: list questions (which rulings were cited, who sat, what was decided) are answered from the graph with no model
(2026-11-08). Counting queries, the CTE in answers, and entity-scoped retrieval: not started.

## Decisions log

- 2026-11-08: Graph answers are never limited to one ruling; the graph exists to see them all together.
- 2026-11-08: DeepSeek through OpenRouter is the app's extractor for non-confidential documents. Qwen on the PC
  (scripts in the scratchpad, not yet in the repo) is the route for confidential case files, since text stays on
  the user's machines.
- 2026-11-08: Gemini Nano is not used for extraction (quota errors, mixed-up rows, weak coverage). It writes answers
  only.
- 2026-11-08: Jev is off by default and separate from the saved key.
- 2026-11-08: Matching and survivorship are separate decisions; matching has three bands with a steward in the
  middle; survivorship rules live in this document. Prompted by a master-data-management article the user shared.
  Not adopted from it: hub-and-spoke governance, data mesh, team stewardship workflows. One steward, a registry-style
  master list and a review screen are the right weight here.

## Master list of roles (first cut, 2026-11-08)

`docs/master/master_roles.csv`, built by `docs/master/scan_pdfs.py` (read-only) over the 2,952 PDFs in the
collection plus the P04 experiment graph. 2,791 Tribunal Constitucional roles: 2,160 with a file in the collection,
631 only cited. Findings that shape the next steps:

- 1,016 of 2,952 PDFs (34%) have no selectable text on page 1: scans, which need OCR before any extraction.
- 207 roles appear in more than one file. Most are copies filed under several ministers' folders (Rol 15.241 appears
  8 times); a few may be distinct documents on one case.
- 58 files whose name and first page disagree on the role. Many are inadmissibility rulings whose first page gives a
  different number (a filing or resolution number), so the role must come from the name or a later page there.
- 12 roles whose files disagree on the year.
- Years are written both as two and four digits; cited roles have no dots; one experiment role had a misread year
  (16.703-2002). The canonical key strips all of this.
- The PC graph `tc_fallos` (port 6379) was not read: it needs the user's credentials. It should be the next source.

## Measurements so far

- STC Rol 17.292-26 (10 pages), DeepSeek legal mode: 63 facts kept, 6 held back, 2 passes, 179 s, $0.006. One known
  wrong page (the rejection cited to p1 instead of p9) got through.
- DASH egg cooker manual (19 pages), DeepSeek one page per request: 268 facts, 27 dropped, 94 s, $0.017.
- Qwen 3.8 27B on the RTX 4090, same ruling, two passes: 58 kept, 5 review, 1 dropped, ~95 s, free.
