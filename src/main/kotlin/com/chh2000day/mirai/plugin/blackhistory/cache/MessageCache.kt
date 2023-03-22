package com.chh2000day.mirai.plugin.blackhistory.cache

import net.mamoe.mirai.message.data.MessageChain
import net.mamoe.mirai.message.data.sourceOrNull
import java.util.concurrent.atomic.AtomicLong

class MessageCache(private val size: Int) {
    private val cacheArray: Array<MessageChain?> = arrayOfNulls(size)
    private val counter = AtomicLong(0)
    fun put(message: MessageChain) {
        val index = counter.getAndIncrement() % size
        cacheArray[index.toInt()] = message
    }

    fun get(ids: IntArray, groupId: Long): MessageChain? {
        for (i in 0 until size) {
            //从0开始填充,因此可以跳过空消息
            val msg = cacheArray[i] ?: break
            val msgSrc = msg.sourceOrNull ?: continue
            if (msgSrc.fromId == groupId && msgSrc.ids.contentEquals(ids)) {
                return msg
            }
        }
        return null
    }
}