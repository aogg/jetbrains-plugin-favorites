package com.aogg.favorites.rules

import com.aogg.favorites.helper.RulePasswordStore
import com.aogg.favorites.model.MatchRule
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import java.sql.ResultSet

/**
 * SQL 规则执行器（1.4.0），双通道连接尽力而为，任何失败 throw 异常
 * （由 [com.aogg.favorites.model.RuleMatchManager] 统一转 ERROR 结果，不影响其它规则）：
 *
 * - 通道一（sqlDatasourceName 非空）：复用 IDE 数据源（Settings→Database Tools→Data Sources）。
 *   Database Tools 插件（id=com.intellij.database）为闭源捆绑插件、API 跨版本不稳定（233 实测
 *   `LocalDataSourceManager` 在 `com.intellij.database.dataSource` 包且 `getInstance(project)` 无参签名
 *   与公开文档不符），故统一纯反射访问：枚举数据源→按名匹配→取其已保存的 URL/用户名/密码
 *   （DatabaseCredentials，含 PasswordSafe 解密）→ 走标准 JDBC 连接。
 *   插件未安装/未启用时抛「未找到 IDE 数据源支持，请改用手动连接」；
 * - 通道二（手动连接）：按规则里的 URL/用户名/密码建连，
 *   密码从 PasswordSafe 读取（[RulePasswordStore]，XML 永不落明文）；
 *
 * 两通道建连统一走 [SqlDriverLoader]：IDE 数据库驱动由 Database Tools 按需下载到 IDE 配置目录、
 * 不在本插件类加载器可见路径，故不使用 DriverManager（其按调用方类加载器过滤会漏），而是按
 * URL 前缀推导候选驱动类 → 优先取数据源自带驱动文件（通道一）、再扫全局兜底目录
 * （IDE 配置目录及跨 IDE 的 jdbc-drivers）→ 动态 URLClassLoader + 实例化 java.sql.Driver 直连；
 * 驱动 jar 未找到时抛「未找到 xx 驱动 jar，请确认 IDE Database 工具窗已下载该驱动」。
 *
 * 查询执行：Statement + `setQueryTimeout(30)`（驱动不支持时降级不设超时），逐行读 ResultSet
 * （单元格取 [cellToText]，null→""），行数达 limit 即停；sqlPathColumn 非空时该列值填入
 * RowItem.path（列不存在→path=null 不报错）；ResultSet/Statement/Connection 全部关闭。
 */
class SqlRuleExecutor : RuleExecutor {

