package com.aogg.favorites.model

import com.intellij.openapi.project.Project
import java.io.File

/**
 * 收藏跨作用域转移结果
 *
 * @param success 是否转移成功
 * @param bookmark 转移成功时的新收藏（保留原 id/createdTime/description/type/lineNumber，路径/列表按目标作用域转换）
 * @param errorMessage 失败时的用户可读错误信息（UI 弹错误提示用）
 */
data class TransferResult(
    val success: Boolean,
    val bookmark: Bookmark? = null,
    val errorMessage: String? = null
) {
    companion object {
        /**
         * 构造成功结果
         */
        fun ok(bookmark: Bookmark): TransferResult = TransferResult(success = true, bookmark = bookmark)

        /**
         * 构造失败结果
         */
        fun fail(message: String): TransferResult = TransferResult(success = false, errorMessage = message)
    }
}

/**
 * 收藏跨作用域转移服务（1.2.0 新增）
 *
 * 转移 = 移动而非复制：原作用域删除、目标作用域新增，保留 id/createdTime/description/type/lineNumber。
 *
 * 路径转换规则：
 * - PROJECT→GLOBAL：相对路径 → 绝对路径（project.basePath + 相对路径）；
 * - GLOBAL→PROJECT：仅当绝对路径位于 basePath 之下才转相对路径，否则失败（UI 弹错误提示）。
 *
 * 助记符规则：目标作用域被占用时降级为匿名收藏（与"导入原生书签"策略一致）。
 *
 * 说明：本类为普通类（非平台 @Service），构造函数接收两个管理器，便于单元测试
 * 直接以 mock Project + 注入假存储的管理器实例化（平台服务无法脱离 IDE 容器构造）。
 *
 * @param project 当前项目（提供 basePath 做路径换算）
 * @param projectManager 项目作用域管理器
 * @param globalManager 全局作用域管理器
 */
