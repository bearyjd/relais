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

package cc.grepon.relais

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SPIKE ONLY (#252 feasibility) — **DO NOT MERGE**. Paired with a temporary `jniLibs.excludes`
 * entry that strips the sherpa TTS runtime from the APK.
 *
 * ### The claim under test
 * `docs/distribution.md` and #252 assert that unbundling sherpa is not workable, because
 * `OfflineTts`'s `<clinit>` calls `System.loadLibrary("sherpa-onnx-jni")`, and `System.loadLibrary`
 * resolves through `ClassLoader.findLibrary()` which searches only the APK's extracted
 * `nativeLibraryDir`. If that is right, pre-loading every `.so` by absolute path via
 * `System.load()` does NOT help: the failure happens at path resolution, before `dlopen`.
 *
 * That was reasoned from the AAR contents and platform behaviour, **not run on a device**. This
 * probe settles it either way. A pass here would mean #252 is cheaper than recorded and the
 * downgrade should be reversed.
 *
 * ### Staging
 * The four `.so` are pushed to the app's external files dir first (readable without permissions),
 * then copied into `filesDir` — Android will not `dlopen` from a world-writable path, so they must
 * land in app-private internal storage:
 *
 *   adb -s <serial> push libonnxruntime.so libsherpa-onnx-c-api.so \
 *     libsherpa-onnx-cxx-api.so libsherpa-onnx-jni.so \
 *     /storage/emulated/0/Android/data/com.ventouxlabs.relais.izzy/files/nativestage/
 *
 *   adb -s <serial> shell am instrument -w \
 *     -e class cc.grepon.relais.SherpaUnbundleProbe \
 *     -e RELAIS_PROBE 1 \
 *     com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class SherpaUnbundleProbe {

  private val args = InstrumentationRegistry.getArguments()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  /** Dependency order matters: the JNI lib links against the other three. */
  private val libsInLoadOrder =
    listOf(
      "libonnxruntime.so",
      "libsherpa-onnx-c-api.so",
      "libsherpa-onnx-cxx-api.so",
      "libsherpa-onnx-jni.so",
    )

  @Test
  fun systemLoadByPathDoesOrDoesNotSatisfySherpasLoadLibrary() {
    assumeTrue("pass -e RELAIS_PROBE 1", args.getString("RELAIS_PROBE") == "1")

    val stage = File(context.getExternalFilesDir(null), "nativestage")
    assumeTrue(
      "stage the 4 .so into ${stage.absolutePath} first (see the file header)",
      stage.isDirectory && libsInLoadOrder.all { File(stage, it).canRead() },
    )

    // 1. Confirm the premise: the libs really are absent from the APK's nativeLibraryDir.
    val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    val presentInApk = libsInLoadOrder.filter { File(nativeDir, it).exists() }
    Log.i(TAG, "nativeLibraryDir=$nativeDir")
    Log.i(TAG, "PREMISE sherpa libs still in APK: $presentInApk (expected [] for a valid test)")

    // 2. Copy into app-private internal storage. dlopen refuses world-writable paths, so the
    //    external staging dir cannot be loaded from directly.
    val dest = File(context.filesDir, "nativelib").apply { mkdirs() }
    libsInLoadOrder.forEach { name ->
      val out = File(dest, name)
      File(stage, name).inputStream().use { i -> out.outputStream().use { o -> i.copyTo(o) } }
      out.setReadable(true, true)
      out.setExecutable(true, true)
    }
    Log.i(TAG, "staged ${libsInLoadOrder.size} libs into $dest")

    // 3. Load every lib by absolute path, in dependency order.
    val loadResults =
      libsInLoadOrder.map { name ->
        val path = File(dest, name).absolutePath
        runCatching { System.load(path) }
          .fold({ "$name: System.load OK" }, { "$name: System.load FAILED ${it::class.simpleName}: ${it.message}" })
      }
    loadResults.forEach { Log.i(TAG, "  $it") }

    // 4. The actual question: does touching OfflineTts now succeed, or does its <clinit>
    //    System.loadLibrary("sherpa-onnx-jni") still fail because findLibrary cannot resolve it?
    val verdict =
      runCatching {
        // Referencing the class forces <clinit>, which is where sherpa calls loadLibrary.
        Class.forName("com.k2fsa.sherpa.onnx.OfflineTts", true, javaClass.classLoader)
      }
        .fold(
          { "CLINIT OK — System.load(path) SATISFIED sherpa's loadLibrary. #252 is CHEAPER than recorded." },
          { t ->
            val root = generateSequence(t) { it.cause }.last()
            "CLINIT THREW ${t::class.simpleName} (root ${root::class.simpleName}: ${root.message}) " +
              "— confirms loadLibrary resolves via findLibrary, not an already-loaded soname. " +
              "#252 downgrade STANDS."
          },
        )

    Log.i(TAG, "=== VERDICT ===")
    Log.i(TAG, verdict)
    Log.i(TAG, "===============")

    // Deliberately does not assert: this probe exists to PRODUCE evidence, not to enforce a
    // conclusion. Read the verdict line out of logcat.
  }

  private companion object {
    const val TAG = "RelaisSherpaUnbundle"
  }
}
