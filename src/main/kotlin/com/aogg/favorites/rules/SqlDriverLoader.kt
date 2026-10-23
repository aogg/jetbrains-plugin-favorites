package com.aogg.favorites.rules

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import java.net.URLClassLoader
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.Driver
import java.util.Properties

/**
 * SQL 驱动动态加载兜底（1.4.0）：
 * IDE 数据库驱动由 Database Tools 按需下载到 IDE 配置目录，**不在本插件类加载器可见路径**，
 * 直接 `Class.forName` 驱动类 / 走 `DriverManager` 都会失败。本对象负责：
 *
 * - 按 JDBC URL 前缀推导候选驱动类名（见 [driverClassCandidates]）；
 * - 按优先级收集候选驱动 jar：
 *   1. 数据源自带：纯反射从 IDE 数据源对象取驱动文件（233 实测链路为
 *      `LocalDataSource.getDatabaseDriver()` → `DatabaseDriver.getClasspathElements()`
 *      / `getAdditionalClasspathElements()` → `SimpleClasspathElement.getClassesRootUrls()`，
 *      全反射 + runCatching，另容忍 `getDriverFiles()` 的版本差异）；
 *   2. 全局兜底：`PathManager.getConfigDir()/jdbc-drivers` 及
 *      `~/.config/JetBrains/<各IDE>/jdbc-drivers`、`~/AppData/Roaming/JetBrains/<各IDE>/jdbc-drivers`、
 *      `~/Library/Application Support/JetBrains/<各IDE>/jdbc-drivers`（存在才扫），递归匹配文件名含
 *      mysql-connector / postgresql / mariadb-java-client / sqlite-jdbc / h2 / mssql-jdbc / ojdbc 的 jar；
 * - 用收集到的 jar 建 `URLClassLoader`（parent = 本插件类加载器，静态缓存按 jar 集合去重）；
 * - **不走 DriverManager**（它按调用方类加载器过滤会漏本加载器里的驱动），改为实例化
 *   `java.sql.Driver` 后直接 `driver.connect(url, Properties)`，user/password 经 Properties 传入；
 *
 * 全部反射与磁盘扫描 runCatching + 判空，失败只影响本次建连（抛带原因的异常），不影响其它通道。
 */
object SqlDriverLoader {

    private val log = Logger.getInstance(SqlDriverLoader::class.java)

    /** 全局驱动目录递归扫描深度上限（驱动 jar 目录层级很浅，防误扫超大目录） */
    private const val MAX_SCAN_DEPTH = 8

    /** 保留的历史 URLClassLoader 数量上限（不 close：已建连接可能仍在使用，仅防无界增长） */
    private const val MAX_RETAINED_LOADERS = 4

    /** 全局兜底目录里按文件名识别驱动 jar 的关键词（小写匹配） */
    private val DRIVER_JAR_KEYWORDS = listOf(
        "mysql-connector",
        "postgresql",
        "mariadb-java-client",
        "sqlite-jdbc",
        "h2",
        "mssql-jdbc",
        "ojdbc"
    )

    /** URL 前缀 → 候选驱动类名（按尝试顺序；同一前缀多个候选时逐个试连） */
    private val DRIVER_CANDIDATES_BY_PREFIX: List<Pair<String, List<String>>> = listOf(
        "jdbc:mysql:" to listOf("com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver"),
        "jdbc:mariadb:" to listOf("org.mariadb.jdbc.Driver"),
        "jdbc:postgresql:" to listOf("org.postgresql.Driver"),
        "jdbc:sqlite:" to listOf("org.sqlite.JDBC"),
        "jdbc:h2:" to listOf("org.h2.Driver"),
        "jdbc:sqlserver:" to listOf("com.microsoft.sqlserver.jdbc.SQLServerDriver"),
        "jdbc:oracle:" to listOf("oracle.jdbc.OracleDriver", "oracle.jdbc.driver.OracleDriver")
    )

    /** URL 前缀 → 展示用数据库名（拼报错文案用） */
    private val FRIENDLY_NAMES_BY_PREFIX: List<Pair<String, String>> = listOf(
        "jdbc:mysql:" to "MySQL",
        "jdbc:mariadb:" to "MariaDB",
        "jdbc:postgresql:" to "PostgreSQL",
        "jdbc:sqlite:" to "SQLite",
        "jdbc:h2:" to "H2",
        "jdbc:sqlserver:" to "SQL Server",
        "jdbc:oracle:" to "Oracle"
    )

    // ////////////////  静态缓存：ClassLoader 按 jar 集合去重  ///////////////////////////////

    private val loaderLock = Any()

    private var cachedLoaderKey: String? = null
    private var cachedClassLoader: URLClassLoader? = null

