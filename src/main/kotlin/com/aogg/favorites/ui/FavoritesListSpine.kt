package com.aogg.favorites.ui

import com.aogg.favorites.model.Bookmark
import com.aogg.favorites.model.FavoriteScope
import java.io.File

/**
 * 收藏树路径工具（1.3.0 PhpStorm 风格树内部使用）
 * 统一处理：反斜杠→正斜杠、按段拆分/拼接、全局作用域绝对路径前导斜杠（盘符路径除外）
 */
internal object FavoritesTreePaths {

    /** 盘符段正则（如 "C:"，Windows 绝对路径跨平台解析时保留原样，不补前导斜杠） */
    private val DRIVE_SEGMENT = Regex("^[A-Za-z]:$")

    /**
     * 路径是否为绝对路径（File#isAbsolute 与盘符前缀两种判定合并）
     *
     * @param path 原始路径字符串
     * @return true=绝对路径（全局作用域常见）；false=相对路径（项目作用域常见）
     */
    @JvmStatic
    fun isAbsolutePath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return File(normalized).isAbsolute || DRIVE_SEGMENT.matches(normalized.substringBefore('/'))
    }

    /**
     * 路径拆分为路径段（忽略空段，兼容前导/尾随分隔符与反斜杠）
     *
     * @param path 路径字符串
     * @return 路径段列表（如 "/var/www/a.php" → [var, www, a.php]）
     */
    fun segments(path: String): List<String> = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }

    /**
     * 路径段拼接回路径字符串：空段=作用域基准目录（项目根/文件系统根）返回空串；
     * 全局作用域补前导斜杠（"C:" 盘符段开头除外）
     *
     * @param segments 路径段列表
     * @param absolute 是否绝对路径语义（全局作用域=true）
     * @return 规范化路径字符串
     */
    fun join(segments: List<String>, absolute: Boolean): String {
        // 校验参数：空段=作用域基准目录
        if (segments.isEmpty()) return ""
        // 核心逻辑：拼接 + 全局作用域补前导斜杠（盘符除外）
        val joined = segments.joinToString("/")
        val startsWithDrive = DRIVE_SEGMENT.matches(segments.first())
        return if (absolute && !startsWithDrive) "/$joined" else joined
    }

    /**
     * 父目录路径 + 子名 → 子路径（处理空基准目录与根目录 "/" 两种边界）
     *
     * @param parentPath 父目录路径（""=作用域基准目录）
     * @param name 子目录/文件名
     * @param absolute 是否绝对路径语义
     * @return 子路径字符串
     */
    fun childPath(parentPath: String, name: String, absolute: Boolean): String = when {
        parentPath.isEmpty() -> if (absolute) "/$name" else name
        parentPath == "/" -> "/$name"
        else -> "$parentPath/$name"
    }

    /**
     * 取父目录路径（最后一段之前的部分；顶层文件/基准目录返回空串）
     *
     * @param path 规范化路径字符串
     * @return 父目录路径（""=作用域基准目录）
     */
    fun parentPath(path: String): String {
        val index = path.lastIndexOf('/')
        // 无分隔符或以根目录 "/x" 形式出现时，父级都是基准目录
        return if (index <= 0) "" else path.substring(0, index)
    }

    /**
     * 多个路径段列表求最长公共前缀（收藏公共祖先目录计算）
     *
     * @param lists 各收藏的挂靠目录路径段列表
     * @return 公共前缀路径段列表（空列表=基准目录）
     */
    fun commonPrefix(lists: List<List<String>>): List<String> {
        // 校验参数：空入参直接返回基准目录
        if (lists.isEmpty()) return emptyList()
        // 核心逻辑：逐个列表收缩公共前缀
        var prefix = lists.first()
        for (segments in lists) {
            val count = minOf(prefix.size, segments.size)
            var i = 0
            while (i < count && prefix[i] == segments[i]) i++
            prefix = prefix.take(i)
        }
        return prefix
    }
}

/**
 * 骨架子项（目录条目或文件条目）
 */
internal sealed interface SpineEntry {

    /** 子项名称（目录名/文件名，真实子项合并去重时比对用） */
    val name: String
}

/**
 * 骨架目录条目：虚拟目录链中的一级目录
 *
 * @param name 目录名（路径最后一段，显示用）
 * @param path 目录完整路径（规范化：项目作用域=项目根相对路径；全局作用域=绝对路径）
 * @property bookmark 挂在该目录上的文件夹收藏（纯虚拟目录为 null）
 */
