package com.offlinetutor.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.offlinetutor.R
import com.offlinetutor.ui.screens.BenchmarkScreen
import com.offlinetutor.ui.screens.ChatScreen
import com.offlinetutor.ui.screens.FixScreen
import com.offlinetutor.ui.screens.HomeScreen
import com.offlinetutor.ui.screens.IntakeScreen
import com.offlinetutor.ui.screens.ModelsScreen
import com.offlinetutor.ui.screens.ProfilesScreen
import com.offlinetutor.ui.screens.ProgressScreen
import com.offlinetutor.ui.screens.QuizScreen
import com.offlinetutor.ui.screens.RoadmapScreen
import com.offlinetutor.ui.screens.SettingsScreen
import com.offlinetutor.ui.theme.LocalExtras

object Routes {
    const val PROFILES = "profiles?auto={auto}"
    fun profiles(auto: Boolean) = "profiles?auto=$auto"
    const val INTAKE = "intake"
    const val MAIN = "main"
    const val BENCHMARK = "benchmark"
    const val MODELS = "models"
    const val SETTINGS = "settings"

    const val HOME = "home"
    const val ROADMAP = "roadmap"
    const val QUIZ = "quiz?course={course}&subtopic={subtopic}"
    fun quiz(course: String? = null, subtopic: String? = null) =
        "quiz?course=${Uri.encode(course ?: "")}&subtopic=${Uri.encode(subtopic ?: "")}"
    const val CHAT = "chat?prefill={prefill}"
    fun chat(prefill: String? = null) = "chat?prefill=${Uri.encode(prefill ?: "")}"
    const val PROGRESS = "progress"
    const val FIX = "fix/{stepId}"
    fun fix(stepId: Long) = "fix/$stepId"
}

// Screen transitions: pushed screens slide in from the side; tabs cross-fade with a slight zoom.
private val pushEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition =
    { slideInHorizontally(tween(320)) { it / 3 } + fadeIn(tween(320)) }
private val pushExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition =
    { slideOutHorizontally(tween(320)) { -it / 6 } + fadeOut(tween(200)) }
private val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition =
    { slideInHorizontally(tween(320)) { -it / 6 } + fadeIn(tween(320)) }
private val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition =
    { slideOutHorizontally(tween(320)) { it / 3 } + fadeOut(tween(200)) }
private val tabEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition =
    { fadeIn(tween(260)) + scaleIn(tween(260), initialScale = 0.97f) }
private val tabExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = { fadeOut(tween(160)) }

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(
        nav, startDestination = Routes.PROFILES,
        enterTransition = pushEnter, exitTransition = pushExit, popEnterTransition = popEnter, popExitTransition = popExit,
    ) {
        composable(Routes.PROFILES, arguments = listOf(navArgument("auto") { type = NavType.BoolType; defaultValue = true })) { e ->
            ProfilesScreen(
                autoEnter = e.arguments?.getBoolean("auto") ?: true,
                onCreate = { nav.navigate(Routes.INTAKE) },
                onEnter = { nav.navigate(Routes.MAIN) { popUpTo(0) { inclusive = true } } },
            )
        }
        composable(Routes.INTAKE) {
            IntakeScreen(
                onBack = { nav.popBackStack() },
                onDone = { nav.navigate(Routes.MAIN) { popUpTo(0) { inclusive = true } } },
            )
        }
        composable(Routes.MAIN) {
            MainScaffold(
                openBenchmark = { nav.navigate(Routes.BENCHMARK) },
                openModels = { nav.navigate(Routes.MODELS) },
                openSettings = { nav.navigate(Routes.SETTINGS) },
                switchProfile = { nav.navigate(Routes.profiles(false)) { popUpTo(0) { inclusive = true } } },
            )
        }
        composable(Routes.BENCHMARK) { BenchmarkScreen(onBack = { nav.popBackStack() }, openModels = { nav.navigate(Routes.MODELS) }) }
        composable(Routes.MODELS) { ModelsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                switchProfile = { nav.navigate(Routes.profiles(false)) { popUpTo(0) { inclusive = true } } },
            )
        }
    }
}

private data class Tab(val route: String, val base: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "home", R.string.tab_home, Icons.Filled.Home),
    Tab(Routes.ROADMAP, "roadmap", R.string.tab_roadmap, Icons.Filled.Route),
    Tab(Routes.QUIZ, "quiz", R.string.tab_quiz, Icons.Filled.Quiz),
    Tab(Routes.CHAT, "chat", R.string.tab_chat, Icons.Filled.Forum),
    Tab(Routes.PROGRESS, "progress", R.string.tab_progress, Icons.Filled.Insights),
)

