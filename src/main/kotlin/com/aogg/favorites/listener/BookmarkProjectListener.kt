package com.aogg.favorites.listener

import com.aogg.favorites.helper.BookmarkRefreshHelper
import com.aogg.favorites.model.BookmarkManager
import com.aogg.favorites.model.SharedBookmarkManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent

/**
 * 项目级收藏监听器：
 * - 处理文件/文件夹删除：清理对应收藏（含子路径）
 * - 处理文件/文件夹重命名、移动：同步更新收藏的相对路径
 * - 1.7.0 共享收藏：删除/移动/重命名同步清理与更新【自己集合】内的共享收藏（绝对路径匹配，外来集合不动）
 *
 * 注意：项目收藏使用相对项目根目录的路径保存（Bookmark.filePath）；共享收藏存绝对路径。
 */
class BookmarkProjectListener(private val project: Project) : BulkFileListener {

    private val log = Logger.getInstance(BookmarkProjectListener::class.java)

    override fun after(events: List<VFileEvent>) {
        if (project.isDisposed) return

        val manager = BookmarkManager.getInstance(project)
        val sharedManager = SharedBookmarkManager.getInstance(project)
        var changed = false
        var removedCount = 0
        var updatedCount = 0

        for (event in events) {
            when (event) {
                is VFileDeleteEvent -> {
                    val rel = toRelativePath(event.path) ?: continue
                    val includeChildren = event.file.isDirectory
                    val removed = manager.removeBookmarksByPath(rel, includeChildren)
                    // 共享收藏（自己集合）：按绝对路径清理，外来集合不动
                    removedCount += sharedManager.removeBookmarksByPath(
                        event.path.replace('\\', '/'),
                        includeChildren
                    )
                    if (removed > 0) {
                        removedCount += removed
                    }
                    changed = true
                }

                is VFileMoveEvent -> {
                    val file = event.file
                    val oldAbs = joinPath(event.oldParent.path, file.name)
                    val newAbs = joinPath(event.newParent.path, file.name)
                    val updateResult = updateForMoveOrRename(file, oldAbs, newAbs, manager)
                    // 共享收藏（自己集合）：绝对路径随文件移动/重命名更新，外来集合不动
                    updatedCount += sharedManager.updateBookmarksPath(
                        oldAbs.replace('\\', '/'),
                        newAbs.replace('\\', '/'),
                        file.isDirectory
                    )
                    if (updateResult > 0) {
                        updatedCount += updateResult
                    }
                    changed = true
                }

                is VFilePropertyChangeEvent -> {
                    if (event.propertyName != VirtualFile.PROP_NAME) continue
                    val file = event.file
                    val oldName = event.oldValue as? String ?: continue
                    val newName = event.newValue as? String ?: continue
                    val parentPath = file.parent?.path ?: continue
                    val oldAbs = joinPath(parentPath, oldName)
                    val newAbs = joinPath(parentPath, newName)
                    val updateResult = updateForMoveOrRename(file, oldAbs, newAbs, manager)
                    // 共享收藏（自己集合）：绝对路径随重命名更新，外来集合不动
                    updatedCount += sharedManager.updateBookmarksPath(
                        oldAbs.replace('\\', '/'),
                        newAbs.replace('\\', '/'),
                        file.isDirectory
                    )
                    if (updateResult > 0) {
                        updatedCount += updateResult
                    }
                    changed = true
                }
            }
        }

        if (changed) {
            // 刷新工具窗口与编辑器行标记（LineMarkerProvider 依赖 Daemon 刷新）
            BookmarkRefreshHelper.refreshAfterChange(project)
            if (removedCount > 0 || updatedCount > 0) {
                log.info("收藏已同步：删除 $removedCount 条，更新 $updatedCount 条")
            }
        }
    }

    private fun updateForMoveOrRename(
        file: VirtualFile,
        oldAbsPath: String,
        newAbsPath: String,
        manager: BookmarkManager
    ): Int {
        val oldRel = toRelativePath(oldAbsPath)
        val newRel = toRelativePath(newAbsPath)

        // 从项目内移出：按删除处理
        if (oldRel != null && newRel == null) {
            return manager.removeBookmarksByPath(oldRel, file.isDirectory)
        }

        // 项目外移入：不自动创建收藏
        if (oldRel == null || newRel == null) return 0

        return manager.updateBookmarksPath(oldRel, newRel, file.isDirectory)
    }

    private fun toRelativePath(absolutePath: String): String? {
        val basePath = project.basePath ?: return null
        val normalizedBase = basePath.replace('\\', '/').trimEnd('/')
        val normalizedAbs = absolutePath.replace('\\', '/')
        if (!normalizedAbs.startsWith(normalizedBase)) return null
        return normalizedAbs.substring(normalizedBase.length).trimStart('/')
    }

    private fun joinPath(parent: String, child: String): String {
        val p = parent.trimEnd('/', '\\')
        val c = child.trimStart('/', '\\')
        return "$p/$c"
    }
}

