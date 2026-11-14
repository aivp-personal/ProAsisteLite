package com.proasiste.lite.ui

enum class UiKind(private val en: String, private val es: String) {
    PHOTO("Photo", "Foto"), AUDIO("Voice note", "Nota de voz"), REPORT("Report", "Informe");
    val label: String get() = tr(en, es)
}

/** A project: number in PXX form (also the storage key for its sources) and a display name.
 *  [incognito] masks personal data in anything shared out of the project, and keeps answers on the phone.
 *  [answerModel] is the OpenRouter model that writes its answers; "" means Gemini Nano on the phone. */
data class UiProject(val id: String, val name: String, val incognito: Boolean = false, val answerModel: String = "")

/** A model the user can pick to write answers. Prices are US dollars per million tokens. */
data class UiModel(val id: String, val name: String, val inputPerMillion: Double, val outputPerMillion: Double, val context: Int)

/** OpenRouter's catalog for the model picker: [suggested] first, then [all], newest first. */
data class UiCatalog(val suggested: List<UiModel> = emptyList(), val all: List<UiModel> = emptyList(), val loading: Boolean = false, val error: String? = null)

/** Text about to leave the app; [masked] counts the replacements made, [note] explains any limits. */
data class UiShare(val text: String = "", val masked: Int = 0, val incognito: Boolean = false, val note: String = "", val preparing: Boolean = true)

val SAMPLE_PROJECTS = listOf(UiProject("P01", "Mercer Court"), UiProject("P02", "Calder House"))

/** Null asset/kind means all. An empty asset ID explicitly selects untagged sources. */
data class UiFilters(
    val assetId: String? = null,
    val kind: UiKind? = null,
    val dateFrom: String = "",
    val dateTo: String = "",
    /** A source must carry every one of these tags. */
    val tags: Set<String> = emptySet(),
) {
    val activeCount: Int get() = listOf(assetId != null, kind != null, dateFrom.isNotEmpty(), dateTo.isNotEmpty(), tags.isNotEmpty()).count { it }
    val metadataCount: Int get() = listOf(assetId != null, dateFrom.isNotEmpty(), dateTo.isNotEmpty()).count { it }
}

data class UiItem(
    val id: String,
    val title: String,
    val kind: UiKind,
    val buildingId: String,
    val assetId: String = "",
    val date: String = "",
    val localPath: String = "",
    val excerpt: String = "",
    val sourceLocation: String = "",
    val synthetic: Boolean = true,
    val score: Float? = null,
    val tags: List<String> = emptyList(),
    /** Boxes to highlight when the PDF opens, "page:x0,y0,x1,y1;…" on DocLang's 500 grid; "" to find [excerpt]'s words instead. */
    val highlight: String = "",
)

/** A decision the user took, with when. */
data class UiDecision(val value: String, val note: String, val created: Long)

/** A saved report: [cited] are its passages, each opening its page highlighted; [decision] the latest one taken on it. */
data class UiReport(val id: Long, val title: String, val question: String, val body: String, val model: String, val cited: List<UiItem>,
                    val created: Long, val updated: Long, val decision: UiDecision? = null, val history: List<UiDecision> = emptyList())

/** A file the user is about to save: the app asks where, then writes it. */
data class UiExport(val name: String, val mime: String)

/** A tag in use in the project and how many files carry it. */
data class UiTagCount(val name: String, val count: Int)
/** A tag rule: [kind] is "carpeta" (each subfolder name), "nombre" ([pattern] in the file name gives [tag]) or "año". */
data class UiTagRule(val id: Long, val kind: String, val pattern: String = "", val tag: String = "")
/** A tag AI may choose, with the description it reads to decide. */
data class UiTagDef(val name: String, val description: String)

enum class UiClaimProblem { CONTRADICTED, NOT_SUPPORTED, UNSURE }
/** A contradicted sentence is removed from the answer, and [passages] show what its sources say instead. */
data class UiClaimIssue(val text: String, val citations: List<Int>, val problem: UiClaimProblem, val passages: List<UiItem> = emptyList())

