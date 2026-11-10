package app.leopard.cockpit.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import app.leopard.cockpit.ui.Capsule
import app.leopard.cockpit.ui.Glyph
import app.leopard.cockpit.ui.Icons
import app.leopard.cockpit.ui.Txt
import app.leopard.cockpit.ui.noRipple
import app.leopard.cockpit.ui.theme.LocalTokens
import app.leopard.cockpit.ui.theme.T

/** A glass search field. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "Search") {
    val t = LocalTokens.current
    Row(
        modifier.height(40.dp).background(t.track, Capsule).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Glyph(Icons.search, t.text2, size = 18.dp)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Txt(placeholder, T.callout, t.text2)
            BasicTextField(
                value, onChange,
                singleLine = true,
                textStyle = T.callout.copy(color = t.text),
                cursorBrush = SolidColor(t.acc),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Box(Modifier.size(20.dp).background(t.text2.copy(alpha = 0.5f), CircleShape).noRipple { onChange("") }, contentAlignment = Alignment.Center) {
                Glyph(Icons.close, t.background, size = 12.dp)
            }
        }
    }
}
