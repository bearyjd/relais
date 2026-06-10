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

package cc.grepon.relais.runtime

import cc.grepon.relais.data.Model
import cc.grepon.relais.data.RuntimeType
import cc.grepon.relais.runtime.aicore.AICoreModelHelper
import cc.grepon.relais.ui.llmchat.LlmChatModelHelper

val Model.runtimeHelper: LlmModelHelper
  get() {
    if (this.runtimeType == RuntimeType.AICORE) {
      return AICoreModelHelper
    }
    return LlmChatModelHelper
  }
