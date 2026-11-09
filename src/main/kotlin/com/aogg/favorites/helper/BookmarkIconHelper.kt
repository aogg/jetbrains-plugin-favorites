package com.aogg.favorites.helper

import com.aogg.favorites.model.BookmarkType
import com.aogg.favorites.model.FavoriteScope
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon
import kotlin.math.max

/**
 * 收藏图标管理助手
 * 提供匿名收藏、助记符收藏、作用域（1.2.0）、类型分组（1.2.0）、文件类型图标（1.3.0）
 *
 * 图标选型说明（基于 PhpStorm 2023.3 实测可用常量）：
 * - 全局作用域：AllIcons.Nodes.Palette 在 2023.3 不存在，改用 AllIcons.General.Web（地球图标，语义最接近"全局"）；
 * - 文件类型组：AllIcons.Nodes.FileText 在 2023.3 不存在，改用 AllIcons.FileTypes.Text（文档图标）；
 * - 树中文件节点：1.3.0 起改用 IDE 文件类型图标（.php/.bat/.sql 等按扩展名自然区分）。
 *
 * 1.8.0：助记符图标从「所有助记符都返回同一个原生书签图标」改为复合图标（[MnemonicBookmarkIcon]），
 * 行号区一眼能看出是 0-9 / A-Z 里的哪一个，匿名收藏仍用原生书签图标。
 */
object BookmarkIconHelper {

    /**
     * 匿名收藏图标（使用 IntelliJ 内置图标）
     */
    val ANONYMOUS_ICON: Icon = AllIcons.Gutter.Bookmark

    /**
     * 助记符图标缓存（字符 → 复合图标）：daemon 每轮行标记都会重复取图标，
     * 缓存后同一字符恒返回同一实例（LineMarkerInfo 缓存图标时也安全，不会无限增长）
     */
    private val MNEMONIC_ICONS: ConcurrentHashMap<Char, Icon> = ConcurrentHashMap()

    /**
     * 获取助记符收藏图标（1.8.0：书签底图 + 居中字符）
     *
     * @param mnemonic 助记符（0-9 或 A-Z，小写会归一为大写；非法字符退回匿名收藏图标）
     * @return 复合图标（同一字符复用同一实例）
     */
    fun getMnemonicIcon(mnemonic: Char): Icon {
        // ////////////////  校验参数：非法助记符退回匿名收藏图标，不抛异常  start  ////////
        val normalized = mnemonic.uppercaseChar()
        if (normalized !in '0'..'9' && normalized !in 'A'..'Z') return ANONYMOUS_ICON
        // ////////////////  校验参数  end  //////////////////////////////////////////

        // ////////////////  返回：按字符缓存复用（并发安全，同一字符只构造一次）  start  ////
        return MNEMONIC_ICONS.computeIfAbsent(normalized) { MnemonicBookmarkIcon(it) }
        // ////////////////  返回  end  //////////////////////////////////////////////
    }

    /**
     * 获取助记符文本（用于显示在图标上）
     *
     * @param mnemonic 助记符
     * @return 显示文本（如 "[1]" 或 "[A]"）
     */
    fun getMnemonicText(mnemonic: Char): String {
        return "[$mnemonic]"
    }

    /**
     * 获取作用域节点图标（树第一层 / 弹窗分组用）
     *
     * @param scope 作用域（项目=项目图标；共享=分享图标；全局=地球图标）
     */
    fun getScopeIcon(scope: FavoriteScope): Icon = when (scope) {
        FavoriteScope.PROJECT -> AllIcons.Nodes.Project
        FavoriteScope.SHARED -> AllIcons.Actions.Share
        FavoriteScope.GLOBAL -> AllIcons.General.Web
    }

