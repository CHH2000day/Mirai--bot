package com.chh2000day.mirai.plugin.blackhistory.struct

data class GroupRandomBlackHistoryRecord(
    val group: Long,
    var poolSize: Int,
    val recordMap: MutableMap<Long, RandomBlackHistoryRecord>
)