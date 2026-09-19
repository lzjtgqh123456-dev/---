# WorkManager 通过类名实例化 Worker，发布版需保留
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
-keepnames class * extends androidx.work.ListenableWorker

# ONNX Runtime：JNI 类通过反射/类名加载，release 混淆会切掉（封 release 前必须保留）
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# PDFBox-Android 的可选依赖（JPEG2000 解码器），我们不用，忽略缺失
-dontwarn com.gemalto.jp2.**
-dontwarn com.tom_roush.pdfbox.**

# 保留 PDFBox 用到的反射/类名（稳妥起见）
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn org.apache.pdfbox.**
