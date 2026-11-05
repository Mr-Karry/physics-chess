package dev.whale.crazychess

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * 音效：沿用原版 CrazyChess 的三段素材。
 *
 * - `whoosh` 甩子（玩家和 AI 出手都会响，AI 不放声音的话对局会显得"死"）
 * - `plum4`  决出胜负的那一枚棋子掉出棋盘时响（原版是王掉出去才有的响动）
 * - `crowd`  终局欢呼，连续播几次
 *
 * 用 SoundPool 而不是 MediaPlayer，因为一局里会有密集的短促音效。
 */
object SoundFx {
    private var pool: SoundPool? = null
    private var shotId = 0
    private var hitId = 0
    private var winId = 0

    var enabled: Boolean = true

    fun init(context: Context) {
        if (pool != null) return
        val app = context.applicationContext
        val p = SoundPool.Builder()
            .setMaxStreams(6)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        shotId = p.load(app, R.raw.whoosh, 1)
        hitId = p.load(app, R.raw.plum4, 1)
        winId = p.load(app, R.raw.crowd, 1)
        pool = p
    }

    /** 甩子。`rate` 让玩家与 AI 的出手听起来略有差别。 */
    fun shot(byTop: Boolean = false) = play(shotId, 0.85f, if (byTop) 0.96f else 1.05f)

    /** 决定胜负的一枚棋子掉出棋盘。 */
    fun decide() = play(hitId, 0.85f, 0.92f)

    /** 单次欢呼。连续播放由调用方按节奏驱动。 */
    fun cheer() = play(winId, 0.9f, 1f)

    private fun play(id: Int, volume: Float, rate: Float) {
        if (!enabled || id == 0) return
        pool?.play(id, volume, volume, 1, 0, rate)
    }
}
