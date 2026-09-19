package com.liuxue.assistant.feature.about

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.liuxue.assistant.data.db.Memo
import com.liuxue.assistant.data.repo.MemoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** 内置说明条目的标题（备忘录里靠它识别"不可删除"） */
const val WELCOME_MEMO_TITLE = "📖 使用说明与感谢（内置）"

private const val PREFS = "welcome"
private const val KEY_SEEN = "seen"
private const val KEY_MEMO = "memo_created"

/** 是否已经看过首次启动的说明（看过就不再自动弹） */
fun hasSeenWelcome(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)

fun markWelcomeSeen(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SEEN, true).apply()
}

/** 把说明写进备忘录（只做一次，置顶，不可删除） */
suspend fun ensureWelcomeMemo(context: Context) = withContext(Dispatchers.IO) {
    val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (sp.getBoolean(KEY_MEMO, false)) return@withContext
    runCatching {
        val repo = MemoRepository(context)
        val exists = repo.observeAll().first().any { it.title == WELCOME_MEMO_TITLE }
        if (!exists) {
            repo.save(
                Memo(
                    title = WELCOME_MEMO_TITLE,
                    bodyCipher = repo.encryptBody(WELCOME_TEXT),
                    pinned = true
                )
            )
        }
        sp.edit().putBoolean(KEY_MEMO, true).apply()
    }
}

/**
 * 首次启动展示的「感谢与教程」。
 * 开头是作者原话，后面是程序说明与教程。
 */
@Composable
fun WelcomeDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* 只能点按钮关掉，避免误触 */ },
        title = { Text("感谢与使用说明", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(WELCOME_TEXT, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) { Text("知道了，开始使用") }
            }
        }
    )
}