/** Jev's check of an answer's citations. [uncited] sentences cite nothing, so they weren't checked. */
data class UiCitationCheck(
    val supported: Int = 0,
    val issues: List<UiClaimIssue> = emptyList(),
    val uncited: Int = 0,
    val error: String? = null,
)

/** A generated answer; [sources] are the passages it was given, numbered from 1 in order.
 *  [jevNote] says what Jev did with this search; [jevFailed] marks it as a warning. */
data class UiAnswer(
    val text: String = "",
    val status: String = "",
    val sources: List<UiItem> = emptyList(),
    val working: Boolean = true,
    val jevNote: String = "",
    val jevFailed: Boolean = false,
    val check: UiCitationCheck? = null,
    /** Listed directly from the project graph, with no model writing it. */
    val fromGraph: Boolean = false,
    /** Who wrote it: "Gemini Nano" or the OpenRouter model's name. */
    val writer: String = "Gemini Nano",
    /** Written by an OpenRouter model, so the question and passages left the phone. */
    val cloud: Boolean = false,
    /** What left the phone and what it cost, or why the chosen model wasn't used. */
    val writerNote: String = "",
    /** The note is a warning: the chosen model failed and Nano answered instead. */
    val writerFailed: Boolean = false,
)

/** A graph fact held back by the checks, or a kept one drawn for a spot check, waiting for the steward's decision. */
data class UiReviewItem(
    val id: Long,
    val sourceId: String,
    val title: String,
    val page: Int,
    val fromType: String,
    val from: String,
    val relation: String,
    val toType: String,
    val to: String,
    val statement: String,
    /** Why it was held back, in plain words. "muestra" for a kept fact drawn to measure the error rate. */
    val reason: String,
    /** The page where the checks would pass, when the held-back reason was the page. */
    val suggestedPage: Int? = null,
    /** Set for a kept fact (in the graph): rejecting removes it, approving marks it reviewed, editing replaces it. */
    val edgeId: Long? = null,
    /** Who wrote the fact: "reglas", "deepseek/…", "revisado" (a steward stood behind it) or "" for an import. */
    val extractor: String = "",
    /** The sentence on the page that best supports a kept AI fact; "" when no sentence does; null when not looked up. */
    val evidence: String? = null,
    /** Where the fact sits on the page, from the block it was found in; "" when unknown (older facts). */
    val place: String = "",
) {
    val checked: Boolean get() = extractor == "revisado"
    val byRules: Boolean get() = extractor == "reglas"
}

/**
 * The review screen's contents. [sample] is a spot check of kept facts; [pageMode] reviews one document or page with
 * its held-back facts first and the kept ones after, grouped by page; [pendingOnly] shows the held-back facts alone.
 */
data class UiReview(val items: List<UiReviewItem>, val sample: Boolean = false, val loading: Boolean = false, val pageMode: Boolean = false, val pendingOnly: Boolean = false, val title: String = "", val subtitle: String = "")

/** What a document or page holds for review: [pending] held-back facts, [facts] in the graph, [unchecked] nobody stood behind yet. */
data class UiPageStatus(val pending: Int = 0, val facts: Int = 0, val unchecked: Int = 0)

/** A background import or indexing run: the app stays usable meanwhile. [pausing] once Pause was tapped. */
data class UiIndexing(val label: String, val progress: Float, val pausing: Boolean = false)

