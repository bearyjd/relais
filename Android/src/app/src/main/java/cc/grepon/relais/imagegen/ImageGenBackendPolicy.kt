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
 * Whether image generation must use the CPU backend instead of Vulkan on this device.
 *
 * sd.cpp / ggml-vulkan **deadlocks at the first Vulkan compute dispatch** on the Tensor G5's
 * PowerVR DXT-48 GPU (Pixel 10): the model loads, then the first submit never returns (issue #69;
 * upstream `Aatricks/llmedge#30`). llmedge 0.4.2 exposes `ImageRuntimeConfig.useVulkan=false` as the
 * escape hatch for exactly this "driver loads fine then hangs" case — no automatic fallback can fire
 * because the hang is post-load. Vulkan is the fast path on Mali (Tensor G3/G4), so force CPU **only**
 * on the affected devices and keep Vulkan everywhere else.
 *
 * Measured on-device (rango / Pixel 10 Pro Fold): CPU SD-Turbo 512×512 4-step ≈ 269 s — comparable
 * to the Vulkan-cold path on Mali, and it actually produces an image instead of wedging.
 *
 * Pure so it is JVM-testable; the caller supplies [isPixel10] from
 * [cc.grepon.relais.common.isPixel10]. This is a distinct, still-needed SoC gate: the PowerVR/Vulkan
 * image-gen deadlock on Pixel 10 is unrelated to (and outlived) the former LLM `isG5Incompatible`
 * gate, which was removed once gemma-4-E4B was verified to serve on Tensor G5.
 */
fun imageGenForcesCpuBackend(isPixel10: Boolean): Boolean = isPixel10
