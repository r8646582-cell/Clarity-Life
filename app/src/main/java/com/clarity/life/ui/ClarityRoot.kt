package com.clarity.life.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clarity.life.ui.know.KnowScreen
import com.clarity.life.ui.plan.PlanScreen
import com.clarity.life.ui.settings.SettingsScreen
import com.clarity.life.ui.talk.TalkScreen
import com.clarity.life.ui.theme.clarity

/** The three tabs of the app, plus Settings, which is pushed on top rather than being a tab. */
enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Talk("talk", "Talk", Icons.Outlined.ChatBubbleOutline),
    Plan("plan", "Plan", Icons.AutoMirrored.Outlined.ListAlt),
    Know("know", "What I know", Icons.Outlined.AutoAwesome),
}

const val SETTINGS_ROUTE = "settings"
const val RELIABLE_ROUTE = "reliable"
const val PROMPTS_ROUTE = "prompts"
const val GUIDE_ROUTE = "guide"

@Composable
fun ClarityRoot() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination
    val onTab = Tab.entries.any { tab -> current?.hierarchy?.any { it.route == tab.route } == true }

    // A coral dot on the Plan tab while a letter or a review is unread (spec section 2.2).
    val reviews: com.clarity.life.ui.plan.ReviewsViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val reviewState by reviews.state.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = clarity.ground,
        bottomBar = {
            if (onTab) {
                ClarityTabBar(current?.hierarchy?.firstOrNull()?.route, planDot = reviewState.unread) { route -> nav.switchTab(route) }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(nav, startDestination = Tab.Talk.route) {
                composable(Tab.Talk.route) {
                    TalkScreen(
                        onOpenSettings = { nav.navigate(SETTINGS_ROUTE) },
                        onOpenGuide = { nav.navigate(GUIDE_ROUTE) },
                        onOpenPromises = { nav.switchTab(Tab.Plan.route) },
                        onOpenPlan = { nav.switchTab(Tab.Plan.route) },
                    )
                }
                composable(Tab.Plan.route) {
                    PlanScreen(
                        onPlanWithCoach = { nav.switchTab(Tab.Talk.route) },
                        onTalk = { nav.switchTab(Tab.Talk.route) },
                    )
                }
                composable(Tab.Know.route) { KnowScreen(onTalk = { nav.switchTab(Tab.Talk.route) }) }
                composable(SETTINGS_ROUTE) {
                    SettingsScreen(
                        onBack = { nav.popBackStack() },
                        onOpenReliable = { nav.navigate(RELIABLE_ROUTE) },
                        onOpenPrompts = { nav.navigate(PROMPTS_ROUTE) },
                        onOpenGuide = { nav.navigate(GUIDE_ROUTE) },
                    )
                }
                composable(GUIDE_ROUTE) {
                    com.clarity.life.ui.guide.GuideScreen(onBack = { nav.popBackStack() })
                }
                composable(PROMPTS_ROUTE) {
                    com.clarity.life.ui.prompts.PromptEditorScreen(onBack = { nav.popBackStack() })
                }
                composable(RELIABLE_ROUTE) {
                    com.clarity.life.ui.reliable.ReliableScreen(onBack = { nav.popBackStack() })
                }
            }
        }
    }
}

private fun androidx.navigation.NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun ClarityTabBar(currentRoute: String?, planDot: Boolean, onSelect: (String) -> Unit) {
    NavigationBar(containerColor = clarity.tabBar, contentColor = clarity.muted) {
        Tab.entries.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = { onSelect(tab.route) },
                icon = {
                    if (tab == Tab.Plan && planDot) {
                        androidx.compose.material3.BadgedBox(
                            badge = { androidx.compose.material3.Badge(containerColor = clarity.coral) },
                        ) { Icon(tab.icon, contentDescription = null) }
                    } else {
                        Icon(tab.icon, contentDescription = null)
                    }
                },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = clarity.yellow,
                    selectedTextColor = clarity.text,
                    indicatorColor = clarity.raised,
                    unselectedIconColor = clarity.muted,
                    unselectedTextColor = clarity.muted,
                ),
            )
        }
    }
}

/** Shared placeholder for the screens that arrive in later stages of the build order. */
@Composable
fun NotBuiltYet(title: String, stage: String) {
    Column(Modifier.fillMaxSize().background(clarity.ground).padding(24.dp)) {
        Text(title, style = MaterialTheme.typography.displaySmall, color = clarity.text)
        Text(stage, style = MaterialTheme.typography.bodyMedium, color = clarity.muted)
    }
}
