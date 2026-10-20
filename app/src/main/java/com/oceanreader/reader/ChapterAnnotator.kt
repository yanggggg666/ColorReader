package com.oceanreader.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.oceanreader.data.Settings
import com.oceanreader.data.model.FontFamilyOption

/**
 * Builds the Compose [AnnotatedString] for a chapter from its typed blocks.
 *
 * Used by the continuous-scroll reading mode and by the live preview inside
 * the typesetting panel, so both show exactly what the reader will show.
 */
object ChapterAnnotator {

    /** Body text size in sp, as resolved from user settings. */
    fun fontSizeSp(prefs: Settings): Int = prefs.fontSize.coerceIn(12, 32)

    /** Font family for the body text, matching the StaticLayout renderer. */
    fun fontFamilyOf(option: FontFamilyOption): FontFamily = when (option) {
        FontFamilyOption.SANS -> FontFamily.SansSerif
        FontFamilyOption.SERIF -> FontFamily.Serif
        FontFamilyOption.ROUNDED -> FontFamily.Default
        FontFamilyOption.MONO -> FontFamily.Monospace
    }

    /** Uniform paragraph style: line height and first-line indent. */
    fun paragraphStyleFor(style: ReaderStyle, prefs: Settings) = androidx.compose.ui.text.ParagraphStyle(
        lineHeight = lineHeightSp(style, prefs),
        textIndent = TextIndent(
            firstLine = indentSp(prefs),
            restLine = 0.sp
        )
    )

    fun lineHeightSp(style: ReaderStyle, prefs: Settings): TextUnit {
        // Keep the same floor the StaticLayout path uses.
        val multiplier = style.lineSpacing
        return (fontSizeSp(prefs) * multiplier).sp
    }

    fun indentSp(prefs: Settings): TextUnit =
        (prefs.fontSize * prefs.firstLineIndent).sp

    /** Styled rendering of a whole chapter; [blocks] from [ReaderTypesetter.blocks]. */
    fun annotate(
        text: String,
        blocks: List<TypedBlock>,
        style: ReaderStyle,
        prefs: Settings
    ): AnnotatedString {
        if (text.isEmpty()) return AnnotatedString("")
        val baseSize = fontSizeSp(prefs).sp
        val monoFamily = FontFamily.Monospace
        return buildAnnotatedString {
            append(text)
            for (block in blocks) {
                when (block.kind) {
                    BlockKind.TITLE -> addStyle(
                        SpanStyle(
                            fontSize = baseSize * style.titleScale,
                            fontWeight = FontWeight.Bold
                        ),
                        block.start,
                        block.end
                    )

                    BlockKind.HEADING -> addStyle(
                        SpanStyle(
                            fontSize = baseSize * style.headingScale,
                            fontWeight = FontWeight.Bold
                        ),
                        block.start,
                        block.end
                    )

                    BlockKind.PARAGRAPH -> Unit // paragraph style handles it

                    BlockKind.CODE -> addStyle(
                        SpanStyle(
                            fontFamily = monoFamily,
                            background = androidx.compose.ui.graphics.Color(
                                style.codeBackground
                            )
                        ),
                        block.start,
                        block.end
                    )
                }
            }
        }
    }
}