/** 说明正文：作者的话 + 全程序教程（也存进备忘录） */
val WELCOME_TEXT: String = """
感谢大家使用这个程序，这是我的第一个公开的程序，我希望这个让大家的留学生活变得更加方便。（bug估计会满天飞）。该程序绝大部分是使用ai写的，本人编程能力有限，如果有bug影响到使用我先道歉，未来可能会修复（大概吧）

这个程序应当作为一个方便的使用集合，重要的数据还是自己保管比较好，我怕我程序出的未知bug害了你。这样就不好了。

离线模型翻译靠的是本地模型，注意本地运行ai模型时耗电量会激增

俄汉词典是我根据俄英词典用ai跑出来的（这也是为什么里面会有英语解释，我烂笔记本跑单词跑了整整1天半，实在是翻译不动句子了），遇到不确定正确的情况下建议多方查证一下，下载完整版后应该能涵盖绝大部分单词。另外生词本的抽查纯纯意识流，靠自己老实嗷（我反正不太老实）。

最后祝愿大家平平安安，顺顺利利度过留学生涯（这样一辈子那更是完美）

━━━━━━━━━━ 下面是说明和教程 ━━━━━━━━━━

【五个标签页】
底部五个入口：证件 / 词典 / 学业 / 搜索 / 备忘。

一、证件
· 13+ 分类（护照、签证、落地签、小白条、指纹卡、学生证、学历学位、成绩单、语言证书、保险、医疗、机票、合同、财务、居留许可、其他），可自定义新增分类。
· 记录名称、编号、签发/到期日、备注，可设到期提醒（每天 9 点检查，到点发通知）。
· 附件支持一次选多个任意类型文件；图片可以点开全屏放大（双指缩放 1–8 倍）。
· 所有附件与证件内容都用 AES-256-GCM 加密存本机（密钥在 Android Keystore），不落明文。
· 顶部可搜索、可置顶；「紧急信息」页可写紧急联系人/血型/过敏等，方便出事时快速查看。

二、词典
· 三个独立入口：俄汉 / 俄英 / 英汉（各查各的释义，不混排）。
· 俄语任意变格形式都能还原原形（不用输重音）；词条卡片里带变格表。
· 英汉包是 ECDICT 常用词（约 5.8 万），支持复数/过去式等变形还原，中文也能反查英文。
· 全量俄汉词典（29 万词条）体积大，不随安装包附带：到「词典管理」里「导入文件」或「填直链下载」，也可以把已装好的手机上的词典「导出」传给另一台手机。
· 生词本：收藏、自定义分类、掌握度、简易间隔重复（1/2/7/30 天）。
· 短语集：俄汉对照、分类、收藏、可导出分享。
· 每日抽查：可设每天固定时间、抽查数量、只抽未掌握的（先回忆后揭晓，自评四级）。
· 离线翻译：模型 1.06 GB，首次使用时下载（多镜像/断点续传/进度/校验）；下载完断网也能翻。
· 图片翻译（离线 OCR）：从相册选图 → 自动识别文字 → 复用同一个翻译模型；OCR 模型约 23 MB，也是首次使用时下载。识别语言可选「自动 / 中英 / 俄文」。

三、学业
· 先设学期（开学日期 + 总周数），之后才有"第几周"。
· 课表导入：支持学校那种「按教室排的矩阵课表」xlsx（合并单元格、多段周次、а/б 分组都能解析），也支持通用课程行表格；导入时可选班级。
· 课表默认日视图 + 永远默认今天；可切周视图；点某节课看完整详情（教师、班级、周次、地点、课程内容、网课链接）。
· 课程 / 作业（三态）/ 考试 / 课件资料都在这里；资料与作业附件同样加密，图片可点开放大。
· 班级筛选支持"本子班 + 同班号整班课 + 合班课"的匹配规则，选择会记住。
· 上课提醒：按班级 + 自定义提前 1–240 分钟，每 15 分钟检查一次。
· 作业可以生成分享码/深链发给同学，对方粘贴即可导入到对应课程。
· 课表可以反向导出成同样格式的 xlsx。

四、搜索 / AI
· AI 走 OpenAI 兼容协议：设置里填 BaseURL + API Key + 模型名即可（DeepSeek、各中转站、本地 Ollama 都行）。
· 可以「测试 API 响应」直接验证配置通不通，失败原因会显示出来。
· AI 预设（系统提示词）：内置通用学习助手/俄语语法讲解/作文批改/翻译润色/文献摘要等，也可以自己加；点一下选中，**再点一下取消选择**（不用预设也行）。
· 深度思考开关：更长的系统提示 + 更低温 + 更大输出预算。
· 附件：可以给 AI 发图片（压到长边 1280 再发，需要模型支持视觉）、PDF（抽文字）、Word / Excel / PPT（抽文字）、文本类文件；单个文件上限 200 MB。只发附件不写字也行。
· 问答历史会保存，可点回当时的问答。
· 汇率换算：输入金额与币种即时换算（open.er-api.com，失败自动回退 frankfurter.app）；在提问框里输入「美元 人民币」也会自动跳到汇率页。

五、备忘
· 正文加密存储（标题明文便于搜索）；可设提醒日期（当天 9 点后触发）。
· 月历视图：有备忘或提醒的日期会打点，点某天只看那天。
· 附件可多选，支持分享、删除（删除都有二次确认）。

【数据与隐私】
· 完全离线：证件、词典、生词本、学业、备忘、离线翻译、图片识别 —— 都不联网。
· 需要联网：AI 问答（走你自己填的服务端）、汇率、模型/词典包的首次下载。
· 所有 API Key 只存在本机应用私有目录，不参与云备份，也不会上传。
· 「导入导出」页可以把生词本/短语集/证件/学业导出成 .lex 文件做备份；建议定期导出，重要数据自己也留一份。

【几个小技巧】
· 表格/文件选择器：先点文件那一行选中，再点右下角「完成」，文件才会交回 App。
· 长文本翻译会分段进行，界面上会显示"正在翻译第 i/N 段"。
· 安装新版本时系统会弹安全提示，选「重新安装」即可；换了新版本记得让它重新下载一次模型（模型文件在应用私有目录）。
· 用久了想省空间：可以删掉词典包或离线模型，需要时再下。

【已知不足】
· 手写体识别目前不理想（公开模型只能做到"能看出大概"），印刷体已经很准。
· 俄汉词典是 AI 批量翻译的，个别释义可能不准，重要场合建议再查一遍。
· AI 联网检索依赖搜索服务商，国内环境下免 Key 的网页搜索不稳定，建议在设置里填 Serper/Brave 的 Key 或用自己的 SearXNG。

祝留学顺利。
""".trimIndent()
