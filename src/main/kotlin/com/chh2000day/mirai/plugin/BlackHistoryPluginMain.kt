package com.chh2000day.mirai.plugin

import net.mamoe.mirai.console.command.SimpleCommand
import net.mamoe.mirai.console.command.UserCommandSender
import net.mamoe.mirai.console.extension.PluginComponentStorage
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.contact.Member
import java.awt.Image

/**
 * @Author CHH2000day
 * @Date 2021/6/7 16:33
 **/
object BlackHistoryPluginMain : KotlinPlugin(JvmPluginDescription.loadFromResource()) {
    override fun PluginComponentStorage.onLoad() {

    }

    override fun onEnable() {
        super.onEnable()
    }

    override fun onDisable() {
        super.onDisable()
    }

    object AddCommand : SimpleCommand(
        BlackHistoryPluginMain,
        "添加黑历史",
    ) {
        fun UserCommandSender.handle(member: Member, vararg pics: Image) {

        }
    }
}