    /**
     * 获取收藏类型分组图标（树第三层类型组 / 收藏节点按类型用）
     *
     * @param type 收藏类型（行=书签图标；文件=文档图标；文件夹=文件夹图标）
     */
    fun getTypeIcon(type: BookmarkType): Icon = when (type) {
        BookmarkType.LINE -> AllIcons.Gutter.Bookmark
        BookmarkType.FILE -> AllIcons.FileTypes.Text
        BookmarkType.FOLDER -> AllIcons.Nodes.Folder
    }

    /**
     * 获取树中文件节点图标（1.3.0 PhpStorm 风格树）：
     * 优先使用有效 VirtualFile 的文件类型图标（不同扩展名 .php/.bat/.sql 等自然区分）；
     * 离线/文件不存在时按文件名推断文件类型图标；再兜底任意文件图标
     *
     * @param virtualFile 已解析的虚拟文件（可为 null=离线/不存在）
     * @param fileName 文件名（兜底推断用）
     * @return 文件图标
     */
    fun getFileIcon(virtualFile: VirtualFile?, fileName: String): Icon {
        // ////////////////  优先：有效 VirtualFile 的文件类型图标  start  ////////////////
        if (virtualFile != null && virtualFile.isValid) {
            try {
                return virtualFile.fileType.icon
            } catch (e: Exception) {
                // 文件类型解析异常时走文件名推断兜底
            }
        }
        // ////////////////  优先  end  //////////////////////////////////////////////////

        // ////////////////  兜底一：按文件名推断文件类型图标  start  //////////////////////
        val fileTypeByFileName = try {
            FileTypeManager.getInstance().getFileTypeByFileName(fileName)
        } catch (e: Exception) {
            null
        }
        if (fileTypeByFileName != null && fileTypeByFileName !== UnknownFileType.INSTANCE) {
            return fileTypeByFileName.icon
        }
        // ////////////////  兜底一  end  //////////////////////////////////////////////////

        // ////////////////  兜底二：任意文件图标（2023.3 常量为 Any_type）  start  ///////////
        return AllIcons.FileTypes.Any_type
        // ////////////////  兜底二  end  //////////////////////////////////////////////////
    }
}

/**
 * 助记符复合图标（1.8.0）：原生书签底图 + 居中助记符字符
 *
 * 设计要点：
 * - 尺寸跟随底图（16x16 逻辑像素），不写死尺寸，兼容不同 IDE 图标尺寸；
 * - 字符绘制在 Graphics2D 副本上（create / dispose 在 finally 里配对），不污染调用方 Graphics；
 * - 打开抗锯齿与文字抗锯齿：HiDPI 下按逻辑坐标绘制由平台缩放，字符始终清晰；
 * - 白字 + 上下左右各 1px 深色描边：浅色/深色主题、彩色书签底图上都能看清；
 * - 不可变（只持有字符与构造期算好的字体），可安全被 LineMarkerInfo / 树渲染器缓存复用。
 *
 * @param mnemonic 已归一为大写的助记符（0-9 / A-Z）
 */
private class MnemonicBookmarkIcon(private val mnemonic: Char) : Icon {

    /** 底图（原生书签图标，与匿名收藏保持一致的外观） */
    private val baseIcon: Icon = AllIcons.Gutter.Bookmark

    /** 待绘制字符（恒为一位） */
    private val text: String = mnemonic.toString()

    /** 字符字体（构造期按图标宽度算好，绘制期不再新建对象） */
    private val textFont: Font = resolveFont()

    override fun getIconWidth(): Int = baseIcon.iconWidth

    override fun getIconHeight(): Int = baseIcon.iconHeight

