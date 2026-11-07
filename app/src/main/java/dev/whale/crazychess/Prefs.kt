package dev.whale.crazychess

import android.content.Context
import dev.whale.crazychess.i18n.I18n
import dev.whale.crazychess.i18n.Lang
import java.util.Locale

/**
 * 一点本地设置。只存语言：第一次启动跟随系统语言，之后记住用户手动切过的选择。
 *
 * 桌面版不用这个文件（那边用 registry / 配置文件），所以这里可以直接用 Android API。
 */
object Prefs {
    private const val FILE = "crazychess"
    private const val KEY_LANG = "lang"

    private var appContext: Context? = null

    /** 读一次语言并装好"切了就存"的监听。重复调用无害。 */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val saved = appContext
            ?.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            ?.getString(KEY_LANG, null)
        I18n.setLang(
            if (saved != null) Lang.fromCode(saved) else Lang.fromLocale(Locale.getDefault().toLanguageTag()),
        )
        I18n.addListener { saveLang(I18n.lang) }
    }

    private fun saveLang(lang: Lang) {
        appContext
            ?.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_LANG, lang.code)
            ?.apply()
    }
}
