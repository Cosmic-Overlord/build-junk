package io.github.cosmicoverlord.boardingpass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.cosmicoverlord.boardingpass.core.model.DecodedCode
import io.github.cosmicoverlord.boardingpass.ui.barcode.FullscreenBarcodeScreen
import io.github.cosmicoverlord.boardingpass.ui.detail.PassDetailScreen
import io.github.cosmicoverlord.boardingpass.ui.edit.EditPassScreen
import io.github.cosmicoverlord.boardingpass.ui.list.PassListScreen
import io.github.cosmicoverlord.boardingpass.ui.scan.ScannerScreen
import io.github.cosmicoverlord.boardingpass.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

private object Routes {
    const val LIST = "list"
    const val SCAN = "scan"
    const val SETTINGS = "settings"
    const val PASS = "pass/{id}"
    const val CODE = "code/{id}"
    const val EDIT = "edit/{id}"
    fun pass(id: String) = "pass/$id"
    fun code(id: String) = "code/$id"
    fun edit(id: String) = "edit/$id"
}

@Composable
fun AppRoot(vm: AppViewModel) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val importing by vm.importing.collectAsStateWithLifecycle()
    var askUnparsed by remember { mutableStateOf<DecodedCode?>(null) }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is UiEvent.OpenPass -> nav.openTop(Routes.pass(e.id))
                is UiEvent.OpenEdit -> nav.openTop(Routes.edit(e.id))
                is UiEvent.AskSaveUnparsed -> askUnparsed = e.code
                is UiEvent.Message -> scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(e.text)
                }
            }
        }
    }

    NavHost(navController = nav, startDestination = Routes.LIST) {
        composable(Routes.LIST) {
            PassListScreen(
                vm = vm,
                snackbar = snackbar,
                onOpen = { nav.navigate(Routes.pass(it)) },
                onShowCode = { nav.navigate(Routes.code(it)) },
                onScan = { nav.navigate(Routes.SCAN) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SCAN) {
            ScannerScreen(
                onBack = { nav.popBackStack() },
                onBoardingPass = vm::importScanned,
                onOtherCode = { askUnparsed = it },
                onPickImage = vm::importUri,
            )
        }
        composable(Routes.PASS) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            PassDetailScreen(
                vm = vm,
                passId = id,
                snackbar = snackbar,
                onBack = { nav.popBackStack() },
                onShowCode = { nav.navigate(Routes.code(id)) },
                onEdit = { nav.navigate(Routes.edit(id)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.CODE) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val passes by vm.passes.collectAsStateWithLifecycle()
            val settings by vm.settings.collectAsStateWithLifecycle()
            val pass = passes.firstOrNull { it.id == id }
            if (pass == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
            } else {
                FullscreenBarcodeScreen(pass, settings.edgeMarginMm, onClose = { nav.popBackStack() })
            }
        }
        composable(Routes.EDIT) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            EditPassScreen(
                vm = vm,
                passId = id,
                onClose = { nav.popBackStack() },
                onSaved = {
                    if (nav.previousBackStackEntry?.destination?.route == Routes.PASS) {
                        nav.popBackStack()
                    } else {
                        nav.openTop(Routes.pass(id))
                    }
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(vm = vm, onBack = { nav.popBackStack() })
        }
    }

    if (importing) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Ищу штрихкод…", color = Color.White, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    askUnparsed?.let { code ->
        AlertDialog(
            onDismissRequest = { askUnparsed = null },
            title = { Text("Это не посадочный IATA") },
            text = {
                Column {
                    Text(
                        "Код прочитан, но в нём нет стандартных данных посадочного талона. " +
                            "Можно сохранить его как есть (он будет показываться на весь экран) и заполнить рейс вручную.",
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(code.raw, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    askUnparsed = null
                    vm.saveUnparsed(code)
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { askUnparsed = null }) { Text("Отмена") } },
        )
    }
}

/** Opens [route] directly above the list, dropping scanner/edit screens from the back stack. */
private fun NavHostController.openTop(route: String) {
    navigate(route) {
        popUpTo(Routes.LIST)
        launchSingleTop = true
    }
}
