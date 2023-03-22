package com.chh2000day.mirai.plugin.blackhistory

import com.chh2000day.mirai.plugin.blackhistory.cache.MessageCache
import com.chh2000day.mirai.plugin.blackhistory.config.Config
import com.chh2000day.mirai.plugin.blackhistory.database.DatabaseHelper
import com.chh2000day.mirai.plugin.blackhistory.struct.GroupRandomBlackHistoryRecord
import com.chh2000day.mirai.plugin.blackhistory.struct.RandomBlackHistoryRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import net.mamoe.mirai.console.command.*
import net.mamoe.mirai.console.command.CommandManager.INSTANCE.register
import net.mamoe.mirai.console.command.CommandManager.INSTANCE.unregister
import net.mamoe.mirai.console.command.descriptor.ExperimentalCommandDescriptors
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.console.util.ConsoleExperimentalApi
import net.mamoe.mirai.contact.Member
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.globalEventChannel
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.message.data.Image.Key.queryUrl
import net.mamoe.mirai.message.data.MessageSource.Key.quote
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import net.mamoe.mirai.utils.ExternalResource.Companion.uploadAsImage
import net.mamoe.mirai.utils.debug
import net.mamoe.mirai.utils.verbose
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.FileNotFoundException
import kotlin.random.Random

/**
 * @Author CHH2000day
 * @Date 2021/6/7 16:33
 **/
