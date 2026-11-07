package dev.whale.crazychess.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import dev.whale.crazychess.i18n.I18n
import dev.whale.crazychess.i18n.Lang

/**
 * 语言切换的 Compose 侧状态。
 *
 * [I18n] 是纯 Kotlin 的（桌面版也用），所以这里要拿一个 Compose 快照状态当"扳机"：
 * 任何读了这个状态的 Composable 都会在切语言时重组。
 */
private var langTick by mutableStateOf(I18n.lang)

private var listenerInstalled = false

private fun ensureListener() {
    if (listenerInstalled) return
    listenerInstalled = true
    I18n.addListener { langTick = I18n.lang }
}

/** 让调用方也能读当前语言（切语言后自动重组）。 */
@Composable
fun currentLang(): Lang {
    ensureListener()
    return langTick
}

/**
 * 与 `androidx.compose.material3.Text` 同签名的包装：把中文原文翻成当前语言再画。
 *
 * 两个大文件里只要把 `import androidx.compose.material3.Text` 删掉，
 * 同包的这个名字就会接管全部调用 —— 几十处文案一行都不用改。
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    ensureListener()
    // 读一下 langTick，切语言时本 Composable 才会重组
    val lang = langTick
    val shown = remember(lang, text) { I18n.translate(text, lang) }
    MaterialText(
        text = shown,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        onTextLayout = onTextLayout,
        style = style,
    )
}
