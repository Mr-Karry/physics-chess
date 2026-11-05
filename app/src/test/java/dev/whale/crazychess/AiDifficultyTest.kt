package dev.whale.crazychess

import dev.whale.crazychess.game.Difficulty
import dev.whale.crazychess.game.GameEngine
import dev.whale.crazychess.game.GameMode
import dev.whale.crazychess.game.Rules
import dev.whale.crazychess.game.Side
import dev.whale.crazychess.game.WinCondition
import org.junit.Test

/**
 * 量一量「AI 强度」到底有没有区别。
 *
 * 做法：让 AI（顶方）连续出手，底方每回合只做一次最小力度的"过场"，然后统计
 * 每个难度下 AI 的出手能打掉多少底方棋子。这是纯 Kotlin 逻辑，不需要真机。
 */
class AiDifficultyTest {

    private data class Result(
        val label: String,
        val shots: Int,
        val kills: Int,
        val selfKills: Int,
        val games: Int,
        val wins: Int,
    )

    private fun playGame(difficulty: Difficulty, seed: Int, maxShots: Int = 30): Result {
        val engine = GameEngine(GameMode.VS_ANDROID, WinCondition.KILL_EM_ALL, difficulty, seed)
        var shots = 0
        var kills = 0
        var selfKills = 0
        var guard = 0

        while (!engine.isOver && shots < maxShots && guard++ < 4000) {
            // 底方"过场"：最小力度推一下，把回合交给顶方
            val mine = engine.piecesOf(Side.BOTTOM)
            if (mine.isEmpty()) break
            engine.shoot(mine[guard % mine.size], 0.01f, Rules.MIN_DRAG)

            val beforeTop = engine.totalAlive(Side.TOP)
            val beforeBottom = engine.totalAlive(Side.BOTTOM)
            engine.aiShoot()
            shots++

            // 跑到静止
            var t = 0L
            var spins = 0
            while (!engine.isSettled() && spins++ < 600) {
                engine.tick(1f / 60f, t)
                t += 16
            }

            val afterTop = engine.totalAlive(Side.TOP)
            val afterBottom = engine.totalAlive(Side.BOTTOM)
            if (afterBottom < beforeBottom) kills += beforeBottom - afterBottom
            if (afterTop < beforeTop) selfKills += beforeTop - afterTop
        }

        return Result(difficulty.label, shots, kills, selfKills, 1, if (engine.winner == Side.TOP) 1 else 0)
    }

    @Test
    fun difficultyChangesOutcome() {
        val seeds = 40
        val results = Difficulty.values().map { d ->
            var shots = 0
            var kills = 0
            var selfKills = 0
            var wins = 0
            repeat(seeds) { s ->
                val r = playGame(d, seed = 1000 + s)
                shots += r.shots
                kills += r.kills
                selfKills += r.selfKills
                wins += r.wins
            }
            Result(d.label, shots, kills, selfKills, seeds, wins)
        }

        println("=== AI 强度实测（${seeds} 局 × 最多 30 手）===")
        results.forEach { r ->
            val killRate = if (r.shots > 0) r.kills * 100.0 / r.shots else 0.0
            val selfRate = if (r.shots > 0) r.selfKills * 100.0 / r.shots else 0.0
            println(
                "%s: 出手 %d 次，吃子 %d（%.1f%% / 手），自己掉 %d（%.1f%% / 手），完胜 %d/%d 局"
                    .format(r.label, r.shots, r.kills, killRate, r.selfKills, selfRate, r.wins, r.games),
            )
        }
    }
}
