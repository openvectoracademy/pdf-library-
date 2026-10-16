package com.pdflib

import android.graphics.Color as AColor
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: LibraryVm = viewModel()
            val dark = isDarkFor(vm.themeMode)
            // Status and navigation bar icons must flip with the chosen theme, not only with the phone's.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(AColor.TRANSPARENT, AColor.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(AColor.TRANSPARENT, AColor.TRANSPARENT) { dark }
                )
                onDispose { }
            }
            PdfLibTheme(dark) { Root(vm) }
        }
    }
}

@Composable
private fun Root(vm: LibraryVm = viewModel()) {
    val pal = LocalPal.current
    var showSplash by rememberSaveable { mutableStateOf(true) }
    val pdfs by vm.pdfs.collectAsState()
    val recent = remember(pdfs) { pdfs.filter { it.lastOpened > 0 }.sortedByDescending { it.lastOpened }.take(100) }
    val saved = remember(pdfs) { sortNatural(pdfs.filter { it.favorite }) }

    // From Recent, Saved or Settings, Back goes to the Library before it leaves the app.
    BackHandler(enabled = vm.tab != 0) { vm.tab = 0 }

    Box(Modifier.fillMaxSize().background(pal.bg)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (vm.tab) {
                    0 -> LibraryScreen(vm)
                    1 -> PdfListScreen(vm, "Recent", recent, "Nothing opened yet", "PDFs you read will show up here, ready to pick up where you left off.")
                    2 -> PdfListScreen(vm, "Saved", saved, "No favourites yet", "Tap the star on any PDF to keep it close for exam week.")
                    else -> SettingsScreen(vm)
                }
            }
            BottomBar(vm.tab) { vm.tab = it }
        }
        if (vm.searching) SearchScreen(vm)
        vm.readerId?.let { ReaderScreen(vm, it) }
        PdfDialogHost(vm)
        vm.undo?.let { UndoBar(vm, it, Modifier.align(Alignment.BottomCenter)) }
        if (showSplash) Splash(ready = vm.loaded) { showSplash = false }
    }
}

@Composable
private fun UndoBar(vm: LibraryVm, note: UndoNote, modifier: Modifier) {
    val pal = LocalPal.current
    LaunchedEffect(note) { delay(5000); if (vm.undo === note) vm.undo = null }
    Row(
        modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 72.dp).fillMaxWidth()
            .clip(RoundedCornerShape(14.dp)).background(pal.ink).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(note.message, Modifier.weight(1f).padding(vertical = 14.dp), color = pal.bg, fontSize = 14.sp)
        TextButton(onClick = { val act = note.action; vm.undo = null; act() }) {
            Text("Undo", color = pal.bg, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val pal = LocalPal.current
    val items = listOf<Pair<String, ImageVector>>(
        "Library" to Icons.Rounded.Book, "Recent" to Icons.Rounded.History,
        "Saved" to Icons.Rounded.Star, "Settings" to Icons.Rounded.Settings
    )
    Column(Modifier.background(pal.surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(60.dp)) {
            items.forEachIndexed { i, (label, icon) ->
                val on = i == selected
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                ) {
                    Icon(icon, label, tint = if (on) pal.accent else pal.soft, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(label, fontSize = 11.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) pal.ink else pal.soft)
                }
            }
        }
    }
}
