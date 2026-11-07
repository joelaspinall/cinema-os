package com.joelaspinall.cinemaos.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.joelaspinall.cinemaos.data.Film
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CinemaTheme { Root(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.checkPending()
    }
}

sealed interface Screen {
    data object Main : Screen
    data class Detail(val film: Film) : Screen
    data class Player(val film: Film) : Screen
}

@Composable
fun Root(vm: AppViewModel) {
    val data by vm.c.store.data.collectAsState()
    val marks = remember(data) { Marks(data) }
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Main)) }
    var tab by remember { mutableStateOf("home") }
    fun push(s: Screen) { stack = stack + s }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }

    CompositionLocalProvider(LocalMarks provides marks) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            val finish = vm.finish
            val rate = vm.rate
            when {
                // Prompts take over the screen so the remote can't wander off them.
                finish != null -> FinishDialog(vm, finish)
                rate != null -> RateDialog(vm, rate)
                else -> when (val top = stack.last()) {
                    Screen.Main -> MainScreen(vm, tab, onTab = { tab = it }, open = { push(Screen.Detail(it)) })
                    is Screen.Detail -> DetailScreen(
                        vm, top.film,
                        open = { push(Screen.Detail(it)) },
                        play = { push(Screen.Player(it)) },
                    )
                    is Screen.Player -> PlayerScreen(vm, top.film, onExit = { pop() })
                }
            }

            BackHandler(enabled = finish != null) { vm.answerFinish(finished = false, keepAsking = true) }
            BackHandler(enabled = finish == null && rate != null) { vm.rate = null }
            BackHandler(enabled = finish == null && rate == null && stack.size > 1 && stack.last() !is Screen.Player) { pop() }
            BackHandler(enabled = finish == null && rate == null && stack.size == 1 && tab != "home") { tab = "home" }

            val toast = vm.toast
            if (toast != null) {
                LaunchedEffect(toast) { delay(5500); vm.toast = null }
                Text(
                    toast, style = Type.body,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp)
                        .clip(RoundedCornerShape(12.dp)).background(PanelHi)
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
        }
    }
}
