package com.chh2000day.mirai.plugin.blackhistory.config

import com.chh2000day.mirai.plugin.blackhistory.struct.RandomBlackHistoryInfo
import kotlinx.serialization.Serializable

@Serializable
data class Config(
    val databaseUrl: String,
    val databaseUsername: String,
    val databasePassword: String,
    val imageDir: String,
    val enabledGroups: List<Long> = mutableListOf(),
    /**
     * 是否允许使用如"添加黑历史 X (图片)"的方式添加黑历史
     */
    val allowAddBlackHistoryWithName: Boolean = true,
    /**
     * 是否启用绑定指令
     */
    val allowBindViaCommand: Boolean = true,
    /**
     * 需要随机发送黑历史的群
     */
    val randomBlackHistoryInfoList: List<RandomBlackHistoryInfo> = mutableListOf(),
    /**
     * 缓存的消息数量,用于恢复添加黑历史时使用
     */
    val cacheSize: Int = 4096
)