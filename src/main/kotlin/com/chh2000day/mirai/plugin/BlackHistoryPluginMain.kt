package com.chh2000day.mirai.plugin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.mamoe.mirai.console.command.SimpleCommand
import net.mamoe.mirai.console.command.UserCommandSender
import net.mamoe.mirai.console.extension.PluginComponentStorage
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.contact.Member
import okio.buffer
import okio.source
import java.awt.Image
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
object BlackHistoryPluginMain : KotlinPlugin(JvmPluginDescription.loadFromResource()) {
    private val STORE_FILE = File(configFolder, "config.json")
    private lateinit var config: Config
    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }
    private lateinit var dbHelper: DatabaseHelper


    init {
        kotlin.runCatching { Class.forName("com.mysql.cj.jdbc.Driver").getConstructor().newInstance() }
            .exceptionOrNull()?.let {
                logger.error("加载数据库驱动失败!", it)
            }
    }


    override fun onEnable() {
        super.onEnable()
    }

    private suspend fun init() = withContext(Dispatchers.IO) {
        kotlin.runCatching {
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
        }.exceptionOrNull()?.let {
            logger.error("初始化黑历史插件失败!", it)
        }
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
        val imageDir: String
    )
}