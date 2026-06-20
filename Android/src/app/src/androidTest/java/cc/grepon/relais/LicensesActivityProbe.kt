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

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.grepon.relais.ui.home.LicensesActivity
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.util.withJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof that the FOSS [LicensesActivity] (AboutLibraries) actually works at runtime.
 * Uses ActivityScenario (lifecycle-driven) + a direct data-load — NOT Espresso/Compose-test (whose
 * input-injection reflection is broken on Android 16) — so it's display-independent (runs on a
 * folded/locked device) and bypasses the activity's `exported="false"`.
 */
@RunWith(AndroidJUnit4::class)
class LicensesActivityProbe {

  /** The plugin-generated R.raw.aboutlibraries parses at runtime into a non-empty library list. */
  @Test
  fun licenseDataLoadsNonEmpty() {
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    val libs = Libs.Builder().withJson(ctx, R.raw.aboutlibraries).build()
    assertTrue("expected non-empty AboutLibraries license data", libs.libraries.isNotEmpty())
  }

  /** Launching composes the whole screen (Scaffold/TopAppBar/LazyColumn); reaching RESUMED without
   * the scenario throwing proves no render-time crash. */
  @Test
  fun activityLaunchesToResumedWithoutCrash() {
    ActivityScenario.launch(LicensesActivity::class.java).use { scenario ->
      scenario.moveToState(Lifecycle.State.RESUMED)
      assertEquals(Lifecycle.State.RESUMED, scenario.state)
    }
  }
}
