package com.aogg.favorites.nativebookmarks

import com.aogg.favorites.helper.BookmarkRefreshHelper
import com.aogg.favorites.model.BookmarkManager
import com.aogg.favorites.settings.FavoritesProjectImportState
import com.intellij.ide.bookmark.BookmarkType
import com.intellij.ide.bookmark.BookmarksManager
import com.intellij.ide.bookmark.FileBookmark
import com.intellij.ide.bookmark.LineBookmark
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import org.jdom.input.SAXBuilder
import java.io.File

/**
 * 导入结果
 *
 * @param imported 实际导入数量
 * @param createdLists 新建的收藏列表数量
 * @param skippedDuplicates 跳过的重复条目数量
 */
data class ImportOutcome(val imported: Int, val createdLists: Int, val skippedDuplicates: Int)

/**
 * 原生书签导入服务（项目级）
 *
 * 双通道（尽力而为，见 docs/v2-design.md 2.5）：
 * - 通道一（首选）：运行时 API com.intellij.ide.bookmark.BookmarksManager 读取原生书签；
 *   整个通道 runCatching，API 不可用/异常/空结果时转通道二。
 * - 通道二（兜底）：容错解析 .idea/bookmarks.xml（组件 BookmarksManager；233 起新版实际
 *   持久化在 .idea/workspace.xml 同名组件，一并容错），再尽力解析旧版 .idea/favorites.xml。
 *
 * 所有原生 API 访问均判空 + runCatching，失败仅记日志，不影响插件其它功能。
 */
@Service(Service.Level.PROJECT)
class NativeBookmarkImportService(private val project: Project) {

    companion object {
        private val log = Logger.getInstance(NativeBookmarkImportService::class.java)

        private val BOOKMARK_COMPONENT_FILES = listOf("bookmarks.xml", "workspace.xml")

        /**
         * 获取项目级实例
         */
        @JvmStatic
        fun getInstance(project: Project): NativeBookmarkImportService =
            project.getService(NativeBookmarkImportService::class.java)

        /**
         * 执行导入并弹出结果通知（动作与首次引导共用入口）
         */
        @JvmStatic
        fun importAndNotify(project: Project): ImportOutcome? {
            if (project.isDisposed) return null
            val outcome = runCatching { getInstance(project).importAll() }
                .onFailure { log.warn("导入原生书签失败", it) }
                .getOrNull() ?: return null

            // 成功导入（含无新增）后置位导入标记，避免重复引导
            runCatching { FavoritesProjectImportState.getInstance(project).state.importedNative = true }

            val message = "已导入 ${outcome.imported} 条原生书签" +
                "（新建 ${outcome.createdLists} 个列表，跳过重复 ${outcome.skippedDuplicates} 条）"
            val notification = Notification(
                "Favorites.Notifications",
                "导入原生书签",
                message,
                NotificationType.INFORMATION
            )
            notification.addAction(
                NotificationAction.createSimple("查看") { openFavoritesToolWindow(project) }
            )
            Notifications.Bus.notify(notification, project)
            return outcome
        }

        /**
         * 打开收藏工具窗口并刷新
         */
        @JvmStatic
        fun openFavoritesToolWindow(project: Project) {
            if (project.isDisposed) return
            runCatching {
                com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                    .getToolWindow("Favorites")?.show(null)
                BookmarkRefreshHelper.refreshAfterChange(project)
            }.onFailure { log.warn("打开收藏工具窗口失败", it) }
        }
    }

    // ==================== 收集（双通道） ====================

    /**
     * 收集全部原生书签（通道一失败/为空时转通道二）
     */
    fun collectNativeItems(): List<NativeBookmarkItem> {
        val fromApi = runCatching { collectFromRuntimeApi() }
            .onFailure { log.info("原生书签 API 通道不可用，转 XML 通道", it) }
            .getOrNull()
        if (!fromApi.isNullOrEmpty()) return fromApi
        return collectFromXmlFiles()
    }

    /**
     * 轻量统计原生书签数量（首次引导检测用，避免拖慢启动）
     */
    fun countNativeBookmarks(): Int {
        val apiCount = runCatching {
            BookmarksManager.getInstance(project)?.bookmarks?.size ?: 0
        }.getOrNull()
        if (apiCount != null && apiCount > 0) return apiCount
        return runCatching { collectFromXmlFiles().size }
            .onFailure { log.info("原生书签 XML 通道统计失败", it) }
            .getOrDefault(0)
    }

    /**
     * 通道一：运行时 API 读取原生书签
     */
    private fun collectFromRuntimeApi(): List<NativeBookmarkItem>? {
        val manager = BookmarksManager.getInstance(project) ?: return null
        val bookmarks = manager.bookmarks
        if (bookmarks.isEmpty()) return null

        val baseDir = project.baseDir
        val items = ArrayList<NativeBookmarkItem>(bookmarks.size)
        for (bookmark in bookmarks) {
            runCatching {
                when (bookmark) {
                    is LineBookmark -> addRuntimeItem(
                        items, manager, bookmark, bookmark.file, bookmark.line, false, baseDir
                    )
                    is FileBookmark -> addRuntimeItem(
                        items, manager, bookmark, bookmark.file, -1, bookmark.file.isDirectory, baseDir
                    )
                    else -> {
                        // 非文件/行书签（如模块书签）暂不支持导入
                    }
                }
            }.onFailure { log.warn("读取单条原生书签失败，已跳过", it) }
        }
        return items
    }

