package com.chh2000day.mirai.plugin.blackhistory.struct

import kotlinx.serialization.Serializable

/**
 * 随机发送黑历史的信息
 */
@Serializable
data class RandomBlackHistoryInfo(val group: Long, val poolSize: Int)