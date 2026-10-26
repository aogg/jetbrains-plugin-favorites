package com.aogg.favorites.ui

import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon
import javax.swing.UIManager

/**
 * 字符绘制图标（1.5.x 图标全量字符化方案的核心实现）
 *
 * 为什么用字符绘制（重要，勿删）：
 * - 收藏树历史 4 次图标加固（换常量/直接实现渲染器/绘制级兜底/运行时取证）在用户环境下全部无效，
 *   判断为 AllIcons / SVG / 文件类型图标的整条平台加载链被接管（第三方主题/图标插件可将其清空）；
 * - 字符绘制不走任何图标加载链：paintIcon 里直接 drawString，主题/第三方插件无法清空字符，
 *   因此从根上绕开图标加载链，保证任何环境下树节点必有可见图标；
 * - 着色参数为 null 时绘制时取组件 foreground：树选中态/未选中态由渲染器设置前景色，
 *   字符颜色随前景色自适应，深浅色主题与选中高亮均无需写死色值。
 *
 * 不可变对象：text/size/tint 构造后不再变化，可安全跨 EDT 与缓存共享；
 * 高频渲染路径统一通过 [of] 取缓存实例，避免每帧重复分配。
 *
 * @param text 绘制的字符内容（emoji / 字母 / 数字等）
 * @param size 图标边长（宽高一致，单位像素，默认 16 与 IDE 树图标规格一致）
 * @param tint 固定着色（null=绘制时取组件 foreground，自适应主题选中态配色）
 */
class CharIcon private constructor(
    private val text: String,
    private val size: Int,
    private val tint: Color?
) : Icon {

    /**
     * 绘制字符图标：
     * 校验/准备画布 → 开启抗锯齿 → 准备字体与颜色 → 按 size 居中 drawString → dispose
     *
     * @param c 承载组件（取其字体与前景色；可为 null，走默认值）
     * @param g 图形上下文
     * @param x 图标左上角 x（由绘制框架传入）
     * @param y 图标左上角 y（由绘制框架传入）
     */
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        // ////////////////  准备画布：派生独立 Graphics2D（不影响调用方状态，用完 dispose）  start  //
        val g2 = g.create() as? Graphics2D ?: return
        // ////////////////  准备画布  end  //////////////////////////////////////////////////////////

        try {
            // ////////////////  开启抗锯齿：图形 + 文本双开，保证 emoji/字符边缘平滑  start  ///////
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            // ////////////////  开启抗锯齿  end  ////////////////////////////////////////////////////

            // ////////////////  准备数据：字体（随组件字体派生）与颜色（tint 优先，否则组件前景色）  /
            // 字号取 size-2：在 size 边长内留出字形上下留白，配合字体度量垂直居中，避免裁边
            val baseFont: Font = c?.font ?: UIManager.getFont("Label.font") ?: Font(Font.DIALOG, Font.PLAIN, size)
            g2.font = baseFont.deriveFont((size - 2).coerceAtLeast(8).toFloat())
            g2.color = tint
                ?: c?.foreground
                ?: UIManager.getColor("Label.foreground")
                ?: Color.BLACK
            // ////////////////  准备数据  end  //////////////////////////////////////////////////////

            // ////////////////  核心逻辑：按字体度量在 size×size 内水平/垂直居中绘制  start  ////////
            val metrics = g2.fontMetrics
            val textX = x + (size - metrics.stringWidth(text)) / 2
            val textY = y + (size - metrics.height) / 2 + metrics.ascent
            g2.drawString(text, textX, textY)
            // ////////////////  核心逻辑  end  //////////////////////////////////////////////////////
        } finally {
            // ////////////////  逻辑后：释放派生画布  //////////////////////////////////////////////
            g2.dispose()
        }
    }

    /** 图标宽 = size */
    override fun getIconWidth(): Int = size

    /** 图标高 = size */
    override fun getIconHeight(): Int = size

    companion object {

        /** 渲染高频调用（树每行每帧都取图标），按 key 缓存单例避免重复分配 */
        private val CACHE = ConcurrentHashMap<CharIconKey, CharIcon>()

        /**
         * 缓存工厂：按 text+size(+tint) 取共享实例（CacheKey 为不可变数据类，线程安全）
         *
         * @param text 绘制的字符内容，如 CharIcon.of("📁")
         * @param size 图标边长（默认 16）
         * @param tint 固定着色（null=绘制时取组件 foreground）
         * @return 缓存内的字符图标实例
         */
        @JvmStatic
        @JvmOverloads
        fun of(text: String, size: Int = 16, tint: Color? = null): CharIcon =
            CACHE.computeIfAbsent(CharIconKey(text, size, tint)) { CharIcon(text, size, tint) }
    }

    /**
     * 缓存 key（text+size+tint 三元组，不可变）：
     * tint 参与相等性——同字符同尺寸不同着色必须区分缓存
     */
    private data class CharIconKey(val text: String, val size: Int, val tint: Color?)
}
