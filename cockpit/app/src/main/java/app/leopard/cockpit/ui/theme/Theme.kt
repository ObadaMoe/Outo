package app.leopard.cockpit.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.leopard.cockpit.R
import app.leopard.cockpit.data.Accent

/** Colors from the Otility design (Glacier, Dune and Jade accents; dark and light). */
@Immutable
data class Tokens(
    val dark: Boolean,
    val text: Color,
    val text2: Color,
    val glassTint: Color,
    val card: Color,
    val sel: Color,
    val sep: Color,
    val track: Color,
    val inset: Color,
    val menu: Color,
    val background: Color,
    val solidGlass: Color,
    val acc: Color,
    val accText: Color,
    val fill: Color,
    val onFill: Color,
    val accHi: Color,
    val accLo: Color,
    val reduceTransparency: Boolean,
    val tinted: Boolean,
) {
    val good = Color(0xFF30D158)
    val check = Color(0xFFFFD60A)
    val alert = Color(0xFFFF453A)
    val destructive = if (dark) Color(0xFFFF453A) else Color(0xFFFF3B30)
}

fun tokens(dark: Boolean, accent: Accent, reduceTransparency: Boolean = false, tinted: Boolean = false): Tokens {
    data class A(val acc: Long, val accText: Long, val fill: Long, val onFill: Long, val hi: Long, val lo: Long)
    val a = when (accent) {
        Accent.GLACIER -> if (dark) A(0xFF0A84FF, 0xFF0A84FF, 0xFF0071E3, 0xFFFFFFFF, 0xFF7CC0FF, 0xFF0A6CFF)
        else A(0xFF007AFF, 0xFF0062CC, 0xFF0071E3, 0xFFFFFFFF, 0xFF7CC0FF, 0xFF0A6CFF)
        Accent.DUNE -> if (dark) A(0xFFFF9F0A, 0xFFFF9F0A, 0xFFFF9F0A, 0xFF1F1404, 0xFFFFC56B, 0xFFF07F00)
        else A(0xFFFF9500, 0xFFA34F00, 0xFFFF9F0A, 0xFF1F1404, 0xFFFFC56B, 0xFFF07F00)
        Accent.JADE -> if (dark) A(0xFF63E6E2, 0xFF63E6E2, 0xFF00C7BE, 0xFF00201E, 0xFF9BF5EA, 0xFF00A89E)
        else A(0xFF00C7BE, 0xFF00796F, 0xFF00C7BE, 0xFF00201E, 0xFF9BF5EA, 0xFF00A89E)
    }
    return if (dark) Tokens(
        dark = true,
        text = Color(0xFFF5F5F7), text2 = Color(0xFFB4B4BB),
        // CarPlay's dark glass: a cool charcoal over about two thirds of the blurred
        // background (measured on its sidebar), more for Tinted and for menus.
        glassTint = if (tinted) Color(0xD11C2028) else Color(0xAD1C2028),
        card = Color(0xAD1C2028), sel = Color(0x29FFFFFF),
        sep = Color(0x1FFFFFFF), track = Color(0x24FFFFFF), inset = Color(0x12FFFFFF),
        menu = Color(0xC720232A), background = Color(0xFF0A0B0D), solidGlass = Color(0xFF1E1F24),
        acc = Color(a.acc), accText = Color(a.accText), fill = Color(a.fill), onFill = Color(a.onFill),
        accHi = Color(a.hi), accLo = Color(a.lo),
        reduceTransparency = reduceTransparency, tinted = tinted,
    ) else Tokens(
        dark = false,
        text = Color(0xFF1D1D1F), text2 = Color(0xFF55555A),
        glassTint = if (tinted) Color(0xD9F6F7F9) else Color(0xB8F6F7F9),
        card = Color(0xB8F6F7F9), sel = Color(0xFFFFFFFF),
        sep = Color(0x14000000), track = Color(0x1A000000), inset = Color(0x99FFFFFF),
        menu = Color(0xD6FAFAFB), background = Color(0xFFEEF0F3), solidGlass = Color(0xFFF6F6F8),
        acc = Color(a.acc), accText = Color(a.accText), fill = Color(a.fill), onFill = Color(a.onFill),
        accHi = Color(a.hi), accLo = Color(a.lo),
        reduceTransparency = reduceTransparency, tinted = tinted,
    )
}

val LocalTokens = staticCompositionLocalOf { tokens(true, Accent.GLACIER) }

/** Inter, the design's fallback for SF Pro (SF Pro itself is only licensed for Apple devices). */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Inter = FontFamily(
    listOf(400, 500, 600, 700).map { w ->
        Font(R.font.inter, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

object T {
    val largeTitle = TextStyle(fontFamily = Inter, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em, lineHeight = 40.sp)
    val title1 = TextStyle(fontFamily = Inter, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em)
    val title2 = TextStyle(fontFamily = Inter, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em)
    val headline = TextStyle(fontFamily = Inter, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontFamily = Inter, fontSize = 17.sp, fontWeight = FontWeight.Normal)
    val callout = TextStyle(fontFamily = Inter, fontSize = 16.sp, fontWeight = FontWeight.Normal)
    val subhead = TextStyle(fontFamily = Inter, fontSize = 15.sp, fontWeight = FontWeight.Normal)
    val footnote = TextStyle(fontFamily = Inter, fontSize = 13.sp, fontWeight = FontWeight.Normal)
    val caption = TextStyle(fontFamily = Inter, fontSize = 12.sp, fontWeight = FontWeight.Normal)
    val sectionHeader = TextStyle(fontFamily = Inter, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.04.em)
    val numberXL = TextStyle(fontFamily = Inter, fontSize = 52.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.03).em, fontFeatureSettings = "tnum")
    val numberL = TextStyle(fontFamily = Inter, fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em, fontFeatureSettings = "tnum")
    val numberM = TextStyle(fontFamily = Inter, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val centered = TextStyle(textAlign = TextAlign.Center)
}

@Composable
fun OtilityTheme(tokens: Tokens, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTokens provides tokens, content = content)
}
