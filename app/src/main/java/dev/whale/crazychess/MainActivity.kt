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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.whale.crazychess.game.Difficulty
import dev.whale.crazychess.game.GameMode
import dev.whale.crazychess.game.Side
import dev.whale.crazychess.game.WinCondition
import dev.whale.crazychess.net.LanMeta
import dev.whale.crazychess.net.LanSession
import dev.whale.crazychess.ui.CrazyChessTheme
import dev.whale.crazychess.ui.GameScreen
import dev.whale.crazychess.ui.HomeScreen
import dev.whale.crazychess.ui.newSeed

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
    /** 局域网对局才有；null 表示单机。 */
    val lan: LanSession? = null,
    val mySide: Side = Side.BOTTOM,
    val myName: String = "你",
    val peerName: String = "对手",
)

@Composable
fun App() {
    var match by remember { mutableStateOf<Match?>(null) }
    val context = LocalContext.current
    LaunchedEffect(Unit) { SoundFx.init(context) }

    // 退出对局（不管哪种模式）都要把局域网连接放掉，否则对端会一直等着

    Surface(
        modifier = Modifier.fillMaxSize(),
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
                    onLanStart = { session, mySide, meta, myName, peerName ->
                        match = Match(
                            mode = GameMode.LAN,
                            difficulty = Difficulty.NORMAL,
                            win = if (meta.killKing) WinCondition.KILL_KING else WinCondition.KILL_EM_ALL,
                            seed = newSeed(),
                            bottomMovesFirst = meta.bottomFirst,
                            lan = session,
                            mySide = mySide,
                            myName = myName,
                            peerName = peerName,
                        )
                    },
                )
            } else {
                val exit = {
                    m.lan?.close()
                    match = null
                }
                BackHandler { exit() }
                GameScreen(
                    mode = m.mode,
                    difficulty = m.difficulty,
                    winCondition = m.win,
                    engineSeed = m.seed,
                    bottomMovesFirst = m.bottomMovesFirst,
                    lan = m.lan,
                    mySide = m.mySide,
                    myName = m.myName,
                    peerName = m.peerName,
                    onExit = exit,
                )
            }
        }
    }
}
