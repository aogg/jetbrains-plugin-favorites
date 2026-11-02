package com.aogg.favorites.helper

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * 共享收藏引用（订阅）持久化存储助手（项目级，1.7.0 新增）
 *
 * 记录当前项目订阅了哪些共享集合（集合 id 列表，按订阅顺序）：
 * - 订阅后收藏树的「共享收藏」根下出现「共享自：来源项目」集合节点（外来集合只读）；
 * - 取消引用后该集合在本项目不再可见（来源项目自己的集合始终可见）；
 * - 数据随项目走且不漫游（RoamingType.DISABLED），持久化于项目配置目录 favorites.shared.refs.xml。
 */
@State(
    name = "FavoritesSharedRefs",
    storages = [Storage(value = "favorites.shared.refs.xml", roamingType = RoamingType.DISABLED)]
)
@Service(Service.Level.PROJECT)
class SharedRefStorageHelper : PersistentStateComponent<SharedRefStorageHelper.State> {

    /**
     * 引用状态
     */
    class State {

        /**
         * 已订阅的共享集合 id 列表（保持订阅顺序）
         */
        var refs: MutableList<String> = mutableListOf()
    }

    private var state = State()

    override fun getState(): State {
        return state
    }

    override fun loadState(state: State) {
        // 直接引用替换：refs 为简单 MutableList<String>，整体替换安全
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
