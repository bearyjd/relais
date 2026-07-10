package cc.grepon.relais.spike.tensortpu

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import java.io.File
import kotlin.concurrent.thread
import kotlin.system.measureNanoTime

/**
 * Gate T-0 of docs/tensor-tpu-spike-plan.md — smallest possible standalone check that the
 * LiteRT 2.1.1 runtime + libLiteRtDispatch_GoogleTensor.so (v2.1.1 zip) reach the Tensor G5 TPU.
 *
 * Runs three cases in sequence and logs each to logcat tag [TAG]:
 *   1. CPU          — sanity baseline; proves the model itself loads and runs.
 *   2. NPU lenient  — Options(Accelerator.NPU): runtime MAY silently fall back (LiteRT #7787
 *                     observed exactly that; the latency gap vs CPU/GPU is the tell).
 *   3. NPU strict   — Options(setOf(Accelerator.NPU)): no fallback allowed; success here is the
 *                     T-0 pass signal, but T-2's hardware evidence (edgetpu/gxp nodes, dmesg,
 *                     power draw vs a forced-GPU run) is still required — a self-report is not proof.
 *
 * Model staging (must be AOT-compiled for Google Tensor — the v2.1.1 drop has no on-device
 * compiler plugin for Tensor, see README):
 *   adb push model.tflite /sdcard/Android/data/cc.grepon.relais.spike.tensortpu/files/t0-model.tflite
 */
class MainActivity : Activity() {

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logView = TextView(this).apply { setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(logView) })

        val modelFile = File(getExternalFilesDir(null), MODEL_FILE)
        if (!modelFile.exists()) {
            say("Model not found: ${modelFile.absolutePath}")
            say("Stage it with:\nadb push <aot-compiled>.tflite ${modelFile.absolutePath}")
            return
        }
        say("Model: ${modelFile.absolutePath} (${modelFile.length()} bytes)")
        say("nativeLibraryDir: ${applicationInfo.nativeLibraryDir}")
        thread { runSuite(modelFile.absolutePath) }
    }

    private fun runSuite(modelPath: String) {
        runCase("CPU (baseline)", modelPath) { CompiledModel.Options(Accelerator.CPU) }
        runCase("NPU lenient (fallback allowed)", modelPath) { CompiledModel.Options(Accelerator.NPU) }
        runCase("NPU strict (no fallback)", modelPath) { CompiledModel.Options(setOf(Accelerator.NPU)) }
        say("Suite done. Strict-NPU success + fast latency = T-0 pass; now gather the T-2 hardware evidence (see README).")
    }

    private fun runCase(label: String, modelPath: String, options: () -> CompiledModel.Options) {
        say("== $label ==")
        val model = try {
            CompiledModel.create(modelPath, options())
        } catch (t: Throwable) {
            say("$label: create FAILED — ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        try {
            val inputs = model.createInputBuffers()
            val outputs = model.createOutputBuffers()
            repeat(WARMUP_RUNS) { model.run(inputs, outputs) }
            val timedUs = List(TIMED_RUNS) { measureNanoTime { model.run(inputs, outputs) } / 1_000 }
            val sorted = timedUs.sorted()
            say(
                "$label: OK — median ${sorted[sorted.size / 2]} µs, " +
                    "min ${sorted.first()} µs, max ${sorted.last()} µs over $TIMED_RUNS runs"
            )
        } catch (t: Throwable) {
            say("$label: run FAILED — ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            try {
                model.close()
            } catch (t: Throwable) {
                Log.w(TAG, "close failed for $label", t)
            }
        }
    }

    private fun say(message: String) {
        Log.i(TAG, message)
        runOnUiThread { logView.append(message + "\n\n") }
    }

    private companion object {
        const val TAG = "RelaisTpuT0"
        const val MODEL_FILE = "t0-model.tflite"
        const val WARMUP_RUNS = 3
        const val TIMED_RUNS = 20
    }
}
