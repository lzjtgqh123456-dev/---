package com.liuxue.assistant.feature.files

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liuxue.assistant.AppContainer
import com.liuxue.assistant.data.db.EmergencyInfo
import com.liuxue.assistant.data.repo.VaultRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * 紧急信息：label/value 通用结构，用户可任意自定义条目。
 * 数据同样落在应用私有数据库中，不参与云备份。
 */
class EmergencyViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: VaultRepository = AppContainer.vaultRepository(app)

    val items: Flow<List<EmergencyInfo>> = repo.observeEmergency()

    fun save(item: EmergencyInfo) = viewModelScope.launch { repo.saveEmergency(item) }

    fun delete(item: EmergencyInfo) = viewModelScope.launch { repo.deleteEmergency(item) }

    companion object {
        val GROUPS = listOf(
            EmergencyInfo.GROUP_PERSONAL to "本人信息",
            EmergencyInfo.GROUP_CONTACT to "紧急联系人",
            EmergencyInfo.GROUP_INSTITUTION to "机构电话",
            EmergencyInfo.GROUP_OTHER to "其他"
        )

        /** 常见条目的快捷模板，降低录入成本 */
        fun templatesFor(group: String): List<String> = when (group) {
            EmergencyInfo.GROUP_PERSONAL -> listOf("血型", "过敏史", "慢性病", "常用药", "护照号")
            EmergencyInfo.GROUP_CONTACT -> listOf("家人（父）", "家人（母）", "室友", "本国同学", "当地朋友")
            EmergencyInfo.GROUP_INSTITUTION -> listOf("中国驻当地使馆", "学校国际处", "当地报警", "急救", "保险客服")
            else -> listOf("其他")
        }
    }
}
