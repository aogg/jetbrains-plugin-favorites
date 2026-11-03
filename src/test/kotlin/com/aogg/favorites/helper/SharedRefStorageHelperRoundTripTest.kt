package com.aogg.favorites.helper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SharedRefStorageHelper（项目级共享收藏来源项目配置存储）State 写入读出往返单元测试
 *
 * 说明：直接实例化存储助手（无参构造，脱离 IDE 服务），验证
 * - refs（1.7.0 旧订阅遗留字段）：loadState/getState 往返一致（按订阅顺序保序）；
 * - sourceProjects（1.7.1 新增来源项目配置字段）：与 refs 混合状态往返一致（按配置顺序保序）；
 * - 加载后两个列表仍可继续变更（对应 store 的 subscribe/unsubscribe 与 setConfiguredSourceProjects 覆盖写）。
 */
class SharedRefStorageHelperRoundTripTest {

    @Test
    fun `refs load get roundtrip should keep subscription order`() {
        // ////////////////  准备数据：多订阅引用（按订阅顺序）  start  ///////////////////
        val state = SharedRefStorageHelper.State()
        state.refs.addAll(listOf("collectionB", "collectionA", "collectionC"))
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：loadState → getState 往返  start  /////////////////
        val helper = SharedRefStorageHelper()
        helper.loadState(state)
        val restored = helper.getState()
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////

        // ////////////////  返回：顺序与内容一致  start  ////////////////////////////////
        assertEquals(listOf("collectionB", "collectionA", "collectionC"), restored.refs, "订阅引用应按原顺序往返一致")
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    @Test
    fun `mixed refs and source projects should roundtrip both fields`() {
        // ////////////////  准备数据：refs + sourceProjects 混合状态（1.7.0 旧数据 + 1.7.1 新配置）  /
        val state = SharedRefStorageHelper.State()
        state.refs.addAll(listOf("legacyCollectionB", "legacyCollectionA"))
        state.sourceProjects.addAll(listOf("/work/sourceB", "/work/sourceA"))
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：loadState → getState 往返  start  /////////////////
        val helper = SharedRefStorageHelper()
        helper.loadState(state)
        val restored = helper.getState()
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////

        // ////////////////  返回：两个字段互不串扰、各自保序  start  ////////////////////
        assertEquals(listOf("legacyCollectionB", "legacyCollectionA"), restored.refs, "旧订阅 refs 应按原顺序往返一致")
        assertEquals(
            listOf("/work/sourceB", "/work/sourceA"),
            restored.sourceProjects,
            "来源项目配置应按配置顺序往返一致"
        )
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    @Test
    fun `source projects only state should roundtrip without touching refs`() {
        // 仅配置来源项目（迁移完成后项目的典型状态）：refs 保持空、sourceProjects 往返一致
        val state = SharedRefStorageHelper.State()
        state.sourceProjects.addAll(listOf("/only/source", "/another/source/"))
        val helper = SharedRefStorageHelper()
        helper.loadState(state)

        val restored = helper.getState()
        assertTrue(restored.refs.isEmpty(), "仅配置来源项目时 refs 应保持为空")
        assertEquals(listOf("/only/source", "/another/source/"), restored.sourceProjects, "来源项目配置应原样往返")
    }

    @Test
    fun `empty state should roundtrip to empty refs and source projects`() {
        // 空 State 往返：refs 与 sourceProjects 均保持空列表（未订阅过/未配置过任何内容的项目）
        val helper = SharedRefStorageHelper()
        helper.loadState(SharedRefStorageHelper.State())
        assertTrue(helper.getState().refs.isEmpty(), "空订阅状态应往返为空列表")
        assertTrue(helper.getState().sourceProjects.isEmpty(), "空来源项目配置应往返为空列表")
    }

    @Test
    fun `refs should stay mutable after load for legacy subscribe unsubscribe`() {
        // 加载后引用列表仍可继续变更（1.7.0 遗留 subscribe/unsubscribe 的存储语义）
        val helper = SharedRefStorageHelper()
        val initial = SharedRefStorageHelper.State()
        initial.refs.add("collectionA")
        helper.loadState(initial)

        val refs = helper.getState().refs
        refs.add("collectionB")
        assertEquals(listOf("collectionA", "collectionB"), helper.getState().refs, "订阅后应在状态中可见")

        refs.remove("collectionA")
        assertEquals(listOf("collectionB"), helper.getState().refs, "取消订阅后应从状态中移除")
    }

    @Test
    fun `source projects should stay mutable after load for overwrite config`() {
        // 加载后来源项目配置仍可整体覆盖变更（对应 setConfiguredSourceProjects 的 clear+addAll 语义）
        val helper = SharedRefStorageHelper()
        val initial = SharedRefStorageHelper.State()
        initial.sourceProjects.addAll(listOf("/old/source"))
        helper.loadState(initial)

        val sourceProjects = helper.getState().sourceProjects
        sourceProjects.clear()
        sourceProjects.addAll(listOf("/new/sourceA", "/new/sourceB"))
        assertEquals(listOf("/new/sourceA", "/new/sourceB"), helper.getState().sourceProjects, "整体覆盖后应只保留新配置")
    }
}