    /** 历史加载器强引用（不 close，防止 GC 关闭后已建连接失效；数量受 [MAX_RETAINED_LOADERS] 限制） */
    private val retainedLoaders = mutableListOf<URLClassLoader>()

    // ////////////////  对外入口：建连  ////////////////////////////////////////////////////

    /**
     * 用动态加载的驱动建连（不走 DriverManager，规避其调用方类加载器过滤）
     *
     * @param url JDBC URL
     * @param user 用户名（空串按无用户名处理）
     * @param password 密码（空串按无密码处理）
     * @param ideDataSource 可选的 IDE 数据源对象（如 LocalDataSource），非空时优先用其自带驱动文件
     * @return 已打开的 JDBC 连接（调用方负责关闭）
     * @throws IllegalArgumentException URL 为空
     * @throws IllegalStateException 驱动 jar 未找到 / 驱动类不可用 / 全部候选驱动建连失败（文案带原因与指引）
     */
    @JvmStatic
    @JvmOverloads
    fun connect(url: String, user: String, password: String, ideDataSource: Any? = null): Connection {
        // ////////////////  校验参数  start  ///////////////////////////////////////////
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) {
            throw IllegalArgumentException("JDBC URL 为空，无法连接数据库")
        }
        val candidates = driverClassCandidates(trimmedUrl)
        if (candidates.isEmpty()) {
            throw IllegalStateException(
                "暂不支持的 JDBC URL 前缀「${prefixOf(trimmedUrl)}」，" +
                    "目前支持 mysql / mariadb / postgresql / sqlite / h2 / sqlserver / oracle"
            )
        }
        // ////////////////  校验参数  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：收集驱动 jar → 动态类加载 → Driver.connect  start  ///////
        val jars = collectDriverJars(ideDataSource)
        if (jars.isEmpty()) {
            throw IllegalStateException(missingDriverMessage(trimmedUrl, candidates))
        }

        val loader = classLoaderFor(jars)
        val failures = mutableListOf<String>()
        for (driverClass in candidates) {
            val driver = runCatching {
                Class.forName(driverClass, true, loader).getDeclaredConstructor().newInstance() as Driver
            }.getOrElse { error ->
                failures.add("$driverClass：驱动类不可用（${error.message ?: error.javaClass.simpleName}）")
                null
            } ?: continue

            val props = Properties()
            if (user.isNotBlank()) props.setProperty("user", user)
            if (password.isNotEmpty()) props.setProperty("password", password)
            val connection = runCatching { driver.connect(trimmedUrl, props) }.getOrElse { error ->
                failures.add("$driverClass：${error.message ?: error.javaClass.simpleName}")
                null
            }
            if (connection != null) {
                log.info("SQL 规则经动态驱动建连成功：driver=$driverClass，使用驱动 jar ${jars.size} 个")
                return connection
            }
            failures.add("$driverClass：驱动不识别该 URL（connect 返回空）")
        }
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////

