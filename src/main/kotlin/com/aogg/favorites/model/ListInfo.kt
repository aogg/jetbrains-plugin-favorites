package com.aogg.favorites.model

import java.io.Serializable
import java.util.UUID

/**
 * 收藏列表排序方式
 */
enum class SortType {

    /**
     * 按添加时间（默认）
     */
    ADDED,

    /**
     * 按描述
     */
    DESCRIPTION,

    /**
     * 按位置（文件路径 + 行号）
     */
    LOCATION
}

/**
 * 收藏列表元信息（1.5.0 起升级为可多层级嵌套的分组树节点）
 *
 * @param name 列表名称（作用域内唯一；[Bookmark.DEFAULT_LIST_NAME] 为默认列表，不可删除/重命名）
 * @param sortType 列表内收藏的排序方式（按列表记忆并持久化）
 * @param id 分组唯一标识（UUID；父子关系按 id 关联，重命名不影响层级）
 * @param parentId 父分组 id（""=顶级分组；默认列表「收藏」parentId 恒为 "" 且不可嵌套/移动/删除）
 */
data class ListInfo(
    val name: String,
    val sortType: SortType = SortType.ADDED,
    val id: String = UUID.randomUUID().toString(),
    val parentId: String = ""
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L

        /**
         * 顶级分组的 parentId 约定值
         */
        const val ROOT_PARENT_ID = ""

        /**
         * 分组树归一（纯函数，加载时调用，供单测）：
         * - 无 id（旧版数据/空串）的列表补 UUID；
         * - 默认列表「收藏」parentId 强制归顶级；
         * - parentId 指向自己、指向不存在的列表、或沿父链形成环 → 归顶级（切断环，绝不丢列表）。
         *
         * 输入输出按同序同长一一对应，调用方可按下标原地替换。
         *
         * @param lists 待归一的列表集合（含默认列表）
         * @return 归一后的新列表（同序同长，元素为 copy 出的新实例）
         */
        @JvmStatic
        fun normalizeTree(lists: List<ListInfo>): List<ListInfo> {
            if (lists.isEmpty()) return lists
            val withIds = lists.map { if (it.id.isNotBlank()) it else it.copy(id = UUID.randomUUID().toString()) }
            val byId = withIds.associateBy { it.id }
            return withIds.map { info ->
                val parentId = when {
                    // 默认列表强制顶级
                    info.name == Bookmark.DEFAULT_LIST_NAME -> ROOT_PARENT_ID
                    // 空 parentId 本身就是顶级
                    info.parentId.isBlank() -> ROOT_PARENT_ID
                    // 指向自己 / 指向不存在的列表 / 形成环 → 归顶级
                    info.parentId == info.id || byId[info.parentId] == null ||
                        formsCycle(byId, info.id) -> ROOT_PARENT_ID
                    else -> info.parentId
                }
                info.copy(parentId = parentId)
            }
        }

        /**
         * 从指定分组出发沿父链上行是否会回到该分组自身（环校验，纯函数，供单测与后续移动分组扩展）
         *
         * @param byId id → 列表映射（须已补全 id）
         * @param id 起始分组 id
         * @return true=父链成环（含直接指向自己）
         */
        @JvmStatic
        fun formsCycle(byId: Map<String, ListInfo>, id: String): Boolean = formsCycleInternal(byId, id)

        /**
         * 递归收集某分组的全部后代分组 id（不含自身；BFS + 已访问集，环数据下自动截断，纯函数供单测）
         *
         * @param lists 全部列表
         * @param rootId 根分组 id
         * @return 后代分组 id 集合（保持列表出现顺序；无后代返回空集）
         */
        @JvmStatic
        fun collectDescendantIds(lists: List<ListInfo>, rootId: String): Set<String> {
            val childrenByParent = lists.groupBy { it.parentId }
            val result = LinkedHashSet<String>()
            val queue = ArrayDeque(childrenByParent[rootId].orEmpty().map { it.id })
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                if (!result.add(id)) continue
                childrenByParent[id]?.forEach { queue.addLast(it.id) }
            }
            return result
        }

        /**
         * 环校验内部实现（沿父链上行，重复访问即成环）
         */
        private fun formsCycleInternal(byId: Map<String, ListInfo>, id: String): Boolean {
            var cursor = byId[id]?.parentId?.takeIf { it.isNotBlank() }?.let { byId[it] } ?: return false
            val visited = HashSet<String>().apply { add(id) }
            while (true) {
                if (!visited.add(cursor.id)) return true
                cursor = cursor.parentId.takeIf { it.isNotBlank() }?.let { byId[it] } ?: return false
            }
        }
    }
}

/**
 * 列表级联删除预览（删除前确认框展示用）
 *
 * @param groupNames 将被删除的分组名（含自身，父先子后按存储顺序）
 * @param movedBookmarkCount 被删分组内的收藏总数（删除时全部并入默认列表）
 */
data class CascadeDeletePreview(
    val groupNames: List<String>,
    val movedBookmarkCount: Int
) {
    /** 连删的子分组数（不含自身） */
    val subGroupCount: Int get() = (groupNames.size - 1).coerceAtLeast(0)
}

/**
 * 列表级联删除结果
 *
 * @param success 是否删除成功（列表不存在/默认列表返回 false）
 * @param deletedGroupNames 实际删除的分组名（含自身）
 * @param movedBookmarkCount 并入默认列表的收藏数
 */
data class ListDeleteResult(
    val success: Boolean = false,
    val deletedGroupNames: List<String> = emptyList(),
    val movedBookmarkCount: Int = 0
)
