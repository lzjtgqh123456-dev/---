package com.liuxue.assistant.domain

/** 默认分类；用户可自定义新增（存 DataStore） */
enum class VaultCategory(val key: String, val label: String, val emoji: String) {
    PASSPORT("passport", "护照", "🛂"),
    VISA("visa", "签证", "📄"),
    LANDING_VISA("landing_visa", "落地签", "🛬"),
    SLIP("slip", "小白条", "🧾"),
    FINGERPRINT("fingerprint", "指纹卡", "🫆"),
    STUDENT_ID("student_id", "学生证", "🎓"),
    DIPLOMA("diploma", "学历学位", "📜"),
    TRANSCRIPT("transcript", "成绩单", "📊"),
    LANGUAGE_CERT("language_cert", "语言证书", "🗣"),
    INSURANCE("insurance", "保险", "🛡"),
    MEDICAL("medical", "医疗健康", "🏥"),
    TICKET("ticket", "机票车票", "✈️"),
    CONTRACT("contract", "合同协议", "📝"),
    FINANCE("finance", "财务银行", "💳"),
    RESIDENCE("residence", "居留许可", "🏠"),
    OTHER("other", "其他", "📁");

    companion object {
        fun fromKey(key: String): VaultCategory =
            entries.firstOrNull { it.key == key } ?: OTHER
    }
}
