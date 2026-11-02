package com.aogg.favorites.helper

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * 共享收藏持久化存储助手（应用级，1.7.0 新增）
 *
 * 与项目级/全局级单作用域存储不同，共享收藏按「集合」（来源项目）分片存放：
 * - key：集合 id（[com.aogg.favorites.model.SharedBookmarkStore.collectionIdFor]，由来源项目路径哈希得到）；
 * - value：[SharedCollectionData]，content 直接复用项目级 [BookmarkStorageHelper.State]
 *   （BookmarkData/ListData 同一结构，不复制第二份），各集合内容互不干扰。
 *
 * 持久化位置：IDE 配置目录 favorites.shared.xml（随应用走，跨项目窗口/跨 IDE 进程共享）。
 */
@State(
    name = "FavoritesSharedBookmarks",
    storages = [Storage(value = "favorites.shared.xml")]
)
@Service(Service.Level.APP)
class SharedBookmarkStorageHelper : PersistentStateComponent<SharedBookmarkStorageHelper.State> {

    /**
     * 共享仓库状态：集合 id → 集合数据
     */
    class State {

        /**
         * 全部共享集合（key=集合 id）
         */
        var collections: MutableMap<String, SharedCollectionData> = mutableMapOf()
    }

    /**
     * 单个共享集合的数据切片
     *
     * @param id 集合 id（来源项目路径哈希前 16 位 hex）
     * @param ownerProjectName 来源项目显示名（管理对话框/树节点展示用）
     * @param ownerProjectPath 来源项目根路径（规范化正斜杠；订阅方 tooltip 展示用）
     * @param updatedTime 集合内容最近一次写入的时间戳（管理对话框「更新时间」列展示用）
     * @param content 集合内容（复用项目级 State 结构：收藏 + 分组元信息；路径存绝对路径）
     */
    class SharedCollectionData(
        var id: String = "",
        var ownerProjectName: String = "",
        var ownerProjectPath: String = "",
        var updatedTime: Long = 0L,
        var content: BookmarkStorageHelper.State = BookmarkStorageHelper.State()
    )

    private var state = State()

    override fun getState(): State {
        return state
    }

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    /**
     * 清空全部共享集合（测试/重置场景用；正常业务走按集合 id 增删）
     */
    fun clearAll() {
        state.collections.clear()
    }

    companion object {
        /**
         * 获取应用级实例
         */
        @JvmStatic
        fun getInstance(): SharedBookmarkStorageHelper {
            return ApplicationManager.getApplication().getService(SharedBookmarkStorageHelper::class.java)
        }
    }
}