internal class SpineDirEntry(override val name: String, val path: String) : SpineEntry {
    var bookmark: Bookmark? = null
}

/**
 * 骨架文件条目：一个文件路径上的收藏聚合（FILE 收藏与 LINE 收藏合并共用）
 *
 * @param name 文件名（显示用）
 * @param path 文件完整路径（规范化同 [SpineDirEntry.path]）
 * @property fileBookmark 文件收藏（仅承载行收藏时为 null）
 * @property lineBookmarks 挂在该文件下的行收藏（保持列表排序出现顺序）
 */
internal class SpineFileEntry(override val name: String, val path: String) : SpineEntry {
    var fileBookmark: Bookmark? = null
    val lineBookmarks = mutableListOf<Bookmark>()
}

/**
 * 单个列表的文件系统骨架（1.3.0 PhpStorm 风格树）：
 * - 由列表内可见收藏的 filePath 计算公共祖先目录，自上而下生成虚拟目录链；
 * - 仅承载通向收藏项的路径（未收藏的真实内容在文件夹收藏展开时才懒加载，由树侧处理）；
 * - 同一文件上的 FILE 与 LINE 收藏合并进同一个 [SpineFileEntry]；
 * - 子项索引键为目录路径（"" 表示作用域基准目录：项目根 / 文件系统根）
 *
 * @property scope 所属作用域
 * @property listName 所属列表名（树侧骨架查找键组成部分）
 * @property commonAncestorPath 公共祖先目录路径（""=作用域基准目录；树顶层目录节点由此生成）
 * @param absolute 路径是否绝对路径语义（全局作用域=true）
 */