    /**
     * 由运行时 API 的一条原生书签生成中间模型并追加
     */
    private fun addRuntimeItem(
        items: MutableList<NativeBookmarkItem>,
        manager: BookmarksManager,
        bookmark: com.intellij.ide.bookmark.Bookmark,
        file: com.intellij.openapi.vfs.VirtualFile,
        line: Int,
        isDirectory: Boolean,
        baseDir: com.intellij.openapi.vfs.VirtualFile?
    ) {
        // 助记符：getType 返回 null/DEFAULT 均视为无助记符
        val mnemonic = runCatching { manager.getType(bookmark) }.getOrNull()
            ?.takeIf { it != BookmarkType.DEFAULT }?.mnemonic
            ?.takeIf { it in '0'..'9' || it in 'A'..'Z' }

        // 所属列表：优先非默认组；只有默认组时按无列表处理（进"原生书签"列表）
        val groups = runCatching { manager.getGroups(bookmark) }.getOrNull().orEmpty()
        val listName = groups.firstOrNull { runCatching { !it.isDefault }.getOrDefault(false) }
            ?.let { group -> runCatching { group.name }.getOrNull() }
        val description = groups.firstNotNullOfOrNull { group ->
            runCatching { group.getDescription(bookmark) }.getOrNull()
        }

        items += NativeBookmarkItem(
            relativePath = resolveRelativeToProject(file, baseDir),
            line = line,
            mnemonic = mnemonic,
            listName = listName,
            description = description,
            isDirectory = isDirectory
        )
    }

    /**
     * 通道二：容错解析 .idea 下 bookmarks.xml / workspace.xml（组件 BookmarksManager）
     * 与旧版 favorites.xml（尽力而为，失败静默）
     */
    private fun collectFromXmlFiles(): List<NativeBookmarkItem> {
        val basePath = project.basePath ?: return emptyList()
        val ideaDir = File(basePath, ".idea")
        val result = ArrayList<NativeBookmarkItem>()

        for (fileName in BOOKMARK_COMPONENT_FILES) {
            val file = File(ideaDir, fileName)
            if (!file.isFile) continue
            runCatching {
                val root = SAXBuilder().build(file).rootElement
                root.children
                    .filter { it.name == "component" && it.getAttributeValue("name") == "BookmarksManager" }
                    .forEach { component ->
                        result += NativeBookmarkXmlParser.parseBookmarksXml(component, basePath)
                    }
            }.onFailure { log.info("解析原生书签文件失败：$fileName", it) }
        }

        // 旧版收藏列表（PhpStorm ≤2021.2），尽力而为、失败静默
        val favoritesFile = File(ideaDir, "favorites.xml")
        if (favoritesFile.isFile) {
            runCatching {
                val root = SAXBuilder().build(favoritesFile).rootElement
                root.children
                    .filter { it.name == "component" }
                    .forEach { component ->
                        result += NativeBookmarkXmlParser.parseLegacyFavoritesXml(component, basePath)
                    }
            }.onFailure { log.info("解析旧版收藏列表失败：favorites.xml", it) }
        }
        return result
    }

    /**
     * 绝对路径 → 项目相对路径；项目外路径原样保留（仍可跳转），不会返回 null
     */
    private fun resolveRelativeToProject(
        file: com.intellij.openapi.vfs.VirtualFile,
        baseDir: com.intellij.openapi.vfs.VirtualFile?
    ): String {
        if (baseDir == null) return file.path
        return VfsUtil.getRelativePath(file, baseDir, '/') ?: file.path
    }

    // ==================== 导入（映射/去重/增量合并） ====================

    /**
     * 执行导入（可重复执行：与现有收藏按 (相对路径+行号)/路径 去重，增量合并）
     */
    fun importAll(): ImportOutcome {
        val manager = BookmarkManager.getInstance(project)
        val existing = manager.getAllBookmarks()

        val plan = NativeBookmarkImportPlanner.plan(
            items = collectNativeItems(),
            existingKeys = NativeBookmarkImportPlanner.existingKeysOf(existing),
            usedMnemonics = existing.mapNotNull { it.mnemonic }.toSet(),
            existingListNames = manager.getLists().map { it.name }.toSet()
        )

        plan.listsToCreate.forEach { manager.createList(it) }

        var added = 0
        plan.bookmarks.forEach { bookmark ->
            if (manager.addBookmark(bookmark)) {
                added++
            }
        }

        // 刷新工具窗口与 gutter 图标
        BookmarkRefreshHelper.refreshAfterChange(project)

        val skipped = plan.skippedDuplicates + (plan.bookmarks.size - added)
        return ImportOutcome(added, plan.listsToCreate.size, skipped)
    }
}