data class UiState(
    val buildingId: String = "P01",
    /** The background import/indexing run, or null when none is running. */
    val indexing: UiIndexing? = null,
    /** Files in this project still to index, not counting scans waiting for OCR. */
    val remainingCount: Int = 0,
    /** PDFs in this project with no text on any page: scans waiting for OCR. */
    val scanCount: Int = 0,
    /** Files found in picked folders and still waiting to be copied into this project. */
    val queuedCount: Int = 0,
    /** Held-back graph facts waiting for review in this project. */
    val reviewCount: Int = 0,
    /** The review screen, when open. */
    val review: UiReview? = null,
    /** Review status per document (by source id) in this project; documents with no graph facts are absent. */
    val docReview: Map<String, UiPageStatus> = emptyMap(),
    /** Review status per page of the document open in the viewer. */
    val pageReview: Map<Int, UiPageStatus> = emptyMap(),
    val projects: List<UiProject> = SAMPLE_PROJECTS,
    val query: String = "",
    val items: List<UiItem> = emptyList(),
    val totalCount: Int = 0,
    val indexedCount: Int = 0,
    val photoCount: Int = 0,
    val audioCount: Int = 0,
    val reportCount: Int = 0,
    val modelReady: Boolean = false,
    val modelLabel: String = "EmbeddingGemma 2",
    val busy: Boolean = false,
    val progress: Float? = null,
    val status: String = "",
    val error: String? = null,
    val searchMillis: Long? = null,
    val isSearching: Boolean = false,
    val playingId: String? = null,
    val recording: Boolean = false,
    val filters: UiFilters = UiFilters(),
    val availableAssetIds: List<String> = emptyList(),
    val filterError: String? = null,
    val answer: UiAnswer? = null,
    val share: UiShare? = null,
    val jevKeySaved: Boolean = false,
    /** Jev reranks and checks answers only when this is on, even with a key saved. */
    val jevEnabled: Boolean = false,
    val jevKeyChecking: Boolean = false,
    val jevKeyError: String? = null,
    /** OpenRouter's model list, loaded when the picker opens. */
    val catalog: UiCatalog = UiCatalog(),
    /** Tags in use in this project, most used first. */
    val tagCounts: List<UiTagCount> = emptyList(),
    /** Each source's visible tags in this project, by source id. */
    val tagsBySource: Map<String, List<String>> = emptyMap(),
    val tagRules: List<UiTagRule> = emptyList(),
    /** Tags AI may choose in this project. */
    val aiTags: List<UiTagDef> = emptyList(),
    /** What this project's graph extracts: automatic, or its own node and link types. */
    val graphSchema: com.proasiste.lite.graph.GraphSchema = com.proasiste.lite.graph.GraphSchema(),
    /** This project's saved reports, most recently changed first. */
    val reports: List<UiReport> = emptyList(),
    /** The report open on screen. */
    val openReport: UiReport? = null,
    /** The latest decision on each file of this project, by source id. */
    val fileDecisions: Map<String, UiDecision> = emptyMap(),
    /** A file waiting for the user to choose where to save it. */
    val export: UiExport? = null,
)

