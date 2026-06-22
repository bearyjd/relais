/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This file is part of Relais.
 *
 * Relais is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Relais is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along
 * with Relais. If not, see <https://www.gnu.org/licenses/>.
 */

package cc.grepon.relais.imagegen

/**
 * The tiny binder contract between the node (main process) and the process-isolated [ImageGenService]
 * (`android:process=":imagegen"`). Messenger-based: only small CONTROL data crosses the binder — the
 * generated PNG is written to a shared cache file and handed back by PATH, because a 512×512 PNG can
 * exceed the ~1 MB binder transaction limit (see the #16 architecture in the plan).
 *
 * Flow: node binds → sends [MSG_GENERATE] with the request bundle + a `replyTo` Messenger → service
 * does exactly ONE generate → replies [MSG_RESULT] ([KEY_PNG_PATH] + [KEY_PID]) or [MSG_ERROR]
 * ([KEY_ERROR] + [KEY_PID]) → self-kills. The node reads + deletes the PNG and hard-kills [KEY_PID]
 * (belt-and-suspenders with the service's self-kill). One generate per process, ever — the only
 * sd.cpp primitive proven stable on-device.
 */
object ImageGenIpc {
  /** Service action; the node binds with an explicit intent to this. */
  const val SERVICE_CLASS = "cc.grepon.relais.imagegen.ImageGenService"

  // ---- message `what` codes ----
  /** node → service: generate one image from the request bundle below. */
  const val MSG_GENERATE = 1
  /** service → node: a PNG was written; reply carries [KEY_PNG_PATH] + [KEY_PID]. */
  const val MSG_RESULT = 2
  /** service → node: generation failed; reply carries [KEY_ERROR] + [KEY_PID]. */
  const val MSG_ERROR = 3

  // ---- request bundle keys (node → service) ----
  /** Absolute path to the local GGUF model file; opened via `ModelSpec.localFile`. Required. */
  const val KEY_MODEL_PATH = "model_path"
  const val KEY_PROMPT = "prompt"
  const val KEY_WIDTH = "width"
  const val KEY_HEIGHT = "height"
  const val KEY_STEPS = "steps"
  /** Seed as a `Long` (sd.cpp seed is 64-bit). */
  const val KEY_SEED = "seed"
  /** Classifier-free-guidance scale as a `Float` (SD-Turbo wants ~1.0). */
  const val KEY_CFG = "cfg"

  // ---- reply bundle keys (service → node) ----
  /** Absolute path of the written PNG in the shared cacheDir; the node reads then deletes it. */
  const val KEY_PNG_PATH = "png_path"
  /** Human-readable failure reason (never raw user input echoed back uncontrolled). */
  const val KEY_ERROR = "error"
  /** The `:imagegen` process pid, so the node can hard-kill it after reading the reply. */
  const val KEY_PID = "pid"

  /** Subdirectory under `cacheDir` where generated PNGs are written (shared across both processes). */
  const val PNG_CACHE_DIR = "imagegen"
}
