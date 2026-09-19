package com.liuxue.assistant.feature.dict

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.liuxue.assistant.data.dict.DictForm

/** 俄语语法标签 -> 中文（这是查词体验的关键） */
object RuGrammar {

    private val map = mapOf(
        // 格
        "nominative" to "主格", "genitive" to "属格", "dative" to "与格",
        "accusative" to "宾格", "instrumental" to "工具格", "prepositional" to "前置格",
        // 数
        "singular" to "单数", "plural" to "复数",
        // 性
        "masculine" to "阳性", "feminine" to "阴性", "neuter" to "中性",
        // 动词
        "first-person" to "第一人称", "second-person" to "第二人称", "third-person" to "第三人称",
        "present" to "现在时", "past" to "过去时", "future" to "将来时",
        "imperfective" to "未完成体", "perfective" to "完成体",
        "imperative" to "命令式", "infinitive" to "不定式",
        "participle" to "分词", "gerund" to "副动词", "adverbial" to "副词性",
        "transitive" to "及物", "intransitive" to "不及物", "reflexive" to "反身",
        "short" to "短尾", "comparative" to "比较级", "superlative" to "最高级",
        // 名词特性
        "animate" to "动物", "inanimate" to "非动物",
        // 其它
        "canonical" to "原形", "colloquial" to "口语", "dated" to "旧式", "archaic" to "古旧",
        "diminutive" to "指小", "augmentative" to "指大", "romanization" to "拉丁转写",
        "adverb" to "副词", "adjective" to "形容词", "noun" to "名词", "verb" to "动词",
    )

    fun zh(tag: String): String = map[tag.trim()] ?: tag.trim()

    fun zhTags(tags: List<String>): String =
        tags.filter { it.isNotBlank() && it != "canonical" }.joinToString("·") { zh(it) }

    /** 把变形按语法维度分组，供表格展示 */
    fun group(forms: List<DictForm>): List<Pair<String, List<DictForm>>> {
        val caseOrder = listOf(
            "nominative", "genitive", "dative", "accusative", "instrumental", "prepositional"
        )
        val byCase = forms.filter { f ->
            val t = f.tags ?: ""
            caseOrder.any { t.contains(it) }
        }
        val byTense = forms.filter { f ->
            val t = f.tags ?: ""
            !caseOrder.any { t.contains(it) } &&
                listOf("present", "past", "future", "imperative", "person").any { t.contains(it) }
        }
        val byOther = forms.filter { it !in byCase && it !in byTense }

        return buildList {
            if (byCase.isNotEmpty()) add("格变化" to byCase.sortedBy { f ->
                caseOrder.indexOfFirst { f.tags?.contains(it) == true }
            })
            if (byTense.isNotEmpty()) add("时态与人称" to byTense)
            if (byOther.isNotEmpty()) add("其他形式" to byOther)
        }
    }
}

@Composable
fun DeclensionTable(forms: List<DictForm>, modifier: Modifier = Modifier) {
    val groups = RuGrammar.group(forms)
        .map { (title, list) -> title to list.distinctBy { it.formPlain to it.tags } }

    if (groups.isEmpty()) {
        Text(
            "该词条暂无变形表",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
        )
        return
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        groups.forEach { (title, list) ->
            Spacer(Modifier.size(4.dp))
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            HorizontalDivider()
            list.take(24).forEach { f ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        f.form,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.width(150.dp)
                    )
                    Text(
                        RuGrammar.zhTags(f.tags?.split(",") ?: emptyList()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (list.size > 24) {
                Text(
                    "… 另有 ${list.size - 24} 个形式",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
