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

package cc.grepon.relais

import cc.grepon.relais.batch.BatchChat
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure JVM tests for [BatchChat] — extracting/shaping a batch chat job. */
class BatchChatTest {

  @Test fun `extracts the last user text and the system prompt`() {
    val body = JSONObject(
      """{"messages":[{"role":"system","content":"be brief"},{"role":"user","content":"hi"},
         {"role":"assistant","content":"hello"},{"role":"user","content":"what is 2+2"}],
         "temperature":0.5,"seed":7}""",
    )
    val r = BatchChat.extract(body)!!
    assertEquals("what is 2+2", r.text) // last user turn wins
    assertEquals("be brief", r.system)
    assertEquals(0.5, r.temperature!!, 1e-9)
    assertEquals(7, r.seed)
  }

  @Test fun `extracts text from an OpenAI content-parts array`() {
    val body = JSONObject(
      """{"messages":[{"role":"user","content":[{"type":"text","text":"hello"},{"type":"text","text":"world"}]}]}""",
    )
    assertEquals("hello world", BatchChat.extract(body)!!.text)
  }

  @Test fun `null when there is no usable user turn`() {
    assertNull(BatchChat.extract(JSONObject("""{"messages":[{"role":"system","content":"x"}]}""")))
    assertNull(BatchChat.extract(JSONObject("""{"messages":[]}""")))
    assertNull(BatchChat.extract(JSONObject("""{"foo":1}""")))
  }

  @Test fun `envelope is OpenAI-shaped`() {
    val e = BatchChat.envelope("job1", "4", 3, "stop")
    assertEquals("batch-job1", e.getString("id"))
    assertEquals("chat.completion", e.getString("object"))
    assertEquals("4", e.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
    assertEquals("stop", e.getJSONArray("choices").getJSONObject(0).getString("finish_reason"))
    assertEquals(3, e.getJSONObject("usage").getInt("completion_tokens"))
  }
}
