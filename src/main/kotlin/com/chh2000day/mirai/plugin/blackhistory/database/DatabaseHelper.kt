package com.chh2000day.mirai.plugin.blackhistory.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.mamoe.mirai.utils.MiraiLogger
import java.io.Closeable
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.math.pow

@Suppress("SqlResolve", "SqlNoDataSourceInspection")
class DatabaseHelper(
    private val dbUrl: String,
    private val dbUsername: String,
    private val dbPassword: String,
    private val logger: MiraiLogger
) :
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

    internal suspend fun insertBlackHistory(
        qq: Long,
        operatorQQ: Long,
        group: Long,
        filename: String
    ): Boolean =
        kotlin.runCatching {
            val statement =
                getConnection().prepareStatement("insert into pic_info (filename, qq, `group`,operator) values (?,?,?,?);")
            statement.use {
                it.setString(1, filename)
                it.setLong(2, qq)
                it.setLong(3, group)
                it.setLong(4, operatorQQ)
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