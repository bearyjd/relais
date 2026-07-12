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

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.first
import cc.grepon.relais.ui.benchmark.BenchmarkScreen
import cc.grepon.relais.ui.modelmanager.ModelManagerViewModel

/**
 * The unified shell: one `RelaisTheme`-wrapped `Scaffold` with a DESIGN.md bottom nav and a
 * `NavHost` that owns every top-level destination (Dashboard, Chat, Models, and the
 * not-yet-linked Benchmark route). Configure remains its own `Intent`-launched activity — it is
 * not a NavHost destination — reached from Dashboard's "CONFIGURE ›" action link.
 */
@Composable
fun RelaisAppShell(
  modelManagerViewModel: ModelManagerViewModel,
  deepLinkUri: android.net.Uri? = null,
) {
  RelaisTheme {
    val navController = rememberNavController()
    // One-shot: honor an incoming deep link once, on first composition / whenever the URI changes.
    LaunchedEffect(deepLinkUri) {
      val route = resolveShellDeepLink(deepLinkUri?.scheme, deepLinkUri?.host) ?: return@LaunchedEffect
      // On a COLD start via a deep link this effect runs before the NavHost below has attached its
      // graph to the controller; navigating then throws "Navigation graph has not been set".
      // Suspend until the graph is live (first back-stack entry emitted) before navigating.
      navController.currentBackStackEntryFlow.first()
      navController.navigate(route) { launchSingleTop = true }
    }
    Scaffold(
      containerColor = Charcoal,
      bottomBar = { RelaisBottomBar(navController) },
    ) { padding ->
      NavHost(
        navController = navController,
        startDestination = startDestinationRoute(),
        modifier = Modifier.padding(padding),
      ) {
        composable(RelaisRoutes.DASHBOARD) {
          val ctx = LocalContext.current
          val vm: RelaisShellViewModel = viewModel()
          val state by vm.panelState.collectAsState()
          val modelDisplay by vm.modelDisplay.collectAsState()
          DashboardScreen(
            state = state,
            modelDisplay = modelDisplay,
            onPrimaryAction = { vm.onPrimaryAction() },
            onOpenConfigure = { ctx.startActivity(Intent(ctx, RelaisConfigureActivity::class.java)) },
            onOpenModelSheet = { navController.navigate(RelaisRoutes.MODELS) },
          )
        }
        composable(RelaisRoutes.CHAT) { ChatScreen() }
        composable(RelaisRoutes.MODELS) { ModelsScreen() }
        composable("${RelaisRoutes.BENCHMARK}/{modelName}") { entry ->
          val modelName = entry.arguments?.getString("modelName") ?: ""
          modelManagerViewModel.getModelByName(modelName)?.let { model ->
            BenchmarkScreen(
              initialModel = model,
              modelManagerViewModel = modelManagerViewModel,
              onBackClicked = { navController.navigateUp() },
            )
          }
        }
      }
    }
  }
}

/**
 * DESIGN.md-styled bottom nav: a `Panel` `NavigationBar` topped with a 1dp `Line` hairline
 * divider, labels only (no icons), Amber for the selected route and Muted otherwise.
 */
@Composable
private fun RelaisBottomBar(navController: NavHostController) {
  val backStack by navController.currentBackStackEntryAsState()
  val currentRoute = backStack?.destination?.route
  Column {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    NavigationBar(containerColor = Panel, tonalElevation = 0.dp) {
      RELAIS_BOTTOM_NAV.forEach { item ->
        NavigationBarItem(
          selected = currentRoute == item.route,
          onClick = {
            navController.navigate(item.route) {
              launchSingleTop = true
              popUpTo(startDestinationRoute()) { saveState = true }
              restoreState = true
            }
          },
          icon = {},
          label = {
            Text(
              item.label,
              fontFamily = FontFamily.Monospace,
              fontSize = 11.sp,
              letterSpacing = 1.5.sp,
              color = if (currentRoute == item.route) Amber else Muted,
            )
          },
          colors =
            NavigationBarItemDefaults.colors(
              indicatorColor = Color.Transparent,
              selectedTextColor = Amber,
              unselectedTextColor = Muted,
            ),
        )
      }
    }
  }
}
