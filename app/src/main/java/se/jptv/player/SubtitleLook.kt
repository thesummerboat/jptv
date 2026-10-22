package se.jptv.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.accessibility.CaptioningManager
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

/**
 * Hur textundertexter (SRT, ASS, WebVTT) ritas. Bildundertexter (PGS, VobSub) är färdiga bilder
 * och påverkas inte. Standardvärdena ger samma utseende som tidigare (TV:ns egna textinställningar).
 */
@OptIn(UnstableApi::class)
data class SubtitleLook(
    /** Index i [SIZES]. */
    val size: Int = 1,
    /** Index i [STYLES]. */
    val style: Int = 0,
    /** Index i [COLORS]. */
    val color: Int = 0,
    /** Index i [POSITIONS]. */
    val position: Int = 1,
) {
    val sizeLabel: String get() = SIZES[size.coerceIn(0, SIZES.lastIndex)].first
    val styleLabel: String get() = STYLES[style.coerceIn(0, STYLES.lastIndex)]
    val colorLabel: String get() = COLORS[color.coerceIn(0, COLORS.lastIndex)].first
    val positionLabel: String get() = POSITIONS[position.coerceIn(0, POSITIONS.lastIndex)].first

    fun nextSize() = copy(size = (size + 1) % SIZES.size)
    fun nextStyle() = copy(style = (style + 1) % STYLES.size)
    fun nextColor() = copy(color = (color + 1) % COLORS.size)
    fun nextPosition() = copy(position = (position + 1) % POSITIONS.size)

    val isDefault: Boolean get() = this == SubtitleLook()

    /** Textstorlek som andel av bildhöjden, eller null för TV:ns egen storlek. */
    val sizeFraction: Float?
        get() = SIZES[size.coerceIn(0, SIZES.lastIndex)].second.takeIf { it != 1f }
            ?.let { SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * it }

    val bottomPadding: Float get() = POSITIONS[position.coerceIn(0, POSITIONS.lastIndex)].second

    fun captionStyle(context: Context): CaptionStyleCompat? {
        val fg = COLORS[color.coerceIn(0, COLORS.lastIndex)].second
        return when (style) {
            1 -> CaptionStyleCompat(fg, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, MEDIUM)
            2 -> CaptionStyleCompat(fg, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.BLACK, MEDIUM)
            3 -> CaptionStyleCompat(fg, 0x99000000.toInt(), Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_NONE, Color.BLACK, MEDIUM)
            4 -> CaptionStyleCompat(fg, Color.BLACK, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_NONE, Color.BLACK, MEDIUM)
            else -> {
                // TV:ns egna inställning, men med vald färg om den inte är vit.
                if (color == 0) return null
                val manager = context.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
                val base = manager?.let { CaptionStyleCompat.createFromCaptionStyle(it.userStyle) } ?: CaptionStyleCompat.DEFAULT
                CaptionStyleCompat(fg, base.backgroundColor, base.windowColor, base.edgeType, base.edgeColor, base.typeface)
            }
        }
    }

    /** Ställer in en SubtitleView. Gör inget om samma utseende redan är satt. */
    fun applyTo(view: SubtitleView) {
        if (view.tag == this) return
        view.tag = this
        val fraction = sizeFraction
        if (fraction == null) view.setUserDefaultTextSize() else view.setFractionalTextSize(fraction)
        // Egen storlek gäller även undertexter som anger sin egen (ASS).
        view.setApplyEmbeddedFontSizes(fraction == null)
        val caption = captionStyle(view.context)
        if (caption == null) view.setUserDefaultStyle() else view.setStyle(caption)
        view.setBottomPaddingFraction(bottomPadding)
    }

    companion object {
        val SIZES = listOf("Liten" to 0.8f, "Normal" to 1f, "Stor" to 1.25f, "Större" to 1.5f, "Störst" to 1.8f)
        val STYLES = listOf("TV:ns standard", "Kantlinje", "Skugga", "Halvgenomskinlig ruta", "Svart ruta")
        val COLORS = listOf("Vit" to Color.WHITE, "Gul" to 0xFFFFE14D.toInt(), "Ljusgrå" to 0xFFD8D8D8.toInt())
        val POSITIONS = listOf("Lägre" to 0.04f, "Normal" to 0.08f, "Högre" to 0.15f, "Högst" to 0.24f)

        private val MEDIUM: Typeface by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    }
}
