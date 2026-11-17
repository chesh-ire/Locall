package com.example.locall

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.locall.ui.assistant.ConversationScreen
import com.example.locall.ui.assistant.SetupSheetContent
import com.example.locall.ui.components.FlaskIcon
import com.example.locall.ui.theme.LocaLLTheme
import com.example.locall.voice.agent.follow.FollowUpManager
import com.example.locall.voice.assistant.AssistantViewModel
import com.example.locall.voice.assistant.KnowledgeImport
import com.example.locall.voice.research.bench.ui.PerformanceLabScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var vm: AssistantViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = AssistantViewModel.get(applicationContext)
        vm.init()
        lifecycleScope.launch { runCatching { FollowUpManager.rescheduleAll(applicationContext) } }
        enableEdgeToEdge()
        setContent {
            LocaLLTheme {
                AppRoot(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.init()
    }
}

@Composable
private fun AppRoot(vm: AssistantViewModel) {
    val context = LocalContext.current
    var showPerfLab by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Ask for notification-post permission once (Android 13+), as the old screen did.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Storage Access Framework picker for the attach(+) → document import flow.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { KnowledgeImport.importFile(context, vm, it) } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.widthIn(max = 348.dp),
            ) {
                SetupSheetContent()
            }
        },
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
        ) { pad ->
            if (showPerfLab) {
                PerfLabHost(onBack = { showPerfLab = false }, modifier = Modifier.padding(pad))
            } else {
                ConversationScreen(
                    vm = vm,
                    modifier = Modifier.padding(pad),
                    onOpenMenu = { scope.launch { drawerState.open() } },
                    onOpenPerfLab = { showPerfLab = true },
                    onAttachDocument = {
                        importLauncher.launch(arrayOf("text/plain", "text/markdown", "text/*"))
                    },
                )
            }
        }
    }
}

@Composable
private fun PerfLabHost(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Text("←", fontSize = 22.sp) }
            FlaskIcon(Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text("Performance Lab", style = MaterialTheme.typography.titleMedium)
        }
        PerformanceLabScreen(modifier = Modifier.weight(1f))
    }
}