/** Transparent top bar with a Baloo title so the paper shows through. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperTopBar(title: String, navigationIcon: @Composable () -> Unit = {}, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
    )
}

@Composable
private fun MainScaffold(
    openBenchmark: () -> Unit,
    openModels: () -> Unit,
    openSettings: () -> Unit,
    switchProfile: () -> Unit,
) {
    val inner = rememberNavController()
    val entry by inner.currentBackStackEntryAsState()
    val currentBase = entry?.destination?.route?.substringBefore('?') ?: "home"
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            PaperTopBar(
                title = stringResource(
                    if (currentBase.startsWith("fix")) R.string.mini_lesson
                    else tabs.firstOrNull { it.base == currentBase && it.base != "home" }?.label ?: R.string.app_name,
                ),
                actions = {
                    IconButton(onClick = openBenchmark) { Icon(Icons.Filled.Speed, stringResource(R.string.benchmark_title)) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.models_title)) },
                            leadingIcon = { Icon(Icons.Filled.Memory, null) }, onClick = { menu = false; openModels() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.settings_title)) },
                            leadingIcon = { Icon(Icons.Filled.Settings, null) }, onClick = { menu = false; openSettings() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.switch_profile)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null) }, onClick = { menu = false; switchProfile() })
                    }
                },
            )
        },
        bottomBar = {
            Column {
                Box(Modifier.fillMaxWidth().height(2.dp).background(LocalExtras.current.cardEdge))
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    tabs.forEach { t ->
                        val selected = currentBase == t.base
                        val scale by animateFloatAsState(if (selected) 1.18f else 1f,
                            spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "tab")
                        NavigationBarItem(
                            selected = selected,
                            onClick = { inner.goTab(if (t.base == "quiz") Routes.quiz() else if (t.base == "chat") Routes.chat() else t.route) },
                            icon = { Icon(t.icon, null, Modifier.scale(scale)) },
                            label = { Text(stringResource(t.label), style = MaterialTheme.typography.labelMedium) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(
            inner, startDestination = Routes.HOME, modifier = Modifier.padding(pad),
            enterTransition = tabEnter, exitTransition = tabExit, popEnterTransition = tabEnter, popExitTransition = tabExit,
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    openRoadmap = { inner.goTab(Routes.ROADMAP) },
                    openQuiz = { course -> inner.goTab(Routes.quiz(course)) },
                    openChat = { inner.goTab(Routes.chat()) },
                    openProgress = { inner.goTab(Routes.PROGRESS) },
                    openModels = openModels,
                )
            }
            composable(Routes.ROADMAP) {
                RoadmapScreen(
                    openQuiz = { course, sub -> inner.goTab(Routes.quiz(course, sub)) },
                    openChat = { q -> inner.goTab(Routes.chat(q)) },
                    openModels = openModels,
                    openFix = { id -> inner.navigate(Routes.fix(id)) },
                )
            }
            composable(
                Routes.QUIZ,
                arguments = listOf(
                    navArgument("course") { type = NavType.StringType; defaultValue = "" },
                    navArgument("subtopic") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { e ->
                QuizScreen(
                    initialCourse = e.arguments?.getString("course").orEmpty().ifBlank { null },
                    initialSubtopic = e.arguments?.getString("subtopic").orEmpty().ifBlank { null },
                    openChat = { q -> inner.goTab(Routes.chat(q)) },
                    openModels = openModels,
                    openFix = { id -> inner.navigate(Routes.fix(id)) },
                )
            }
            composable(Routes.FIX, arguments = listOf(navArgument("stepId") { type = NavType.LongType })) { e ->
                FixScreen(stepId = e.arguments?.getLong("stepId") ?: 0L, openModels = openModels)
            }
            composable(Routes.CHAT, arguments = listOf(navArgument("prefill") { type = NavType.StringType; defaultValue = "" })) { e ->
                ChatScreen(prefill = e.arguments?.getString("prefill").orEmpty(), openModels = openModels)
            }
            composable(Routes.PROGRESS) { ProgressScreen(openQuiz = { course, sub -> inner.goTab(Routes.quiz(course, sub)) }) }
        }
    }
}

private fun NavHostController.goTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        // A tab opened with real arguments (e.g. quiz on a subtopic) starts fresh; plain tab taps restore state.
        restoreState = !Regex("=[^&]+").containsMatchIn(route)
    }
}