        // ////////////////  返回：全部候选失败 → 带原因与指引的异常  start  ///////////////////
        val detail = if (failures.isEmpty()) "无失败详情" else failures.joinToString("；")
        throw IllegalStateException(
            "数据库连接失败（${friendlyNameOf(trimmedUrl)}）：$detail。" +
                "若为驱动缺失：${missingDriverMessage(trimmedUrl, candidates)}"
        )
        // ////////////////  返回  end  ///////////////////////////////////////////////
    }

    // ////////////////  纯函数辅助（便于单测）  /////////////////////////////////////////////

    /**
     * 按 JDBC URL 前缀推导候选驱动类名（纯函数，便于单测）
     *
     * @param url JDBC URL
     * @return 候选驱动类名（按尝试顺序）；不认识的前缀返回空列表
     */
    @JvmStatic
    fun driverClassCandidates(url: String): List<String> {
        val lower = url.trim().lowercase()
        return DRIVER_CANDIDATES_BY_PREFIX
            .firstOrNull { (prefix, _) -> lower.startsWith(prefix) }
            ?.second
            ?: emptyList()
    }

    /**
     * 判断文件名是否是全局兜底目录里的候选驱动 jar（纯函数，便于单测；小写匹配关键词 + .jar 后缀）
     *
     * @param fileName 文件名（可空）
     */
    @JvmStatic
    fun isDriverJarCandidate(fileName: String?): Boolean {
        val name = fileName?.lowercase() ?: return false
        if (!name.endsWith(".jar")) return false
        return DRIVER_JAR_KEYWORDS.any(name::contains)
    }

    /**
     * JDBC URL 字符串转本地 jar 路径（纯函数，便于单测）：容忍 `jar:file:...!/...` 嵌套 URL 与未转义空格
     *
     * @param raw URL 字符串（可空）
     * @return 本地 .jar 文件路径；非 file 协议/解析失败/不是 jar 返回 null
     */
    @JvmStatic
    fun urlStringToPath(raw: String?): Path? {
        // ////////////////  校验参数  start  ///////////////////////////////////////////
        var text = raw?.trim() ?: return null
        if (text.startsWith("jar:", ignoreCase = true)) text = text.substring(4)
        val bang = text.indexOf("!/")
        if (bang > 0) text = text.substring(0, bang)
        if (!text.startsWith("file:", ignoreCase = true)) return null
        // ////////////////  校验参数  end  /////////////////////////////////////////////

        // ////////////////  返回：URI 解析优先，空格等非法字符 URLDecoder 兜底  start  /////////
        val path = runCatching { Paths.get(java.net.URI(text)) }
            .recoverCatching {
                val decoded = URLDecoder.decode(text.removePrefix("file:").removePrefix("FILE:"), StandardCharsets.UTF_8)
                Paths.get(decoded)
            }.getOrNull() ?: return null
        val fileName = path.fileName?.toString() ?: return null
        return path.takeIf { Files.isRegularFile(it) && fileName.endsWith(".jar", ignoreCase = true) }
        // ////////////////  返回  end  ///////////////////////////////////////////////
    }

    // ////////////////  驱动 jar 收集  ////////////////////////////////////////////////////

    /**
     * 按优先级收集候选驱动 jar：a. 数据源自带（有数据源对象时）→ b. 全局兜底目录
     *
     * @param ideDataSource 可选的 IDE 数据源对象
     */
    private fun collectDriverJars(ideDataSource: Any?): List<Path> {
        val jars = LinkedHashSet<Path>()
        if (ideDataSource != null) {
            jars += datasourceDriverJars(ideDataSource)
        }
        jars += globalFallbackDriverJars()
        return jars.toList()
    }

    /**
     * 反射取 IDE 数据源自带的驱动文件（233 实测链路：
     * `getDatabaseDriver()` → `getClasspathElements()`/`getAdditionalClasspathElements()` →
     * `getClassesRootUrls()`；另容忍 `getDriverFiles()` 的版本差异）。任何一步失败只记日志。
     *
     * @param dataSource IDE 数据源对象（如 LocalDataSource）
     */
    private fun datasourceDriverJars(dataSource: Any): List<Path> {
        val paths = LinkedHashSet<Path>()

        // ////////////////  主路径：getDatabaseDriver() → classpathElements  start  ///////////
        runCatching {
            val driver = dataSource.javaClass.getMethod("getDatabaseDriver").invoke(dataSource)
                ?: return@runCatching
            for (methodName in listOf("getClasspathElements", "getAdditionalClasspathElements")) {
                val elements = runCatching {
                    driver.javaClass.getMethod(methodName).invoke(driver) as? List<*>
                }.getOrNull().orEmpty()
                for (element in elements) {
                    val urls = runCatching {
                        element?.javaClass?.getMethod("getClassesRootUrls")?.invoke(element) as? List<*>
                    }.getOrNull().orEmpty()
                    for (url in urls) {
                        urlStringToPath(url as? String)?.let(paths::add)
                    }
                }
            }
        }.onFailure { log.warn("反射读取数据源自带驱动文件失败（降级全局兜底目录）", it) }
        // ////////////////  主路径  end  ///////////////////////////////////////////////

        // ////////////////  版本容错：getDriverFiles()（元素可能是 File/Path/VirtualFile）  start /
        runCatching {
            val files = dataSource.javaClass.getMethod("getDriverFiles").invoke(dataSource) as? List<*>
                ?: return@runCatching
            for (file in files) {
                pathFromUnknown(file)?.let(paths::add)
            }
        }.onFailure { log.debug("数据源无 getDriverFiles()（按 233 API 属正常，忽略）") }
        // ////////////////  版本容错  end  /////////////////////////////////////////////

        return paths.filter { Files.isRegularFile(it) }.toList()
    }

    /**
     * 未知类型对象转路径（File / Path / 带 getPath() 的对象如 VirtualFile / toString 即路径）
     */
    private fun pathFromUnknown(obj: Any?): Path? {
        if (obj == null) return null
        (obj as? Path)?.let { return it }
        (obj as? java.io.File)?.let { return runCatching { it.toPath() }.getOrNull() }
        val text = runCatching { obj.javaClass.getMethod("getPath").invoke(obj) as? String }
            .getOrNull()
            ?: (obj as? CharSequence)?.toString()
            ?: return null
        if (text.isBlank()) return null
        return runCatching { Paths.get(text) }.getOrNull()
            ?.takeIf { Files.isRegularFile(it) && it.fileName?.toString()?.endsWith(".jar", ignoreCase = true) == true }
    }

    /**
     * 扫全局兜底目录收集驱动 jar（目录不存在/不可读时静默跳过）
     */
    private fun globalFallbackDriverJars(): List<Path> {
        val jars = LinkedHashSet<Path>()
        for (dir in globalDriverDirectories()) {
            runCatching {
                Files.walk(dir, MAX_SCAN_DEPTH).use { stream ->
                    stream.filter { Files.isRegularFile(it) && isDriverJarCandidate(it.fileName?.toString()) }
                        .forEach(jars::add)
                }
            }.onFailure { log.warn("扫描全局驱动目录失败（跳过）：$dir", it) }
        }
        return jars.toList()
    }

    /**
     * 候选的全局驱动目录（存在才返回）：
     * IDE 配置目录 jdbc-drivers、~/.config/JetBrains/<各IDE>/jdbc-drivers、
     * ~/AppData/Roaming/JetBrains/<各IDE>/jdbc-drivers、~/Library/Application Support/JetBrains/<各IDE>/jdbc-drivers
     */
    private fun globalDriverDirectories(): List<Path> {
        val dirs = mutableListOf<Path>()

        // ////////////////  目录一：当前 IDE 配置目录（PathManager，233 返回 Path）  start  ///////
        runCatching { PathManager.getConfigDir() }.getOrNull()
            ?.let { dirs.add(it.resolve("jdbc-drivers")) }
        // ////////////////  目录一  end  ///////////////////////////////////////////////

        // ////////////////  目录二：跨 IDE 的全局配置根（存在才扫）  start  ////////////////////
        val userHome = System.getProperty("user.home")
        if (!userHome.isNullOrBlank()) {
            val roots = listOf(
                Paths.get(userHome, ".config", "JetBrains"),
                Paths.get(userHome, "AppData", "Roaming", "JetBrains"),
                Paths.get(userHome, "Library", "Application Support", "JetBrains")
            )
            for (root in roots) {
                runCatching {
                    if (Files.isDirectory(root)) {
                        Files.list(root).use { stream ->
                            stream.filter { Files.isDirectory(it) }
                                .forEach { dirs.add(it.resolve("jdbc-drivers")) }
                        }
                    }
                }.onFailure { log.warn("枚举全局配置根失败（跳过）：$root", it) }
            }
        }
        // ////////////////  目录二  end  ///////////////////////////////////////////////

        return dirs.map { it.toAbsolutePath().normalize() }.distinct().filter { Files.isDirectory(it) }
    }

    // ////////////////  ClassLoader 缓存  //////////////////////////////////////////////////

    /**
     * 取（或创建）覆盖给定 jar 集合的 URLClassLoader（parent = 本插件类加载器；按 jar 集合缓存）
     */
    private fun classLoaderFor(jars: List<Path>): URLClassLoader {
        val key = jars.map { it.toAbsolutePath().normalize().toString() }.sorted().joinToString("|")
        synchronized(loaderLock) {
            cachedClassLoader?.takeIf { key == cachedLoaderKey }?.let { return it }

            val urls = jars.map { it.toUri().toURL() }.toTypedArray()
            val loader = URLClassLoader(urls, SqlDriverLoader::class.java.classLoader)
            cachedClassLoader?.let { previous ->
                retainedLoaders.add(previous)
                while (retainedLoaders.size > MAX_RETAINED_LOADERS) {
                    retainedLoaders.removeAt(0)
                }
            }
            cachedLoaderKey = key
            cachedClassLoader = loader
            return loader
        }
    }

    // ////////////////  报错文案  //////////////////////////////////////////////////////////

    /** URL 的已知前缀（文案展示用；未知返回空串） */
    private fun prefixOf(url: String): String {
        val lower = url.trim().lowercase()
        return DRIVER_CANDIDATES_BY_PREFIX.map { it.first }.firstOrNull { lower.startsWith(it) } ?: ""
    }

    /** URL 对应的展示用数据库名（未知返回「该数据库」） */
    private fun friendlyNameOf(url: String): String {
        val lower = url.trim().lowercase()
        return FRIENDLY_NAMES_BY_PREFIX.firstOrNull { (prefix, _) -> lower.startsWith(prefix) }?.second ?: "该数据库"
    }

    /** 驱动 jar 未找到的明确指引文案（含任务要求的固定提示语） */
    private fun missingDriverMessage(url: String, candidates: List<String>): String {
        return "未找到 ${friendlyNameOf(url)} 驱动 jar，请确认 IDE Database 工具窗已下载该驱动" +
            "（数据源属性 → 驱动 → 下载驱动），或手动将驱动 jar 放入 IDE 配置目录 jdbc-drivers/" +
            "（候选驱动类：${candidates.joinToString(" / ")}）"
    }
}
