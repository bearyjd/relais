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

package cc.grepon.relais.chat

import cc.grepon.relais.ParsedTurn
import cc.grepon.relais.data.ChatTurn

/**
 * Maps persisted turns to request history, EXCLUDING the final (live) user turn.
 *
 * [allTurns] is expected ordered oldest-first (as returned by `ChatDao.turnsFor` /
 * `ChatRepository.turnsFor`, `ORDER BY createdAt ASC, id ASC`), with the current live turn always
 * last. Callers (`ChatViewModel.streamAndPersist`) pass that live turn separately as `userText`, so
 * it must not also appear in `history` or the engine/HTTP-server would see it twice.
 */
fun historyForRequest(allTurns: List<ChatTurn>): List<ParsedTurn> =
  allTurns.dropLast(1).map { turn -> ParsedTurn(role = turn.role, text = turn.content) }