    companion object {
        /** 单次执行结果条数硬上限（与 RegexRuleExecutor 一致） */
        const val MAX_RESULT_LIMIT = 10000

        /** 查询超时秒数 */
        const val QUERY_TIMEOUT_SECONDS = 30

        /** Database Tools 捆绑插件 id */
        private const val DATABASE_PLUGIN_ID = "com.intellij.database"

        /** Database Tools 内部类名（233 实测位置；后续版本挪包需同步调整） */
        private const val DATA_SOURCE_MANAGER_CLASS = "com.intellij.database.dataSource.LocalDataSourceManager"
        private const val DATABASE_CREDENTIALS_CLASS = "com.intellij.database.access.DatabaseCredentials"

        private val log = Logger.getInstance(SqlRuleExecutor::class.java)

        /**
         * 单元格值转文本（纯函数，便于单测）：null→""，其余 toString()
         *
         * @param value ResultSet 单元格原始值
         */
        @JvmStatic
        fun cellToText(value: Any?): String = value?.toString() ?: ""

        /**
         * 解析「路径列」的 1-based 列下标（纯函数，便于单测）
         *
         * @param labels 全部列名（按查询列顺序）
         * @param pathColumn 目标列名（空串=未启用，返回 -1）
         * @return 1-based 下标；未启用/列不存在返回 -1（不报错）
         */
        @JvmStatic
        fun resolvePathColumnIndex(labels: List<String>, pathColumn: String): Int {
            // ////////////////  校验参数：未启用路径列  start  /////////////////////////////
            val target = pathColumn.trim()
            if (target.isEmpty()) return -1
            // ////////////////  校验参数  end  ///////////////////////////////////////////

            // ////////////////  返回：忽略大小写与首尾空白匹配列名  start  ///////////////////
            return labels.indexOfFirst { it.trim().equals(target, ignoreCase = true) }.let { if (it >= 0) it + 1 else -1 }
            // ////////////////  返回  end  ///////////////////////////////////////////////
        }

        /**
         * 枚举当前项目全部 IDE 数据源名（供规则编辑对话框数据源下拉使用，与 execute 同一反射通道）
         * 任何失败返回空列表（对话框给「未读取到」提示），不抛异常。
         *
         * @param project 项目
         * @return 数据源名列表（按 IDE 中的顺序）
         */
        @JvmStatic
        fun listIdeDatasourceNames(project: Project): List<String> {
            // ////////////////  校验参数：插件未装/类不可见→空列表  start  ///////////////////
            val classLoader = databasePluginClassLoader() ?: return emptyList()
            val managerClass = runCatching { Class.forName(DATA_SOURCE_MANAGER_CLASS, true, classLoader) }
                .getOrNull() ?: return emptyList()
            // ////////////////  校验参数  end  ///////////////////////////////////////////

            // ////////////////  返回：getDataSources() 按名收集  start  /////////////////////
            val namesResult: Result<List<String>> = runCatching {
                val manager = managerClass.getMethod("getInstance", Project::class.java).invoke(null, project)
                val dataSources = managerClass.getMethod("getDataSources").invoke(manager) as? List<*>
                    ?: return@runCatching emptyList()
                dataSources.mapNotNull { dataSource ->
                    runCatching { dataSource?.javaClass?.getMethod("getName")?.invoke(dataSource) as? String }
                        .getOrNull()
                }.filter { it.isNotBlank() }
            }
            return namesResult.onFailure { log.warn("枚举 IDE 数据源失败", it) }
                .getOrDefault(emptyList())
            // ////////////////  返回  end  ///////////////////////////////////////////////
        }

        /**
         * 取 Database Tools 插件的类加载器（纯反射通道入口；插件未加载返回 null）
         */
        private fun databasePluginClassLoader(): ClassLoader? {
            return runCatching {
                PluginManagerCore.getPlugin(PluginId.getId(DATABASE_PLUGIN_ID))?.pluginClassLoader
            }.getOrNull()
        }
    }

    override fun execute(project: Project, rule: MatchRule): List<RuleItem> {
        // ////////////////  校验参数：SQL 非空 + limit 收敛  start  ///////////////////////
        val sql = rule.sqlQuery.trim()
        if (sql.isEmpty()) {
            throw IllegalArgumentException("SQL 查询语句为空，请编辑规则补充")
        }
        val limit = rule.limit.coerceIn(1, MAX_RESULT_LIMIT)
        // ////////////////  校验参数  end  ////////////////////////////////////////////

        // ////////////////  分支一：IDE 数据源通道  start  ///////////////////////////////
        if (rule.sqlDatasourceName.isNotBlank()) {
            val config = loadIdeDatasourceConfig(project, rule.sqlDatasourceName.trim())
            return query(config.url, config.user, config.password, config.dataSource, sql, limit, rule.sqlPathColumn)
        }
        // ////////////////  分支一  end  /////////////////////////////////////////////

        // ////////////////  分支二：手动连接通道（密码取 PasswordSafe）  start  //////////////
        val url = rule.sqlJdbcUrl.trim()
        if (url.isEmpty()) {
            throw IllegalArgumentException("JDBC URL 为空，请编辑规则填写连接地址或选择 IDE 数据源")
        }
        val password = RulePasswordStore.read(rule.id)
        return query(url, rule.sqlUser.trim(), password, null, sql, limit, rule.sqlPathColumn)
        // ////////////////  分支二  end  /////////////////////////////////////////////
    }