@Suppress("unused")
object BlackHistoryPluginMain : KotlinPlugin(JvmPluginDescription.loadFromResource()) {
    private val STORE_FILE = File(configFolder, "config.json")
    private val httpClient = OkHttpClient()
    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }
    private lateinit var config: Config
    private lateinit var imageDir: File
    private lateinit var dbHelper: DatabaseHelper
    private var hasGroupConstraints = false
    private lateinit var enabledGroups: List<Long>
    private lateinit var messageCache: MessageCache
    private var allowBindViaCommand: Boolean = true

    private val randomBlackRecordMap = mutableMapOf<Long, GroupRandomBlackHistoryRecord>()
    private val random = Random.Default

    /**
     * 是否允许使用如"添加黑历史 X (图片)"的方式添加黑历史
     */

    private var allowAddBlackHistoryWithName: Boolean = true

    /**
     * 来点XX黑历史/来点XX语录
     */
    private val NAME_REGEX = Regex("来点.{1,20}[黑历史|语录]")

    /**
     * @1234565789
     */
    private val AT_REGEX = Regex("@[0-9]+")

    init {
        kotlin.runCatching { Class.forName("com.mysql.cj.jdbc.Driver").getConstructor().newInstance() }
            .exceptionOrNull()?.let {
                logger.error("加载数据库驱动失败!", it)
            }
    }


    override fun onEnable() {
        super.onEnable()
        init()
        globalEventChannel().subscribeAlways<GroupMessageEvent> {
            handleGroupMessage()
        }

        AddCommand.register()
        if (allowBindViaCommand) {
            BindNickCommand.register()
        }
    }

    /**
     * 处理群聊事件:来点xx语录,随机黑历史
     */
    private suspend fun GroupMessageEvent.handleGroupMessage() {
        if (hasGroupConstraints && !enabledGroups.contains(this.group.id)) {
            return
        }

        //缓存
        if (message.contains(Image)) {
            messageCache.put(this.message)
        }

        handleRandomBlackHistory()

        handleGetBlackHistory()
    }

    private fun GroupMessageEvent.getRandomRecord(): RandomBlackHistoryRecord? {
        val poolSize = config.randomBlackHistoryInfoList.firstOrNull {
            it.group == group.id
        }?.poolSize ?: return null
        val groupMap = randomBlackRecordMap.getOrPut(this.group.id) {
            GroupRandomBlackHistoryRecord(
                group.id,
                poolSize,
                mutableMapOf()
            )
        }
        val userRecord = groupMap.recordMap.getOrPut(sender.id) {
            RandomBlackHistoryRecord(sender.id, poolSize, 0, random.nextInt(poolSize))
        }
        return userRecord
    }

    private suspend fun GroupMessageEvent.handleRandomBlackHistory() {
        //处理随机黑历史
        val randomRecord = getRandomRecord()
        if (randomRecord != null) {
            with(randomRecord) {
                val newCounter = (counter + 1) % poolSize
                if (newCounter == 0) {
                    encounteredInCycle = false
                }
                counter = newCounter
            }
            if (randomRecord.counter == randomRecord.targetNum && !randomRecord.encounteredInCycle) {
                randomRecord.encounteredInCycle = true
                randomRecord.targetNum = random.nextInt(randomRecord.poolSize)
                sendBlackHistory(randomRecord.qq, null, true)
            }
        }
    }

    private suspend fun GroupMessageEvent.handleGetBlackHistory() {
        //来点XX语录
        val contentStr = this.message.contentToString()
        for (pattern in NAME_REGEX.findAll(contentStr)) {
            val patternString = pattern.groups[0]?.value!!
            val endIndex = pattern.groups[0].let {
                if (patternString.endsWith("语录")) {
                    it!!.range.last - 1
                } else {
                    it!!.range.last - 2
                }
            }
            val name = contentStr.substring(pattern.range.first + 2, endIndex).trim()
            val qq = when {
                name == "我的" -> {
                    this.sender.id
                }

                name.matches(AT_REGEX) -> {
                    /**
                     * 格式为 @123456789
                     */
                    name.substring(1).trim().toLongOrNull() ?: -1
                }

                else -> {
                    dbHelper.getQQIdByNickname(name)
                }
            }
            if (qq < 10000) {
                this.group.sendMessage("未记录的昵称:$name")
                return
            }
            sendBlackHistory(qq, name)
            //只处理一次
            break
        }
    }

    private suspend fun GroupMessageEvent.sendBlackHistory(
        qq: Long,
        name: String?,
        discardEmptyWarn: Boolean = false
    ) {
        val blackHistoryList = dbHelper.getBlackHistoryList(qq, this.group.id)
        if (blackHistoryList.isEmpty() && !discardEmptyWarn) {
            this.group.sendMessage(this.message.quote() + "找不到${name}的黑历史")
        } else {
            //随机挑个黑历史
            val file = File(imageDir, blackHistoryList.random())
            file.toExternalResource().use {
                this.group.sendMessage(it.uploadAsImage(this.group))
            }
        }
    }

    private fun init() = kotlin.runCatching {
        config = if (STORE_FILE.exists()) {
            val source = STORE_FILE.source().buffer()
            val confStr = source.readUtf8()
            source.close()
            json.decodeFromString(Config.serializer(), confStr)
        } else {
            logger.error("数据库配置文件不存在!")
            throw FileNotFoundException("数据库配置文件不存在!")
        }
        messageCache = MessageCache(config.cacheSize)
        dbHelper = DatabaseHelper(
            config.databaseUrl,
            config.databaseUsername,
            config.databasePassword,
            logger
        )
        imageDir = File(config.imageDir)
        enabledGroups = config.enabledGroups
        //留空以禁用群组限制
        hasGroupConstraints = enabledGroups.isNotEmpty()
        allowAddBlackHistoryWithName = config.allowAddBlackHistoryWithName
        allowBindViaCommand = config.allowBindViaCommand
    }.exceptionOrNull()?.let {
        logger.error("初始化黑历史插件失败!", it)
    }


    override fun onDisable() {
        super.onDisable()
        AddCommand.unregister()
        if (allowBindViaCommand) {
            BindNickCommand.unregister()
        }
        dbHelper.close()
    }

    /**
     * @return 如果下载失败返回空字符串,否则返回下载后的文件名
     */
    private suspend fun downloadImage(image: Image): String = withContext(Dispatchers.IO) {
        runCatching {
            val url = image.queryUrl()
            val filename = image.imageId
            val destFile = File(imageDir, filename)
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.source()?.use { bufferedSource ->
                    val sink = destFile.sink().buffer()
                    sink.use {
                        it.writeAll(bufferedSource)
                    }
                    return@runCatching filename
                }
            }
            ""
        }.getOrElse {
            logger.error("下载图片:${image.queryUrl()}失败", it)
            ""
        }
    }

    /**
     * (/)添加黑历史 <member> <图片>
     * or
     * (/)添加语录 <member> <图片>
     */
    @Suppress("OPT_IN_IS_NOT_ENABLED")
    object AddCommand : RawCommand(
        BlackHistoryPluginMain,
        "添加黑历史",
        secondaryNames = arrayOf("添加语录"),
        prefixOptional = true,
        usage = "(/)添加黑历史 <member> <图片> \n or \n (/)添加语录 <member> <图片>"
    ) {
        @Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
        @OptIn(
            ConsoleExperimentalApi::class,
            ExperimentalCommandDescriptors::class
        )
        override val prefixOptional: Boolean
            get() = true

        /**
         * 在指令被执行时调用.
         *
         * @param args 指令参数.
         *
         * @see CommandManager.execute 查看更多信息
         */
        @Suppress("KDocUnresolvedReference")
        override suspend fun CommandSender.onCommand(args: MessageChain) {
            if (this !is MemberCommandSenderOnMessage) {
                sendMessage("来源错误!")
                return
            }
            val isQuote = fromEvent.message.contains(QuoteReply)
            val origMessage = fromEvent.message[QuoteReply]?.source?.let {
                messageCache.get(it.ids, it.fromId)
            }
            if (isQuote && origMessage == null) {
                sendMessage("找不到原始消息")
                return
            }
            if (args.size < 2 && !isQuote) {
                logger.debug { "Message:" + this.fromEvent.message.map { it::class.qualifiedName }.joinToString() }
                logger.debug { "Args: $args \n" + args.map { it::class.qualifiedName }.joinToString() }
                sendMessage("参数列表错误.\n使用方法:\n$usage")
                return
            }
            val picList = if (isQuote) {
                logger.verbose { origMessage.toString() }
                origMessage!!.filterIsInstance<Image>()
            } else {
                args.filterIsInstance<Image>()
            }
            if (picList.isEmpty()) {
                sendMessage("参数错误:对象非图像")
                return
            }
            when (val destUser = args[0]) {
                is At -> {
                    this.group.members[destUser.target]?.let { member ->
                        handle(member, picList)
                    }
                }

                is PlainText -> {
                    val memberId = dbHelper.getQQIdByNickname(destUser.content.trim())
                    if (memberId == 0L) {
                        sendMessage(this.fromEvent.message.quote() + "${destUser.content}是谁呢QaQ")
                        return
                    }
                    val member = this.group.members.findLast {
                        it.id == memberId
                    }
                    if (member == null) {
                        sendMessage(this.fromEvent.message.quote() + "${destUser.content}不在本群QaQ")
                        return
                    }
                    handle(member, picList)
                }

                else -> {
                    this.sendMessage("未知对象${destUser::class.qualifiedName}:${destUser.contentToString()}")
                }
            }
        }

        private suspend fun UserCommandSender.handle(member: Member, pics: List<Image>) {
            if (this !is MemberCommandSenderOnMessage) {
                return
            }
            val operator = this.user
            val resultMap = mutableMapOf<Image, AddResult>()
            for (pic in pics) {
                resultMap[pic] = handleSingle(operator, member, pic)
            }
            if (resultMap.all { it.value == AddResult.SUCCESS }) {
                val message = fromEvent.message.quote() + "添加黑历史成功"
                this.sendMessage(message)
                return
            } else {
                val failedPicsMap = resultMap.filter { it.value != AddResult.SUCCESS }
                failedPicsMap.forEach {
                    logger.warning("添加黑历史失败:${it.key} : ${it.value}")
                }
                if (failedPicsMap.size == resultMap.size) {
                    //全部失败
                    val message = fromEvent.message.quote() + "添加黑历史大失败"
                    this.sendMessage(message)
                    return
                }
                this.sendMessage(fromEvent.message.quote() + "添加黑历史部分失败")
                val infoMessage = buildMessageChain {
                    add("失败的黑历史:")
                    for (failInfo in failedPicsMap) {
                        add("\n")
                        add(failInfo.key)
                        add(":${failInfo.value}")
                    }
                }
                this.sendMessage(infoMessage)
            }
        }


        private suspend fun handleSingle(operator: Member, member: Member, pic: Image): AddResult {
            //Download image first
            val filename = downloadImage(pic)
            if (filename.isBlank()) {
                return AddResult.DOWNLOAD_FAIL
            }
            return if (dbHelper.insertBlackHistory(
                    qq = member.id,
                    operatorQQ = operator.id,
                    group = member.group.id,
                    filename
                )
            ) {
                AddResult.SUCCESS
            } else {
                AddResult.DATABASE_FAIL
            }
        }

        private enum class AddResult {
            SUCCESS, DOWNLOAD_FAIL, DATABASE_FAIL
        }
    }

    /**
     * "/绑定昵称 '昵称'"
     */
    @Suppress("OPT_IN_IS_NOT_ENABLED")
    object BindNickCommand : SimpleCommand(
        BlackHistoryPluginMain,
        "绑定昵称"
    ) {
        @OptIn(ConsoleExperimentalApi::class, ExperimentalCommandDescriptors::class)
        override val prefixOptional: Boolean
            get() = true

        @Handler
        suspend fun UserCommandSender.handle(nickname: String) {
            if (dbHelper.bindNickname(this.user.id, nickname)) {
                sendMessage("绑定昵称成功")
            } else {
                sendMessage("绑定昵称失败")
            }
        }
    }
}