internal class FavoritesListSpine private constructor(
    val scope: FavoriteScope,
    val listName: String,
    val commonAncestorPath: String,
    private val absolute: Boolean
) {

    companion object {

        /**
         * 由可见收藏（已按列表排序方式排好）构建列表骨架
         *
         * @param scope 收藏所属作用域
         * @param listName 列表名
         * @param visibleBookmarks 过滤后的可见收藏（顺序=列表排序方式结果，骨架保持该顺序）
         * @return 列表文件系统骨架
         */
        fun build(scope: FavoriteScope, listName: String, visibleBookmarks: List<Bookmark>): FavoritesListSpine {
            // ////////////////  校验参数  start  /////////////////////////////////////////
            // 路径为空的脏数据直接忽略，避免拆段越界
            val bookmarks = visibleBookmarks.filter { it.filePath.isNotBlank() }
            // ////////////////  校验参数  end  ///////////////////////////////////////////

            // ////////////////  准备数据：收藏路径段与挂靠目录段  start  /////////////////
            val absolute = scope == FavoriteScope.GLOBAL
            val pathSegs = bookmarks.map { FavoritesTreePaths.segments(it.filePath) }
            // 文件/行收藏挂靠其父目录；文件夹收藏挂靠自身路径（该路径即目录节点）
            val dirSegs = pathSegs.mapIndexed { index, segments ->
                if (bookmarks[index].isFolderBookmark()) segments else segments.dropLast(1)
            }
            // ////////////////  准备数据  end  ///////////////////////////////////////////

            // ////////////////  核心逻辑：公共祖先 + 逐收藏建立目录链并挂载条目  start  //
            val common = FavoritesTreePaths.commonPrefix(dirSegs)
            val spine = FavoritesListSpine(scope, listName, FavoritesTreePaths.join(common, absolute), absolute)
            bookmarks.forEachIndexed { index, bookmark ->
                val segments = pathSegs[index]
                val targetDir = spine.ensureDirChain(common, dirSegs[index])
                when {
                    bookmark.isFolderBookmark() ->
                        spine.markDirBookmark(targetDir, bookmark)
                    bookmark.isFileBookmark() ->
                        spine.attachFile(targetDir, segments.last(), bookmark.filePath, bookmark, null)
                    else ->
                        spine.attachFile(targetDir, segments.last(), bookmark.filePath, null, bookmark)
                }
            }
            // ////////////////  核心逻辑  end  ///////////////////////////////////////////

            // ////////////////  返回  start  /////////////////////////////////////////////
            return spine
            // ////////////////  返回  end  ///////////////////////////////////////////////
        }
    }

    /** 目录路径 → 子项列表（保持收藏排序出现顺序） */
    private val childrenByDir = HashMap<String, MutableList<SpineEntry>>()

    /** 目录路径 → 该目录上的文件夹收藏 */
    private val dirBookmarks = HashMap<String, Bookmark>()

    /** 目录路径 → 骨架目录条目（用于回填收藏标记） */
    private val dirEntryByPath = HashMap<String, SpineDirEntry>()

    /**
     * 确保从公共祖先到目标目录的虚拟目录链存在，返回目标目录路径键
     * （仅创建公共祖先以下的目录，公共祖先由树侧作为顶层目录节点呈现）
     *
     * @param common 公共祖先路径段
     * @param targetDirSegs 目标目录路径段（公共前缀=common）
     * @return 目标目录路径键（""=基准目录）
     */
    private fun ensureDirChain(common: List<String>, targetDirSegs: List<String>): String {
        // ////////////////  核心逻辑：自上而下逐级确保目录条目存在  start  //////////////
        var parentPath = FavoritesTreePaths.join(common, absolute)
        var siblings = childrenByDir.getOrPut(parentPath) { mutableListOf() }
        for (i in common.size until targetDirSegs.size) {
            val segment = targetDirSegs[i]
            val existing = siblings.filterIsInstance<SpineDirEntry>().firstOrNull { it.name == segment }
            val entry = if (existing != null) {
                existing
            } else {
                SpineDirEntry(segment, FavoritesTreePaths.childPath(parentPath, segment, absolute)).also {
                    siblings.add(it)
                    dirEntryByPath[it.path] = it
                }
            }
            parentPath = entry.path
            siblings = childrenByDir.getOrPut(parentPath) { mutableListOf() }
        }
        // ////////////////  核心逻辑  end  ///////////////////////////////////////////
        return parentPath
    }

    /**
     * 在目录上标记文件夹收藏（目录条目回填标记 + 路径索引）
     *
     * @param dirPath 目录路径键
     * @param bookmark 文件夹收藏
     */
    private fun markDirBookmark(dirPath: String, bookmark: Bookmark) {
        dirBookmarks[dirPath] = bookmark
        dirEntryByPath[dirPath]?.bookmark = bookmark
    }

    /**
     * 挂载文件条目（FILE 收藏与 LINE 收藏同路径自动合并进同一 [SpineFileEntry]）
     *
     * @param dirPath 所在目录路径键
     * @param fileName 文件名
     * @param filePath 文件完整原始路径
     * @param fileBookmark 文件收藏（可为 null）
     * @param lineBookmark 行收藏（可为 null）
     */
    private fun attachFile(
        dirPath: String,
        fileName: String,
        filePath: String,
        fileBookmark: Bookmark?,
        lineBookmark: Bookmark?
    ) {
        // ////////////////  核心逻辑：同路径合并 / 新建文件条目  start  /////////////////
        val siblings = childrenByDir.getOrPut(dirPath) { mutableListOf() }
        val entry = siblings.filterIsInstance<SpineFileEntry>().firstOrNull { it.name == fileName }
            ?: SpineFileEntry(fileName, FavoritesTreePaths.join(FavoritesTreePaths.segments(filePath), absolute))
                .also { siblings.add(it) }
        // ////////////////  核心逻辑  end  ///////////////////////////////////////////

        // ////////////////  挂载收藏  start  //////////////////////////////////////////
        if (fileBookmark != null) entry.fileBookmark = fileBookmark
        if (lineBookmark != null) entry.lineBookmarks.add(lineBookmark)
        // ////////////////  挂载收藏  end  ////////////////////////////////////////////
    }

    /**
     * 目录路径键下的骨架子项（目录条目在前、文件条目在后，组内保持收藏排序出现顺序）
     *
     * @param dirPath 目录路径键（""=基准目录）
     * @return 子项列表（SpineDirEntry / SpineFileEntry）
     */
    fun childrenOf(dirPath: String): List<SpineEntry> {
        val all = childrenByDir[dirPath] ?: return emptyList()
        return all.filterIsInstance<SpineDirEntry>() + all.filterIsInstance<SpineFileEntry>()
    }

    /**
     * 目录路径键上的文件夹收藏（无则 null；顶层公共祖先节点也从此查询）
     *
     * @param dirPath 目录路径键
     * @return 文件夹收藏或 null
     */
    fun dirBookmarkAt(dirPath: String): Bookmark? = dirBookmarks[dirPath]
}