    /**
     * 建连并执行查询：连接统一经 [SqlDriverLoader]（数据源驱动文件优先 → 全局 jdbc-drivers 兜底，
     * 不走 DriverManager）；Connection/Statement/ResultSet 逐层关闭，任何失败转带原因的异常
     */
    private fun query(
        url: String,
        user: String,
        password: String,
        ideDataSource: Any?,
        sql: String,
        limit: Int,
        pathColumn: String
    ): List<RuleItem> {
        val connection = runCatching { SqlDriverLoader.connect(url, user, password, ideDataSource) }.getOrElse { error ->
            // 驱动缺失/连接失败等已带明确文案的异常直接透出，其余包一层统一前缀
            if (error is IllegalStateException || error is IllegalArgumentException) throw error
            throw IllegalStateException("数据库连接失败：${error.message ?: error.javaClass.simpleName}", error)
        }
        return connection.use { conn ->
            val statement = conn.createStatement()
            statement.use { st ->
                // 部分驱动不支持查询超时，失败降级为不设超时
                runCatching { st.queryTimeout = QUERY_TIMEOUT_SECONDS }
                    .onFailure { log.warn("设置查询超时失败（驱动不支持，降级不设超时）", it) }
                val hasResultSet = runCatching { st.execute(sql) }.getOrElse { error ->
                    throw IllegalStateException("SQL 执行失败：${error.message ?: error.javaClass.simpleName}", error)
                }
                if (!hasResultSet) {
                    throw IllegalStateException("SQL 已执行但未返回结果集，请使用 SELECT 查询")
                }
                val resultSet = st.resultSet ?: throw IllegalStateException("SQL 未返回结果集，请使用 SELECT 查询")
                resultSet.use { rs -> readRows(rs, limit, pathColumn) }
            }
        }
    }

