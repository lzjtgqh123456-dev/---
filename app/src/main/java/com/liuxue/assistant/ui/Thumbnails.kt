package com.liuxue.assistant.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 按类型给个图标，作为非图片文件的「预览」 */
fun fileEmoji(mime: String, name: String = ""): String {
    val m = mime.lowercase()
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        m.startsWith("image/") -> "🖼"
        m.startsWith("video/") -> "🎬"
        m.startsWith("audio/") -> "🎵"
        m == "application/pdf" || ext == "pdf" -> "📕"
        ext in listOf("doc", "docx", "rtf", "odt") -> "📘"
        ext in listOf("xls", "xlsx", "csv", "ods") -> "📗"
        ext in listOf("ppt", "pptx", "odp") -> "📙"
        ext in listOf("zip", "rar", "7z", "tar", "gz") -> "🗜"
        ext in listOf("txt", "md", "json", "xml", "log") -> "📄"
        else -> "📎"
    }
}

/** 渲染一块缩略图：图片 → 真实预览；其它 / 解码失败 → 类型图标 */
@Composable
fun BytesThumb(
    bytes: ByteArray?,
    mime: String,
    name: String = "",
    size: Dp = 40.dp
) {
    val bmp = remember(bytes) {
        if (bytes == null || !mime.startsWith("image/")) null
        else runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, thumbOptions(bytes))
        }.getOrNull()
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size)
            )
        } else {
            Text(fileEmoji(mime, name), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 直接从本地 Uri 读取（还没入库的待上传文件用这个） */
@Composable
fun UriThumb(
    uri: Uri,
    mime: String,
    name: String = "",
    size: Dp = 40.dp
) {
    val context = LocalContext.current
    var bytes by remember(uri) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(uri) {
        bytes = runCatching {
            if (!mime.startsWith("image/")) null
            else context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
    }
    BytesThumb(bytes, mime, name, size)
}


/** 缩略图下采样解码（长边 ≤ maxSide）：整张解码大图又慢又容易 OOM */
fun decodeThumbBytes(bytes: ByteArray, maxSide: Int = 256): android.graphics.Bitmap? =
    runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, thumbOptions(bytes, maxSide))
    }.getOrNull()

/** 缩略图下采样：长边最多 256px，避免大图 OOM */
private fun thumbOptions(bytes: ByteArray, maxSide: Int = 256): BitmapFactory.Options {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
    return BitmapFactory.Options().apply { inSampleSize = sample }
}
