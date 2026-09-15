package com.cruisetune.player.steering

import android.content.SharedPreferences

enum class SteeringAction(val label: String) {
    NONE("不执行"), TOGGLE("播放 / 暂停"), NEXT("下一首"), PREVIOUS("上一首"), PLAY("播放"), PAUSE("暂停")
}

enum class SteeringGesture(val label: String) { SINGLE("单击"), DOUBLE("双击"), LONG("长按") }

enum class SteeringKey(val code: Int, val label: String, val defaultAction: SteeringAction) {
    CENTER(200085, "播放键", SteeringAction.TOGGLE), LEFT(200088, "左键", SteeringAction.PREVIOUS), RIGHT(200087, "右键", SteeringAction.NEXT);
    companion object { fun fromCode(code: Int) = entries.firstOrNull { it.code == code } }
}

class SteeringSettings(private val preferences: SharedPreferences) {
    companion object {
        const val PREFIX = "steering."
        const val ENABLED = "steering.enabled"
        const val DETECT_ONLY = "steering.detectOnly"
    }
    val enabled get() = preferences.getBoolean(ENABLED, false)
    val detectOnly get() = preferences.getBoolean(DETECT_ONLY, false)
    fun action(key: SteeringKey, gesture: SteeringGesture): SteeringAction {
        val default = if (gesture == SteeringGesture.SINGLE) key.defaultAction else SteeringAction.NONE
        return SteeringAction.entries.firstOrNull { it.name == preferences.getString(mappingKey(key, gesture), null) } ?: default
    }
    fun setAction(key: SteeringKey, gesture: SteeringGesture, action: SteeringAction) {
        preferences.edit().putString(mappingKey(key, gesture), action.name).apply()
    }
    fun resetMappings() {
        preferences.edit().apply {
            SteeringKey.entries.forEach { key -> SteeringGesture.entries.forEach { remove(mappingKey(key, it)) } }
        }.apply()
    }
    private fun mappingKey(key: SteeringKey, gesture: SteeringGesture) = "$PREFIX${key.code}.${gesture.name}"
}

data class SteeringStatus(
    val connection: String = "未开启车机按键适配",
    val detail: String = "标准媒体按键仍由系统处理",
    val lastEvent: String = "尚未收到车机按键",
    val lastAction: String = "",
    val menu: String = "按键菜单尚未确认",
)
