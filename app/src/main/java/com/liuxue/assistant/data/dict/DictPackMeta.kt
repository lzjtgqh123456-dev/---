package com.liuxue.assistant.data.dict

/**
 * 词典包的「默认下载源」元信息（和离线翻译模型一个思路：多镜像 + 断点续传 + 进度）。
 *
 * 当前源：HuggingFace dataset 仓库 `datasets/SweetTouch/transpak` 里的 `dict_ru.db`。
 * 直链格式（dataset 仓库）：
 *   https://huggingface.co/datasets/<用户>/<仓库>/resolve/main/<文件名>
 *   国内镜像 https://hf-mirror.com/datasets/<用户>/<仓库>/resolve/main/<文件名>
 *
 * 想换源/换文件名，只改下面的 [RU_PATH] 即可。
 * 用户也可以在「词典管理」里手填自己的直链，会覆盖这里的默认值。
 */
object DictPackMeta {

    /** HuggingFace 上的路径（已实测：公开可访问、支持 Range、431,058,944 字节、SQLite 头正确） */
    private const val RU_PATH = "datasets/SweetTouch/transpak/resolve/main/dict_ru.db"

    const val RU_FILE_NAME = "dict_ru.db"

    /** 预期大小（字节）：431,058,944 = 411 MiB；下载完做完整性校验 */
    const val RU_EXPECTED_SIZE = 431_058_944L

    /** 下载源，按顺序尝试（hf-mirror 优先，失败自动换 huggingface.co） */
    val RU_MIRRORS: List<String> = listOf(
        "https://hf-mirror.com/$RU_PATH",
        "https://huggingface.co/$RU_PATH"
    )

    /** 目前只有俄语包需要下载；英汉包随 APK 内置（删了也会自动从 APK 重新释放） */
    fun mirrorsFor(fileKey: String): List<String> =
        if (fileKey == "ru") RU_MIRRORS else emptyList()
}
