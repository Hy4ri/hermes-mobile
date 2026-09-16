package com.m57.hermescontrol.ui.kanban

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable

/** Keep the cache path while the external document picker recreates the Activity (#1137). */
@Composable
internal fun rememberPendingKanbanExportPath(): MutableState<String?> = rememberSaveable { mutableStateOf(null) }
