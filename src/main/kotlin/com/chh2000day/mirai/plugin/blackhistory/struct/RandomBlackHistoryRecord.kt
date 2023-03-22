package com.chh2000day.mirai.plugin.blackhistory.struct

/**
 * 随机发送黑历史的记录
 */
data class RandomBlackHistoryRecord(
    val qq: Long,
    var poolSize: Int,
    var counter: Int,
    var targetNum: Int,
    var encounteredInCycle: Boolean = false
)