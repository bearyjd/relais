/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Affero General Public License for more details.
 */

package cc.grepon.relais.widget

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import cc.grepon.relais.core.RelaisInference

private const val TAG = "WidgetActions"

/** Action parameter key: which template's canned prompt to run (its system prompt is resolved later). */
val TemplateIdKey = ActionParameters.Key<String>("template_id")

/**
 * RUN: fires the canned prompt for the supplied template — but ONLY behind the cold-start guard.
 *
 * Re-reads [RelaisInference.isReady] HERE (the tap is the gate, not the render): if the node went OFF
 * since the widget last rendered, [shouldRunWidgetPrompt] is false and we DO NOT enqueue inference —
 * a tap can never cold-start the multi-GB engine. When the gate passes we flip the persisted state to
 * LOADING immediately (responsive UI) and hand the long inference to [WidgetPromptWorker] (a Worker,
 * so it survives the ~10 s broadcast limit).
 */
class RunPromptAction : ActionCallback {
  override suspend fun onAction(
    context: Context,
    glanceId: GlanceId,
    parameters: ActionParameters,
  ) {
    val templateId = parameters[TemplateIdKey]?.takeIf { it.isNotBlank() }
    if (!shouldRunWidgetPrompt(RelaisInference.isReady(), WIDGET_PROMPT)) {
      Log.i(TAG, "node not ready; widget tap ignored (cold-start guard)")
      // Re-render the (now off) status without enqueuing inference; leave any prior state intact.
      RelaisWidget().update(context, glanceId)
      return
    }
    updateAppWidgetState(context, glanceId) { prefs ->
      writeState(prefs, WidgetUiState.idle().loading(WIDGET_PROMPT))
    }
    RelaisWidget().update(context, glanceId)
    WidgetPromptWorker.enqueue(context, glanceId, templateId)
  }
}

/** CLEAR: resets the persisted widget state to IDLE (drops any stored answer from the launcher). */
class ClearAction : ActionCallback {
  override suspend fun onAction(
    context: Context,
    glanceId: GlanceId,
    parameters: ActionParameters,
  ) {
    updateAppWidgetState(context, glanceId) { prefs -> writeState(prefs, WidgetUiState.idle()) }
    RelaisWidget().update(context, glanceId)
  }
}