    /**
     * 绘制：先画书签底图，再叠加带描边的白色字符
     */
    override fun paintIcon(component: Component?, g: Graphics, x: Int, y: Int) {
        // ////////////////  校验参数：拿不到 Graphics2D 副本就不画（避免污染调用方 Graphics）  start
        val g2 = g.create() as? Graphics2D ?: return
        // ////////////////  校验参数  end  //////////////////////////////////////////

        try {
            // ////////////////  绘制准备：图形抗锯齿 + 文字抗锯齿（HiDPI 下字符清晰）  start  ////
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            // ////////////////  绘制准备  end  //////////////////////////////////////////

            // ////////////////  核心逻辑一：先画书签底图  start  /////////////////////////////
            baseIcon.paintIcon(component, g2, x, y)
            // ////////////////  核心逻辑一  end  //////////////////////////////////////////

            // ////////////////  核心逻辑二：字符居中（先深色描边，再白色填充）  start  ////////
            g2.font = textFont
            val metrics = g2.fontMetrics
            val textX = x + (iconWidth - metrics.stringWidth(text)) / 2
            // 垂直居中：居中偏移 + ascent 得到基线
            val textY = y + (iconHeight - metrics.height) / 2 + metrics.ascent

            // 描边：上下左右各偏移 1px 画深色，保证任意主题下字符都有清晰边界
            g2.color = OUTLINE_COLOR
            g2.drawString(text, textX - 1, textY)
            g2.drawString(text, textX + 1, textY)
            g2.drawString(text, textX, textY - 1)
            g2.drawString(text, textX, textY + 1)

            // 填充：白色字符（书签底图为深色/彩色，白字对比度最高）
            g2.color = FILL_COLOR
            g2.drawString(text, textX, textY)
            // ////////////////  核心逻辑二  end  //////////////////////////////////////////
        } finally {
            // 副本必须释放，避免 Graphics 资源泄漏（LineMarker 每行都会绘制）
            g2.dispose()
        }
    }

    override fun toString(): String = "MnemonicBookmarkIcon[$mnemonic]"

    /**
     * 解析字符字体：字号跟随图标高度等比换算，宽字符（M/W）放不下时逐步缩小到能放下为止
     *
     * @return 构造期确定的粗体字体
     */
    private fun resolveFont(): Font {
        // ////////////////  准备数据：初始字号按图标高度换算（16px 图标 → 10px 字号）  start  ///
        var size = max(MIN_FONT_SIZE, iconHeight * FONT_SIZE_NUM / FONT_SIZE_DEN)
        val maxTextWidth = max(MIN_TEXT_WIDTH, iconWidth - CHAR_PADDING * 2)
        // ////////////////  准备数据  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：宽字符逐步缩字号，直到字符能放进书签主体宽度  start  ////
        while (size > MIN_FONT_SIZE && textWidthOf(size) > maxTextWidth) {
            size--
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////

        // ////////////////  返回：粗体无衬线（16px 图标上最易辨认）  start  ///////////////
        return Font(Font.SANS_SERIF, Font.BOLD, size)
        // ////////////////  返回  end  ///////////////////////////////////////////////
    }

    /**
     * 量出指定字号下字符的像素宽度（离屏 1x1 图像取 FontMetrics，构造期调用一次，线程安全）
     *
     * @param fontSize 字号
     * @return 字符像素宽度
     */
    private fun textWidthOf(fontSize: Int): Int {
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, fontSize)
            return graphics.fontMetrics.stringWidth(text)
        } finally {
            graphics.dispose()
        }
    }

    companion object {
        /** 字号换算分子（图标高度 × 分子 / 分母） */
        private const val FONT_SIZE_NUM = 10

        /** 字号换算分母 */
        private const val FONT_SIZE_DEN = 16

        /** 最小字号（再小就不可读） */
        private const val MIN_FONT_SIZE = 7

        /** 字符可占的最小宽度（兜底，避免图标过窄时缩字号死循环） */
        private const val MIN_TEXT_WIDTH = 6

        /** 字符左右留白（保证描边不被裁掉） */
        private const val CHAR_PADDING = 1

        /** 描边颜色（半透明黑：深色主题下不突兀，浅色主题下足够清晰） */
        private val OUTLINE_COLOR = Color(0, 0, 0, 200)

        /** 字符填充颜色（白色，与深色书签底图对比度最高） */
        private val FILL_COLOR = Color.WHITE
    }
}
