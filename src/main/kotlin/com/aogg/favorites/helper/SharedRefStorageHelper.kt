package com.aogg.favorites.helper

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * 共享收藏来源项目配置持久化存储助手（项目级，1.7.0 新增订阅引用，1.7.1 改版为来源项目配置）
 *
 * 记录当前项目「共享收藏」配置了哪些来源项目（来源项目根路径列表，按配置顺序）：
 * - 配置后收藏树的「共享收藏」根下出现「共享自：来源项目」节点，
 *   只读展示该来源项目的项目收藏（实时读打开项目的 BookmarkManager，未打开则解析其 .idea/favorites.xml）；
 * - 单向只读：仅消费方保存本配置，绝不写来源项目的任何文件；
 * - 数据随项目走且不漫游（RoamingType.DISABLED），持久化于项目配置目录 favorites.shared.refs.xml。
 *
 * 兼容迁移（1.7.0 → 1.7.1）：旧版 refs 存共享集合 id（16 位 hex），
 * 加载后首次读取时由 [com.aogg.favorites.model.SharedBookmarkStore] 把旧 id 尽力映射回
 * 来源项目路径转入 [State.sourceProjects] 并清空 refs（映射不到的丢弃）。
 */
@State(
    name = "FavoritesSharedRefs",
    storages = [Storage(value = "favorites.shared.refs.xml", roamingType = RoamingType.DISABLED)]
)
@Service(Service.Level.PROJECT)
class SharedRefStorageHelper : PersistentStateComponent<SharedRefStorageHelper.State> {

    /**
     * 来源项目配置状态
     */
    class State {

        /**
         * 1.7.0 旧订阅模型遗留：已订阅的共享集合 id 列表（保持订阅顺序）
         * 1.7.1 起不再写入，仅作为旧数据迁移来源（迁移后清空）
         */
        var refs: MutableList<String> = mutableListOf()

        /**
         * 已配置的来源项目根路径列表（1.7.1 新增，规范化正斜杠、去尾部斜杠，保持配置顺序）
         */
        var sourceProjects: MutableList<String> = mutableListOf()
    }

    private var state = State()

    override fun getState(): State {
        return state
    }

    override fun loadState(state: State) {
        // 直接引用替换：refs/sourceProjects 均为简单 MutableList<String>，整体替换安全
        // 与既有存储助手一致：copyBean 原地合并，保持 state 对象引用稳定
        com.intellij.util.xmlb.XmlSerializerUtil.copyBean(state, this.state)
    }

    companion object {
        /**
         * 获取项目实例
         */
        @JvmStatic
        fun getInstance(project: Project): SharedRefStorageHelper {
            return project.service()
        }
    }
}
