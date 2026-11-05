package dev.whale.crazychess

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.whale.crazychess.game.Difficulty
import dev.whale.crazychess.game.GameMode
import dev.whale.crazychess.game.WinCondition
import dev.whale.crazychess.ui.CrazyChessTheme
import dev.whale.crazychess.ui.GameScreen
import dev.whale.crazychess.ui.HomeScreen
import dev.whale.crazychess.ui.newSeed
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            CrazyChessTheme {
                App()
            }
        }
    }
}

private data class Match(
    val mode: GameMode,
    val difficulty: Difficulty,
    val win: WinCondition,
    val seed: Int,
    val bottomMovesFirst: Boolean,
)

@Composable
fun App() {
    var match by remember { mutableStateOf<Match?>(null) }
    val context = LocalContext.current
    LaunchedEffect(Unit) { SoundFx.init(context) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.background,
    ) {
        AnimatedContent(
            targetState = match,
            transitionSpec = {
                (fadeIn(tween(320)) + scaleIn(tween(320), initialScale = 0.94f)) togetherWith
                    fadeOut(tween(180))
            },
            label = "screen",
        ) { m ->
            if (m == null) {
                HomeScreen(
                    onStart = { mode, difficulty, win, bottomFirst ->
                        match = Match(mode, difficulty, win, newSeed(), bottomFirst)
                    },
                )
            } else {
                BackHandler { match = null }
                GameScreen(
                    mode = m.mode,
                    difficulty = m.difficulty,
                    winCondition = m.win,
                    engineSeed = m.seed,
                    bottomMovesFirst = m.bottomMovesFirst,
                    onExit = { match = null },
                )
            }
        }
    }
}
