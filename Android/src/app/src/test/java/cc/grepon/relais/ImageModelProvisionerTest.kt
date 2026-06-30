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

import cc.grepon.relais.imagegen.ImageModelProvisioner
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the image-model provisioner's reuse + integrity logic (image-gen #16 PR-B). */
class ImageModelProvisionerTest {

  private fun tmp(bytes: ByteArray): File =
    File.createTempFile("relais-img", ".bin").apply { deleteOnExit(); writeBytes(bytes) }

  @Test fun `isComplete requires an exact size match when the size is known`() {
    val f = tmp(ByteArray(10))
    assertTrue(ImageModelProvisioner.isComplete(f, 10))
    assertFalse(ImageModelProvisioner.isComplete(f, 9))
    assertFalse(ImageModelProvisioner.isComplete(f, 11))
  }

  @Test fun `isComplete with unknown size accepts any non-empty file`() {
    assertTrue(ImageModelProvisioner.isComplete(tmp(ByteArray(1)), -1))
    assertFalse(ImageModelProvisioner.isComplete(tmp(ByteArray(0)), -1))
    assertFalse(ImageModelProvisioner.isComplete(tmp(ByteArray(0)), 0))
  }

  @Test fun `sha256Hex matches the known vector for abc`() {
    val f = tmp("abc".toByteArray())
    assertEquals(
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
      ImageModelProvisioner.sha256Hex(f),
    )
  }

  @Test fun `sha256Hex of an empty file is the known empty digest`() {
    val f = tmp(ByteArray(0))
    assertEquals(
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      ImageModelProvisioner.sha256Hex(f),
    )
  }
}
