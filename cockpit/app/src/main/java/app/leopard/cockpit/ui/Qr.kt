package app.leopard.cockpit.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** A QR code on a white card, sharp at any size. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier, size: Dp = 168.dp) {
    val bitmap = remember(text) { qr(text) }
    Box(modifier.size(size).background(Color.White, rounded(16.dp)).padding(10.dp)) {
        if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
    }
}

private fun qr(text: String): ImageBitmap? = runCatching {
    val m = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )
    val w = m.width
    val h = m.height
    val px = IntArray(w * h) { i -> if (m[i % w, i / w]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()
