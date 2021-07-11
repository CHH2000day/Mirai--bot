package com.chh2000day.mirai.plugin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.mamoe.mirai.console.command.*
import net.mamoe.mirai.console.command.CommandManager.INSTANCE.register
import net.mamoe.mirai.console.command.CommandManager.INSTANCE.unregister
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.console.util.ConsoleExperimentalApi
import net.mamoe.mirai.contact.Member
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.globalEventChannel
import net.mamoe.mirai.message.data.Image
import net.mamoe.mirai.message.data.Image.Key.queryUrl
import net.mamoe.mirai.message.data.MessageChain
import net.mamoe.mirai.message.data.MessageSource.Key.quote
import net.mamoe.mirai.message.data.PlainText
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import net.mamoe.mirai.utils.ExternalResource.Companion.uploadAsImage
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import okio.source
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.math.pow

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

    /**
     * 是否允许使用如"添加黑历史 X (图片)"的方式添加黑历史
     */

    private var allowAddBlackHistoryWithName: Boolean = true
    private val NAME_REGEX = Regex("来点.{1,20}黑历史")

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
            if (hasGroupConstraints && !enabledGroups.contains(this.group.id)) {
                return@subscribeAlways
            }
            val contentStr = this.message.contentToString()
            for (pattern in NAME_REGEX.findAll(contentStr)) {
                val name = contentStr.substring(pattern.range.first + 2, pattern.range.last - 2)
                val qq = dbHelper.getQQIdByNickname(name)
                if (qq < 10000) {
                    logger.verbose("未记录的昵称:$name")
                    return@subscribeAlways
                }
                val blackHistoryList = dbHelper.getBlackHistoryList(qq, this.group.id)
                if (blackHistoryList.isEmpty()) {
                    this.group.sendMessage(this.message.quote() + "找不到${name}的黑历史")
                } else {
                    //随机挑个黑历史
                    val file = File(imageDir, blackHistoryList.random())
                    file.toExternalResource().use {
                        this.group.sendMessage(it.uploadAsImage(this.group))
                    }
                }
                //只处理一次
                break
            }

        }
        AddCommand.register()
        BindNickCommand.register()
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
        dbHelper = DatabaseHelper(
            config.databaseUrl,
            config.databaseUsername,
            config.databasePassword
        )
        imageDir = File(config.imageDir)
        enabledGroups = config.enabledGroups
        //留空以禁用群组限制
        hasGroupConstraints = enabledGroups.isNotEmpty()
    }.exceptionOrNull()?.let {
        logger.error("初始化黑历史插件失败!", it)
    }


    override fun onDisable() {
        super.onDisable()
        AddCommand.unregister()
        BindNickCommand.unregister()
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

    object AddCommand : RawCommand(
        BlackHistoryPluginMain,
        "添加黑历史"
    ) {
        @Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
        @OptIn(
            ConsoleExperimentalApi::class,
            net.mamoe.mirai.console.command.descriptor.ExperimentalCommandDescriptors::class
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
        override suspend fun CommandSender.onCommand(args: MessageChain) {
            if (this !is MemberCommandSenderOnMessage) {
                sendMessage("来源错误!")
                return
            }
            if (args.size < 2) {
                logger.info("参数列表错误")
                return
            }
            val pic = args[1]
            if (pic !is Image) {
                logger.info("参数错误:对象非图像")
                return
            }
            when (val destUser = args[0]) {
                is Member -> {
                    handle(destUser, pic)
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
                    handle(member, pic)
                }
                else -> {
                    sendMessage("未知对象${destUser::class.qualifiedName}:${destUser.contentToString()}")
                }
            }
        }

        private suspend fun UserCommandSender.handle(member: Member, pics: Image) {
            if (this !is MemberCommandSenderOnMessage) {
                return
            }
            //Download image first
            val filename = downloadImage(pics)
            if (filename.isBlank()) {
                this.sendMessage(this.fromEvent.message.quote() + "下载图片失败")
                return
            }
            if (dbHelper.insertBlackHistory(member.id, member.group.id, filename)) {
                this.sendMessage(this.fromEvent.message.quote() + "添加黑历史成功")
            } else {
                this.sendMessage(this.fromEvent.message.quote() + "添加黑历史失败")
            }
        }
    }

    /**
     * "/绑定昵称 '昵称'"
     */
    object BindNickCommand : SimpleCommand(
        BlackHistoryPluginMain,
        "绑定昵称"
    ) {
        @OptIn(ConsoleExperimentalApi::class)
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

    class DatabaseHelper(private val dbUrl: String, private val dbUsername: String, private val dbPassword: String) :
        Closeable {
        private lateinit var mConnection: Connection
        private var errorCounter = 0
        private val connectTime = 1000L
        private val maxConnectionTries = 6

        init {
            connect(dbUrl, dbUsername, dbPassword)
        }

        private fun connect(dbUrl: String, dbUsername: String, dbPassword: String) {
            kotlin.runCatching {
                logger.info("连接数据库...")
                mConnection = DriverManager.getConnection(dbUrl, dbUsername, dbPassword)
                errorCounter = 0
            }.exceptionOrNull()?.let {
                logger.error("连接数据库失败", it)
            }
        }

        @Synchronized
        private suspend fun getConnection(): Connection = withContext(Dispatchers.IO) {
            if (mConnection.isValid(100)) {
                return@withContext mConnection
            } else {
                logger.warning("数据库连接断开")
                errorCounter++
                if (errorCounter > maxConnectionTries) {
                    throw SQLException("无法与数据库取得连接!")
                } else {
                    val delay = connectTime * 2.toDouble().pow(errorCounter.toDouble())
                    logger.warning("将于${delay}ms后第${errorCounter}次重连数据库")
                    delay(delay.toLong())
                    connect(dbUrl, dbUsername, dbPassword)
                    return@withContext getConnection()
                }
            }
        }

        /**
         * @return 黑历史图片的列表
         */
        internal suspend fun getBlackHistoryList(qq: Long, group: Long): List<String> = kotlin.runCatching {
            val result = mutableListOf<String>()
            val statement =
                getConnection().prepareStatement("select filename from `pic_info` where qq=? and `group`=?;")
            statement.use { preparedStatement ->
                preparedStatement.setLong(1, qq)
                preparedStatement.setLong(2, group)
                val resultSet = preparedStatement.executeQuery()
                resultSet.use {
                    while (it.next()) {
                        result.add(it.getString(1))
                    }
                }
            }
            return result
        }.getOrElse {
            logger.error("获取群成员黑历史失败", it)
            emptyList()
        }

        internal suspend fun insertBlackHistory(qq: Long, group: Long, filename: String): Boolean = kotlin.runCatching {
            val statement =
                getConnection().prepareStatement("insert into pic_info (filename, qq, `group`) values (?,?,?);")
            statement.use {
                it.setString(1, filename)
                it.setLong(2, qq)
                it.setLong(3, group)
                return@runCatching it.executeUpdate() > 0
            }
        }.getOrElse {
            logger.error("添加群成员黑历史失败", it)
            false
        }

        internal suspend fun bindNickname(qq: Long, nickname: String): Boolean = kotlin.runCatching {
            val statement =
                getConnection().prepareStatement("insert into nickname (nickname, qq) values (?,?);")
            statement.use {
                it.setString(1, nickname)
                it.setLong(2, qq)
                return@runCatching it.executeUpdate() > 0
            }
        }.getOrElse {
            logger.error("绑定昵称失败", it)
            false
        }

        internal suspend fun getQQIdByNickname(nickname: String): Long = kotlin.runCatching {
            val statement =
                getConnection().prepareStatement("select qq from nickname where nickname=?;")
            statement.use { preparedStatement ->
                preparedStatement.setString(1, nickname)
                val resultSet = preparedStatement.executeQuery()
                resultSet.use {
                    if (it.next()) {
                        return@runCatching it.getLong(1)
                    }
                }
            }
            return 0
        }.getOrElse {
            logger.error("获取群成员黑历史失败", it)
            0
        }

        /**
         * Closes this stream and releases any system resources associated
         * with it. If the stream is already closed then invoking this
         * method has no effect.
         *
         *
         *  As noted in [AutoCloseable.close], cases where the
         * close may fail require careful attention. It is strongly advised
         * to relinquish the underlying resources and to internally
         * *mark* the `Closeable` as closed, prior to throwing
         * the `IOException`.
         *
         * @throws IOException if an I/O error occurs
         */
        @Suppress("KDocUnresolvedReference")
        override fun close() {
            if (!mConnection.isClosed) {
                mConnection.close()
            }
        }
    }

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
        val allowAddBlackHistoryWithName: Boolean = true
    )
}