package com.proasiste.lite.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** App language. Compose state, so every tr() on screen re-renders when it changes. Saved by PlantroomModel. */
object L10n {
    var spanish by mutableStateOf(false)
}

/** The English or Spanish text, by the current app language. Messages already shown keep their language. */
fun tr(en: String, es: String) = if (L10n.spanish) es else en