class FavoritesTransferService(
    private val project: Project,
    private val projectManager: BookmarkManager,
    private val globalManager: GlobalBookmarkManager
) {

    companion object {
        /**
         * 获取当前项目的转移服务实例（无状态，按需创建）
         */
        @JvmStatic
        fun getInstance(project: Project): FavoritesTransferService =
            FavoritesTransferService(
                project,
                BookmarkManager.getInstance(project),
                GlobalBookmarkManager.getInstance()
            )
    }

    /**
     * 按作用域取对应管理器
     */
    private fun managerOf(scope: FavoriteScope) = when (scope) {
        FavoriteScope.PROJECT -> projectManager
        FavoriteScope.GLOBAL -> globalManager
    }

    /**
     * 转移收藏到另一作用域的指定列表
     *
     * @param bookmarkId 收藏ID
     * @param from 源作用域
     * @param to 目标作用域（与源相同时退化为同作用域 moveToList）
     * @param targetListName 目标列表名（目标作用域缺失该列表时兜底并入默认列表，绝不丢数据）
     * @return [TransferResult]：成功带新收藏；失败带用户可读错误信息
     */
    fun transferBookmark(
        bookmarkId: String,
        from: FavoriteScope,
        to: FavoriteScope,
        targetListName: String
    ): TransferResult {
        // ////////////////  校验参数  start  ////////////////////////////////////////////////
        if (from == to) {
            // 同作用域转移：直接走列表移动语义，避免无谓的删+建
            val moved = managerOf(from).moveToList(bookmarkId, targetListName)
            return if (moved) {
                managerOf(from).getBookmark(bookmarkId)?.let { TransferResult.ok(it) }
                    ?: TransferResult.fail("收藏不存在")
            } else {
                TransferResult.fail("收藏不存在或目标列表不存在")
            }
        }
        val source = managerOf(from)
        val target = managerOf(to)
        val bookmark = source.getBookmark(bookmarkId)
            ?: return TransferResult.fail("收藏不存在或已被删除")
        // ////////////////  校验参数  end  ////////////////////////////////////////////////

        // ////////////////  准备数据：路径换算  start  ////////////////////////////////////////////////
        val convertedPath: String? = when (to) {
            FavoriteScope.GLOBAL -> toAbsoluteForGlobal(bookmark.filePath)
            FavoriteScope.PROJECT -> toRelativeForProject(bookmark.filePath)
        }
        if (convertedPath == null) {
            return TransferResult.fail(
                "该收藏位于项目目录之外，无法转移到项目收藏（项目收藏只支持项目内路径）"
            )
        }
        // ////////////////  准备数据：路径换算  end  ////////////////////////////////////////////////

        // ////////////////  准备数据：目标列表兜底  start  ////////////////////////////////////////////////
        // UI 只会给出目标作用域已有的列表名；这里防御性兜底：列表缺失时并入默认列表，绝不丢收藏
        val finalListName = if (target.hasList(targetListName)) targetListName else Bookmark.DEFAULT_LIST_NAME
        // ////////////////  准备数据：目标列表兜底  end  ////////////////////////////////////////////////

        // ////////////////  准备数据：助记符占用检测（转移前判定，失败即降级为匿名）  start  ////////////
        val mnemonic = bookmark.mnemonic
        val downgradeToAnonymous = mnemonic != null && !target.isMnemonicAvailable(mnemonic)
        // ////////////////  准备数据：助记符占用检测  end  //////////////////////////////////////////////

        // ////////////////  核心逻辑：移动而非复制（先删源、再入目标）  start  ////////////////////////
        if (!source.removeBookmark(bookmarkId)) {
            return TransferResult.fail("收藏不存在或已被删除")
        }
        // 保留 id/createdTime/description/type/lineNumber；仅转换路径/列表/助记符
        val transferred = bookmark.copy(
            filePath = convertedPath,
            listName = finalListName,
            mnemonic = if (downgradeToAnonymous) null else mnemonic
        )
        val added = target.addBookmark(transferred)
        if (!added) {
            // 理论不可达（匿名收藏不会冲突）：兜底回滚，保证不丢数据
            source.addBookmark(bookmark)
            return TransferResult.fail("转移失败：目标作用域写入收藏失败")
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////////

        // ////////////////  返回  start  ////////////////////////////////////////////////
        return TransferResult.ok(transferred)
        // ////////////////  返回  end  ////////////////////////////////////////////////
    }

    /**
     * 项目→全局：相对路径转绝对路径（已是绝对路径则原样返回并统一分隔符）
     *
     * @return 绝对路径
     */
    private fun toAbsoluteForGlobal(path: String): String {
        // ////////////////  核心逻辑：相对路径拼项目根  start  ////////////////////////////////////////
        val absolutePath = if (File(path).isAbsolute) path else File(project.basePath ?: "", path).absolutePath
        // 统一使用正斜杠（与 VFS 路径风格一致，便于 gutter/跳转按绝对路径匹配）
        return absolutePath.replace('\\', '/')
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////////
    }

    /**
     * 全局→项目：绝对路径仅当位于 basePath 之下才转相对路径
     *
     * @return 相对路径；位于项目之外返回 null（调用方返回失败）
     */
    private fun toRelativeForProject(absolutePath: String): String? {
        // ////////////////  校验参数：项目根缺失无法换算  start  ////////////////////////////////////
        val basePath = project.basePath ?: return null
        // ////////////////  校验参数  end  ////////////////////////////////////////////////

        // ////////////////  核心逻辑：前缀判定（目录边界对齐，避免 /projX 误匹配 /proj）  start  //////
        val normalizedBase = basePath.replace('\\', '/').trimEnd('/')
        val normalizedPath = absolutePath.replace('\\', '/')
        val underBase = normalizedPath == normalizedBase ||
            normalizedPath.startsWith("$normalizedBase/")
        if (!underBase) return null
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////////

        // ////////////////  返回：去掉项目根前缀的相对路径  start  /////////////////////////////////
        return normalizedPath.removePrefix(normalizedBase).trimStart('/')
        // ////////////////  返回  end  ////////////////////////////////////////////////
    }
}
