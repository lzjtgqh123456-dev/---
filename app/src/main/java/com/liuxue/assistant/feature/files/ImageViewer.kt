package com.liuxue.assistant.feature.files

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 全屏图片查看器：**双指缩放 + 单指拖动**。
 *
 * 证件封面、证件附件、课件资料里的图片都是加密落盘的，调用方先用
 * `VaultRepository.coverBytes / attachmentBytes / materialBytes` 解密成字节，
 * 再交给本查看器（[ZoomableImageBytesDialog] 会自行解码）。
 */
@Composable
fun ZoomableImageDialog(bitmap: ImageBitmap, title: String = "", onDismiss: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 8f)
        offset = if (scale <= 1.01f) Offset.Zero else offset + panChange
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(
                bitmap = bitmap,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .transformable(transform)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
            ) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
            }
            Text(
                if (title.isBlank()) "双指缩放 · 拖动查看" else "双指缩放 · 拖动查看 · " + title,
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(18.dp)
            )
        }
    }
}

/** 便捷入口：传解密后的字节，解码失败自动关闭（不崩） */
@Composable
fun ZoomableImageBytesDialog(bytes: ByteArray, title: String = "", onDismiss: () -> Unit) {
    val bmp = remember(bytes) {
        runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, viewerOptions(bytes)).asImageBitmap()
        }.getOrNull()
    }
    if (bmp == null) {
        LaunchedEffect(bytes) { onDismiss() }
    } else {
        ZoomableImageDialog(bmp, title, onDismiss)
    }
}


/** 大图下采样：长边最多 2048px，避免超大照片 OOM */
private fun viewerOptions(bytes: ByteArray, maxSide: Int = 2048): BitmapFactory.Options {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
    return BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
    }
}
