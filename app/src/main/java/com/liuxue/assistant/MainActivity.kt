package com.liuxue.assistant

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.liuxue.assistant.feature.about.WelcomeDialog
import com.liuxue.assistant.feature.about.ensureWelcomeMemo
import com.liuxue.assistant.feature.about.hasSeenWelcome
import com.liuxue.assistant.feature.about.markWelcomeSeen
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.liuxue.assistant.feature.dict.DictScreen
import com.liuxue.assistant.feature.dict.QuizScreen
import com.liuxue.assistant.feature.dict.PhraseScreen
import com.liuxue.assistant.feature.dict.WordbookScreen
import com.liuxue.assistant.feature.files.EmergencyScreen
import com.liuxue.assistant.feature.files.FileDetailScreen
import com.liuxue.assistant.feature.files.FileEditScreen
import com.liuxue.assistant.feature.files.FileListScreen
import com.liuxue.assistant.feature.memo.MemoScreen
import com.liuxue.assistant.feature.transfer.TransferScreen
import com.liuxue.assistant.feature.net.NetScreen
import com.liuxue.assistant.feature.study.HomeworkShare
import com.liuxue.assistant.feature.study.StudyScreen
import com.liuxue.assistant.feature.translate.TranslateScreen
import com.liuxue.assistant.ui.theme.StudyAssistantTheme

private const val ROUTE_FILES = "files"
private const val ROUTE_DICT = "dict"
private const val ROUTE_STUDY = "study"
private const val ROUTE_NET = "net"
private const val ROUTE_MEMO = "memo"

private data class Destination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector
)

private val destinations = listOf(
    Destination(ROUTE_FILES, R.string.nav_files, Icons.Filled.Home),
    Destination(ROUTE_DICT, R.string.nav_dict, Icons.Filled.Favorite),
    Destination(ROUTE_STUDY, R.string.nav_study, Icons.Filled.Person),
    Destination(ROUTE_NET, R.string.nav_net, Icons.Filled.Search),
    Destination(ROUTE_MEMO, R.string.nav_memo, Icons.Filled.Edit)
)

class MainActivity : ComponentActivity() {

    /** 处理"作业分享回流"：liuxue://homework?d=xxx */
    private fun handleShareIntent(intent: android.content.Intent?) {
        val data = intent?.dataString ?: return
        if (data.startsWith(HomeworkShare.DEEP_LINK_PREFIX)) {
            HomeworkShare.Pending.text = data
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)
        enableEdgeToEdge()
        setContent {
            StudyAssistantTheme {
                AssistantAppShell()
            }
        }
    }
}

@Composable
private fun AssistantAppShell() {
    // Android 13+ 必须显式申请通知权限，否则到期提醒无法弹出
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* 用户拒绝就静默降级：提醒仍写入应用内，只是不弹通知 */ }
        LaunchedEffect(Unit) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 首次启动：自动弹出「感谢与教程」；同时把它写进备忘录（只做一次）
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showWelcome by remember { mutableStateOf(!hasSeenWelcome(ctx)) }
    LaunchedEffect(Unit) {
        scope.launch { ensureWelcomeMemo(ctx) }
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in destinations.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    destinations.forEach { dest ->
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
                            onClick = {
                                if (currentRoute != dest.route) {
                                    navController.navigate(dest.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = null) },
                            label = { Text(stringResource(dest.labelRes)) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = ROUTE_FILES,
                modifier = Modifier.fillMaxSize()
            ) {
                composable(ROUTE_FILES) {
                    FileListScreen(
                        onOpen = { id -> navController.navigate("file_detail/" + id) },
                        onCreate = { navController.navigate("file_edit/0") },
                        onOpenEmergency = { navController.navigate("emergency") },
                        onOpenTransfer = { navController.navigate("transfer") }
                    )
                }
                composable(
                    route = "file_detail/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType })
                ) { entry ->
                    val id = entry.arguments?.getLong("id") ?: 0L
                    FileDetailScreen(
                        fileId = id,
                        onBack = { navController.popBackStack() },
                        onEdit = { navController.navigate("file_edit/" + it) }
                    )
                }
                composable(
                    route = "file_edit/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType })
                ) { entry ->
                    val id = entry.arguments?.getLong("id") ?: 0L
                    FileEditScreen(
                        fileId = id,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(ROUTE_MEMO) { MemoScreen() }
                composable("emergency") {
                    EmergencyScreen(onBack = { navController.popBackStack() })
                }
                composable("transfer") {
                    TransferScreen(onBack = { navController.popBackStack() })
                }
                composable("translate") {
                    TranslateScreen()
                }
                composable("quiz") {
                    QuizScreen(onBack = { navController.popBackStack() })
                }
                composable(ROUTE_DICT) {
                    DictScreen(
                        onOpenWordbook = { navController.navigate("wordbook") },
                        onOpenPhrases = { navController.navigate("phrases") },
                        onOpenTransfer = { navController.navigate("transfer") },
                        onOpenTranslate = { navController.navigate("translate") },
                        onOpenQuiz = { navController.navigate("quiz") }
                    )
                }
                composable("wordbook") {
                    WordbookScreen(onBack = { navController.popBackStack() })
                }
                composable("phrases") {
                    PhraseScreen(onBack = { navController.popBackStack() })
                }
                composable(ROUTE_STUDY) { StudyScreen() }
                composable(ROUTE_NET) { NetScreen() }
            }
        }
    }

    if (showWelcome) {
        WelcomeDialog(onClose = {
            markWelcomeSeen(ctx)
            showWelcome = false
        })
    }
}