data class UiActions(
    val onBuilding: (String) -> Unit = {},
    /** Returns an error message, or null when the project was created and selected. */
    val onCreateProject: (String, String) -> String? = { _, _ -> null },
    val onDeleteProject: (String) -> Unit = {},
    val onToggleIncognito: (String) -> Unit = {},
    /** true switches the app to Spanish, false to English. */
    val onLanguage: (Boolean) -> Unit = {},
    /** Deletes one source (by item id) with its index and the app's copy of the file. */
    val onDeleteSource: (String) -> Unit = {},
    /** Builds the share preview for the current answer, masked when the project is incognito. */
    val onShareAnswer: () -> Unit = {},
    val onConfirmShare: (String) -> Unit = {},
    val onDismissShare: () -> Unit = {},
    /** Checks the OpenRouter key with OpenRouter, then stores it encrypted. */
    val onSaveJevKey: (String) -> Unit = {},
    val onRemoveJevKey: () -> Unit = {},
    val onJevEnabled: (Boolean) -> Unit = {},
    /** Sets who writes the current project's answers: an OpenRouter model id, or "" for Gemini Nano. */
    val onAnswerModel: (String) -> Unit = {},
    /** Loads OpenRouter's model list for the picker. */
    val onLoadModels: () -> Unit = {},
    /** Adds a tag to a source by hand (source id, tag). */
    val onAddTag: (String, String) -> Unit = { _, _ -> },
    /** Removes a tag from a source by hand; rules and AI won't put it back. */
    val onRemoveTag: (String, String) -> Unit = { _, _ -> },
    /** Adds or removes a tag from the filter: shown sources must carry every selected tag. */
    val onToggleTagFilter: (String) -> Unit = {},
    /** Adds a rule (kind, words to look for, tag) and applies the project's rules. */
    val onAddTagRule: (String, String, String) -> Unit = { _, _, _ -> },
    val onDeleteTagRule: (Long) -> Unit = {},
    val onApplyTagRules: () -> Unit = {},
    /** Adds or changes a tag AI may choose (name, description); an empty description takes it off AI's list. */
    val onDefineTag: (String, String) -> Unit = { _, _ -> },
    /** Queues the project's indexed PDFs for AI tagging in the background. */
    val onTagWithAi: () -> Unit = {},
    /** Graph types: "auto" or "custom". */
    val onSchemaMode: (String) -> Unit = {},
    val onAddNodeType: (String, String) -> Unit = { _, _ -> },
    val onRemoveNodeType: (String) -> Unit = {},
    /** Adds a link type (name, description, from types, to types). */
    val onAddLinkType: (String, String, Set<String>, Set<String>) -> Unit = { _, _, _, _ -> },
    val onRemoveLinkType: (String) -> Unit = {},
    /** Replaces the custom types with a copy of the Chilean rulings types. */
    val onLegalPreset: () -> Unit = {},
    /** Saves the current answer as a report, with its question and cited passages. */
    val onSaveAnswer: () -> Unit = {},
    val onOpenReport: (Long) -> Unit = {},
    val onCloseReport: () -> Unit = {},
    /** Saves a report's edited title and text (id, title, body). */
    val onSaveReport: (Long, String, String) -> Unit = { _, _, _ -> },
    val onDeleteReport: (Long) -> Unit = {},
    /** Starts a report written by hand. */
    val onNewNote: () -> Unit = {},
    /** Records a decision: target ("archivo" or "informe"), its id, the decision, a note. */
    val onDecide: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    /** Saves a report as a file: "md" or "pdf". */
    val onExportReport: (Long, String) -> Unit = { _, _ -> },
    val onExportDecisions: () -> Unit = {},
    /** Saves the project's graph facts: "csv" or "json". */
    val onExportFacts: (String) -> Unit = {},
    /** Saves one PDF (by source id) as a DocLang file. */
    val onExportDocLang: (String) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onSearch: () -> Unit = {},
    val onImportModel: () -> Unit = {},
    val onImportCorpus: () -> Unit = {},
    val onImportFiles: () -> Unit = {},
    /** Picks a folder; every PDF, photo and recording in it and its subfolders is imported and indexed in the background. */
    val onImportFolder: () -> Unit = {},
    val onImportGraph: () -> Unit = {},
    val onBuildGraph: () -> Unit = {},
    val onOpenReview: () -> Unit = {},
    val onCloseReview: () -> Unit = {},
    val onApproveReview: (UiReviewItem) -> Unit = {},
    /** Approves the item as edited: page, names, types or relation changed by the steward. */
    val onCorrectReview: (UiReviewItem) -> Unit = {},
    val onRejectReview: (UiReviewItem) -> Unit = {},
    /** Draws kept AI facts at random, to measure what the checks let through. */
    val onSampleReview: () -> Unit = {},
    /** Opens the document at the item's page. */
    val onOpenReviewPage: (UiReviewItem) -> Unit = {},
    /** Reviews everything the graph says about one document (by source id). */
    val onReviewDocument: (String) -> Unit = {},
    /** Reviews everything the graph says about one page of a document. */
    val onReviewPage: (String, Int) -> Unit = { _, _ -> },
    /** Reviews only the facts the checks held back on a document (page null) or one of its pages: the red link. */
    val onReviewPending: (String, Int?) -> Unit = { _, _ -> },
    /** Approves every kept AI fact still unchecked on a page of the open review, or in the whole review (page null). */
    val onApproveRest: (Int?) -> Unit = {},
    /** The viewer opened (or closed, with null) a document: its per-page review status is loaded. */
    val onDocumentOpen: (UiItem?) -> Unit = {},
    val onImageSearch: () -> Unit = {},
    val onRecordAudio: () -> Unit = {},
    val onOpenSource: (UiItem) -> Unit = {},
    val onPlayAudio: (UiItem) -> Unit = {},
    val onStopAudio: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onIndex: () -> Unit = {},
    val onCancelIndex: () -> Unit = {},
    val onCapturePhoto: () -> Unit = {},
    val onApplyFilters: (UiFilters) -> Boolean = { true },
    val onKindFilter: (UiKind?) -> Unit = {},
    val onResetFilters: () -> Unit = {},
)
