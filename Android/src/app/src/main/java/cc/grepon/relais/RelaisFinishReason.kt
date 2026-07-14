/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.grepon.relais

/**
 * Why a streaming decode callback asked to stop early. Distinguishes the two cancel causes that issue
 * #22 must treat differently:
 *  - [THERMAL]: the caller's cooperative-cancel predicate ([RelaisEngine.generate]'s `shouldCancel`,
 *    wired to [ThermalGovernor.shouldTruncate]) returned true mid-decode -> the answer is TRUNCATED.
 *  - [BROKEN_PIPE]: the `onToken`/`onReasoning` lambda threw because the client disconnected -> the
 *    stream is aborted, but this is NOT truncation (no client remains to read finish_reason).
 */
enum class DecodeCancelCause { THERMAL, BROKEN_PIPE }

/**
 * Running cancel bookkeeping for one decode. [canceled] stops further streaming to the client;
 * [truncated] additionally records that the stop was a device-protective truncation (-> `"length"`).
 */
data class DecodeCancelState(val canceled: Boolean = false, val truncated: Boolean = false)

/**
 * OpenAI-compatible `finish_reason` values plus the cancel-cause / truncation derivation for issue #22.
 *
 * A decode cut short by the thermal cooperative-cancel is INCOMPLETE: OpenAI reports that as
 * `"length"` (the response hit a limit), NOT `"stop"` (the model finished on its own). Reporting
 * `"stop"` would tell a client the reply is complete when it was actually truncated — clients that
 * inspect `finish_reason` to decide whether to continue/retry would be misled.
 *
 * BEST-EFFORT caveat: the cooperative cancel does not natively stop the decode (the bundled litertlm
 * has no cancellation API — see SPIKE-FINDINGS / Gate 2). `"length"` is therefore surfaced only when a
 * decode callback observes the cancel before the native run reaches its natural end; a short tail
 * decoded after the device crosses CRITICAL may still report `"stop"`. This is strictly better than
 * the prior unconditional `"stop"`, not a hard guarantee.
 *
 * Pure (no Android types) so the mapping and the cancel fold are covered by fast JVM unit tests
 * (see `RelaisFinishReasonTest`).
 */
object RelaisFinishReason {
  /** The model ended the turn on its own (natural completion). */
  const val STOP = "stop"

  /** The decode was cut short before natural completion (here: thermal truncation). */
  const val LENGTH = "length"

  /** The model requested one or more tool calls instead of a final answer. */
  const val TOOL_CALLS = "tool_calls"

  /**
   * Folds one cancel event into the running [DecodeCancelState].
   *
   * [DecodeCancelCause.THERMAL] marks both `canceled` and `truncated`. [DecodeCancelCause.BROKEN_PIPE]
   * marks `canceled` only and PRESERVES any prior truncation — a thermal cancel followed by a late
   * broken pipe must still report `"length"`, and a broken pipe is never upgraded to a truncation.
   */
  fun applyCancel(prev: DecodeCancelState, cause: DecodeCancelCause): DecodeCancelState = when (cause) {
    DecodeCancelCause.THERMAL -> prev.copy(canceled = true, truncated = true)
    DecodeCancelCause.BROKEN_PIPE -> prev.copy(canceled = true)
  }

  /**
   * `finish_reason` for a text / structured-output completion.
   *
   * @param truncated true iff the decode was thermally truncated (see [applyCancel]) — NOT a natural
   *   end, and NOT a broken-pipe abort.
   * @return [LENGTH] when truncated, otherwise [STOP].
   */
  fun forCompletion(truncated: Boolean): String = if (truncated) LENGTH else STOP

  /**
   * True when [message] is the terminal litertlm surfaces after a deliberate `Conversation.cancelProcess()`.
   *
   * A native mid-decode cancel (issue #125) ends the stream via `MessageCallback.onError` carrying
   * `"Process cancelled."` (verified on-device, both lanes — see docs/litertlm-native-api.md §7.5).
   * When Relais itself requested the cancel, that terminal is EXPECTED and must be folded into the
   * already-computed `finish_reason` (a truncated `"length"` or a broken-pipe `"stop"`), NOT re-thrown
   * as an inference error. Matched loosely (contains "cancel", case-insensitive) so a punctuation or
   * wording tweak in a future AAR doesn't turn a clean cancel back into a spurious error turn.
   */
  fun isCancellationTerminal(message: String?): Boolean =
    message?.contains("cancel", ignoreCase = true) == true
}