    /**
     * 逐行读 ResultSet → RowItem 列表（列名取 meta；单元格失败按空串处理；达 limit 即停）
     */
    private fun readRows(rs: ResultSet, limit: Int, pathColumn: String): List<RuleItem> {
        val meta = rs.metaData
        val columnCount = meta.columnCount
        val labels = (1..columnCount).map { column ->
            runCatching { meta.getColumnLabel(column) }.getOrNull() ?: "column$column"
        }
        val pathIndex = resolvePathColumnIndex(labels, pathColumn)

        // ////////////////  核心逻辑：逐行读取至 limit  start  ///////////////////////////
        val rows = mutableListOf<RuleItem>()
        while (rows.size < limit && rs.next()) {
            val cells = (1..columnCount).map { column ->
                cellToText(runCatching { rs.getObject(column) }.getOrNull())
            }
            // 列不存在时 pathIndex=-1 → path=null，不报错
            val path = if (pathIndex > 0) cells[pathIndex - 1].takeIf { it.isNotBlank() } else null
            rows.add(RuleItem.RowItem(cells, path))
        }
        return rows
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * IDE 数据源连接配置（反射读取结果的载体）
     *
     * @param url JDBC URL
     * @param user 用户名（可能为空串）
     * @param password 已解密密码（可能为空串）
     * @param dataSource 数据源对象本体（如 LocalDataSource，供 [SqlDriverLoader] 反射取驱动文件；可能为 null）
     */
    private data class IdeDatasourceConfig(
        val url: String,
        val user: String,
        val password: String,
        val dataSource: Any?
    )

    /**
     * 通道一：纯反射读取 IDE 数据源的连接配置（按名匹配；任何一步失败抛带明确文案的异常）
     *
     * @param project 项目
     * @param datasourceName 数据源名
     */
    private fun loadIdeDatasourceConfig(project: Project, datasourceName: String): IdeDatasourceConfig {
        // ////////////////  校验参数：Database Tools 插件可用性  start  ///////////////////
        val classLoader = databasePluginClassLoader()
            ?: throw IllegalStateException("未找到 IDE 数据源支持，请改用手动连接")
        val managerClass = runCatching { Class.forName(DATA_SOURCE_MANAGER_CLASS, true, classLoader) }
            .getOrNull() ?: throw IllegalStateException("未找到 IDE 数据源支持，请改用手动连接")
        // ////////////////  校验参数  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：枚举→按名匹配→读 URL/用户名/密码  start  ///////////////
        val configResult: Result<IdeDatasourceConfig> = runCatching {
            val manager = managerClass.getMethod("getInstance", Project::class.java).invoke(null, project)
            val dataSources = managerClass.getMethod("getDataSources").invoke(manager) as? List<*>
                ?: emptyList<Any>()
            val dataSource = dataSources.firstOrNull { candidate ->
                runCatching {
                    candidate?.javaClass?.getMethod("getName")?.invoke(candidate) == datasourceName
                }.getOrDefault(false)
            } ?: throw IllegalArgumentException("未找到名为「$datasourceName」的 IDE 数据源，请检查名称或改用手动连接")

            val url = runCatching {
                dataSource?.javaClass?.getMethod("getUrl")?.invoke(dataSource) as? String
            }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("数据源「$datasourceName」缺少 JDBC URL，请改用手动连接")
            val user = runCatching {
                dataSource?.javaClass?.getMethod("getUsername")?.invoke(dataSource) as? String
            }.getOrNull().orEmpty()
            IdeDatasourceConfig(url, user, readIdeDatasourcePassword(classLoader, dataSource), dataSource)
        }
        return configResult.getOrElse { error ->
            if (error is IllegalArgumentException || error is IllegalStateException) throw error
            throw IllegalStateException("读取 IDE 数据源「$datasourceName」失败：${error.message ?: error.javaClass.simpleName}", error)
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * 反射读取 IDE 数据源已保存的密码（DatabaseTools 自己管理存储/解密；失败返回空串按无密码处理）
     *
     * OneTimeString 一次性消费疑点已按 233 发行版字节码核实：
     * `OneTimeString extends CharArrayCharSequence`，未覆写 `toString()`，继承实现为
     * `new String(chars, start, length)`——只读取、不销毁；真正会清空的是 `toString(clear=true)`。
     * 故此处优先走反射调 `toString(false)` 显式非销毁读取，失败再按 CharSequence 视角兜底，全 runCatching。
     */
    private fun readIdeDatasourcePassword(classLoader: ClassLoader, dataSource: Any?): String {
        // ////////////////  校验参数  start  ///////////////////////////////////////////
        if (dataSource == null) return ""
        // ////////////////  校验参数  end  /////////////////////////////////////////////

        // ////////////////  返回：DatabaseCredentials.getPassword(ds)→OneTimeString  start  //
        val raw = runCatching {
            val credentialsClass = Class.forName(DATABASE_CREDENTIALS_CLASS, true, classLoader)
            val pointClass = Class.forName("com.intellij.database.model.DasDataSource", true, classLoader)
            val credentials = credentialsClass.getMethod("getInstance").invoke(null)
            credentialsClass.getMethod("getPassword", pointClass).invoke(credentials, dataSource)
        }.onFailure { log.warn("读取 IDE 数据源密码失败（按无密码继续）", it) }
            .getOrNull() ?: return ""

        extractOneTimeStringText(raw)?.let { return it }
        log.warn("IDE 数据源密码对象无法转为文本（按无密码继续）")
        return ""
        // ////////////////  返回  end  ///////////////////////////////////////////////
    }

    /**
     * OneTimeString → 文本的稳妥读取：优先 `toString(false)`（显式不销毁），失败按 CharSequence 兜底；
     * 两条路都失败返回 null（调用方按无密码继续）。全 runCatching，不抛异常。
     */
    private fun extractOneTimeStringText(raw: Any): String? {
        val viaNonClearingCall = runCatching {
            raw.javaClass.getMethod("toString", Boolean::class.javaPrimitiveType)
                .invoke(raw, false) as? String
        }.getOrNull()
        if (viaNonClearingCall != null) return viaNonClearingCall
        return runCatching { (raw as? CharSequence)?.toString() }.getOrNull()
    }
}
