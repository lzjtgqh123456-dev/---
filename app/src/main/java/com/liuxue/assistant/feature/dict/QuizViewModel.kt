package com.liuxue.assistant.feature.dict

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liuxue.assistant.AppContainer
import com.liuxue.assistant.data.dict.WordbookItem
import com.liuxue.assistant.data.dict.DictEntry
import com.liuxue.assistant.data.dict.DictRepository
import com.liuxue.assistant.data.dict.QuizSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 抽查中的一张卡 */
data class QuizCard(
    val item: WordbookItem,
    val entry: DictEntry?
)

data class QuizState(
    val loading: Boolean = true,
    val cards: List<QuizCard> = emptyList(),
    val index: Int = 0,
    val revealed: Boolean = false,
    /** 每个词的选择结果：mastery 值 */
    val answers: List<Int> = emptyList(),
    val finished: Boolean = false,
    val message: String? = null
) {
    val current: QuizCard? get() = cards.getOrNull(index)
    val total: Int get() = cards.size
    val knownCount: Int get() = answers.count { it >= 2 }
}

/**
 * 每日随机抽查。
 *
 * 抽查逻辑：
 * - 从生词本随机抽 N 个词（默认优先未掌握，可在设置里改）
 * - 先只显示俄语词与音标，让用户主动回忆；点一下才显示释义
 * - 用户自评掌握程度 -> 写回生词本（影响下次复习间隔）
 * - 结束后给出正确率，并记录日期避免当天重复提醒
 */
class QuizViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: DictRepository = AppContainer.dictRepository(app)
    private val settings = QuizSettings(app)

    private val _ui = MutableStateFlow(QuizState())
    val ui: StateFlow<QuizState> = _ui.asStateFlow()

    val settingsSummary: String get() = settings.summary()
    val isEnabled: Boolean get() = settings.enabled
    val lastScore: String? get() = settings.lastScore

    var count: Int
        get() = settings.dailyCount
        set(v) { settings.dailyCount = v }

    var hour: Int
        get() = settings.hour
        set(v) { settings.hour = v; reschedule() }

    var minute: Int
        get() = settings.minute
        set(v) { settings.minute = v; reschedule() }

    var onlyUnmastered: Boolean
        get() = settings.onlyUnmastered
        set(v) { settings.onlyUnmastered = v; reschedule() }

    fun enable(v: Boolean) {
        settings.enabled = v
        reschedule()
    }

    fun start() = viewModelScope.launch {
        _ui.value = QuizState(loading = true)
        val items = repo.allWordbookItems()
        if (items.isEmpty()) {
            _ui.value = QuizState(loading = false, message = "生词本还是空的，先去词典收藏几个词吧")
            return@launch
        }
        val pool = if (onlyUnmastered) items.filter { it.mastery < 3 } else items
        if (pool.isEmpty()) {
            _ui.value = QuizState(loading = false, message = "生词本里的词都已标记为掌握 🎉")
            return@launch
        }
        val picked = pool.shuffled().take(count)
        val cards = picked.map { QuizCard(it, repo.entry(it.entryId)) }
        _ui.value = QuizState(loading = false, cards = cards, answers = emptyList())
    }

    fun reveal() {
        _ui.value = _ui.value.copy(revealed = true)
    }

    /** 自评掌握程度：0 不认识 / 1 模糊 / 2 熟悉 / 3 已掌握 */
    fun answer(mastery: Int) = viewModelScope.launch {
        val s = _ui.value
        val card = s.current ?: return@launch
        repo.setMastery(card.item.entryId, mastery)
        val answers = s.answers + mastery
        val next = s.index + 1
        if (next >= s.cards.size) {
            // 记录本次成绩
            val known = answers.count { it >= 2 }
            settings.lastScore = "上次抽查：认识 $known / ${answers.size}"
            _ui.value = s.copy(answers = answers, finished = true, revealed = false)
        } else {
            _ui.value = s.copy(answers = answers, index = next, revealed = false)
        }
    }

    fun restart() = start()

    fun clearMessage() { _ui.value = _ui.value.copy(message = null) }

    // ---------- 设置 ----------

    private fun reschedule() {
        com.liuxue.assistant.notify.ReminderScheduler.scheduleQuiz(getApplication())
    }
}
