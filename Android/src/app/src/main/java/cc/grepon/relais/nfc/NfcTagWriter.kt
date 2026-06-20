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

package cc.grepon.relais.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable

/** Outcome of writing a workflow URI to a presented tag. */
sealed interface WriteResult {
  data object Success : WriteResult

  data object ReadOnly : WriteResult

  data class TooSmall(val needed: Int, val capacity: Int) : WriteResult

  data class Failed(val message: String) : WriteResult
}

/** Writes a Relais workflow NDEF URI record to a presented [Tag] (already NDEF or NDEF-formatable). */
object NfcTagWriter {
  fun write(tag: Tag, uri: String): WriteResult {
    val message = NdefMessage(NdefRecord.createUri(uri))
    val needed = message.toByteArray().size

    Ndef.get(tag)?.let { ndef ->
      return try {
        ndef.connect()
        when {
          !ndef.isWritable -> WriteResult.ReadOnly
          ndef.maxSize < needed -> WriteResult.TooSmall(needed, ndef.maxSize)
          else -> {
            ndef.writeNdefMessage(message)
            WriteResult.Success
          }
        }
      } catch (e: Exception) {
        WriteResult.Failed(e.message ?: "write failed")
      } finally {
        runCatching { ndef.close() }
      }
    }

    val formatable = NdefFormatable.get(tag) ?: return WriteResult.Failed("tag is not NDEF-compatible")
    return try {
      formatable.connect()
      formatable.format(message)
      WriteResult.Success
    } catch (e: Exception) {
      WriteResult.Failed(e.message ?: "format failed")
    } finally {
      runCatching { formatable.close() }
    }
  }
}
