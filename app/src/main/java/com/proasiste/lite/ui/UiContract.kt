package com.proasiste.lite.ui

enum class UiKind(private val en: String, private val es: String) {
    PHOTO("Photo", "Foto"), AUDIO("Voice note", "Nota de voz"), REPORT("Report", "Informe");
    val label: String get() = tr(en, es)
}

/** A project: number in PXX form (also the storage key for its sources) and a display name.
 *  [incognito] masks personal data in anything shared out of the project. */
data class UiProject(val id: String, val name: String, val incognito: Boolean = false)

/** Text about to leave the app; [masked] counts the replacements made, [note] explains any limits. */
data class UiShare(val text: String = "", val masked: Int = 0, val incognito: Boolean = false, val note: String = "", val preparing: Boolean = true)

val SAMPLE_PROJECTS = listOf(UiProject("P01", "Mercer Court"), UiProject("P02", "Calder House"))

/** Null asset/kind means all. An empty asset ID explicitly selects untagged sources. */
data class UiFilters(
    val assetId: String? = null,
    val kind: UiKind? = null,
    val dateFrom: String = "",
    val dateTo: String = "",
) {
    val activeCount: Int get() = listOf(assetId != null, kind != null, dateFrom.isNotEmpty(), dateTo.isNotEmpty()).count { it }
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
)

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
    /** Set for a kept fact drawn for a spot check: rejecting removes it from the graph. */
    val edgeId: Long? = null,
)

data class UiReview(val items: List<UiReviewItem>, val sample: Boolean = false, val loading: Boolean = false)

data class UiState(
    val buildingId: String = "P01",
    /** Held-back graph facts waiting for review in this project. */
    val reviewCount: Int = 0,
    /** The review screen, when open. */
    val review: UiReview? = null,
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
    val onQuery: (String) -> Unit = {},
    val onSearch: () -> Unit = {},
    val onImportModel: () -> Unit = {},
    val onImportCorpus: () -> Unit = {},
    val onImportFiles: () -> Unit = {},
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
