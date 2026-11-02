package com.aogg.favorites.helper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SharedRefStorageHelper（项目级共享收藏订阅引用存储）State 写入读出往返单元测试（1.7.0 新增）
 *
 * 说明：直接实例化存储助手（无参构造，脱离 IDE 服务），验证订阅引用列表
 * loadState/getState 往返一致（按订阅顺序保序），以及加载后继续变更的可见性。
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
    fun `empty refs state should roundtrip to empty`() {
        // 空 State 往返：refs 保持空列表（未订阅过任何集合的项目）
        val helper = SharedRefStorageHelper()
        helper.loadState(SharedRefStorageHelper.State())
        assertTrue(helper.getState().refs.isEmpty(), "空订阅状态应往返为空列表")
    }

    @Test
    fun `refs should stay mutable after load for subscribe unsubscribe`() {
        // 加载后引用列表仍可继续变更（对应 store 的 subscribe/unsubscribe 往返语义）
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
}
