package com.aogg.favorites.helper

/**
 * 行号区图标「行锚点」判定（纯函数，便于单测与复用）
 *
 * 背景（1.8.1）：平台的 `LineMarkerProvider` 会为文件里的**每个** PSI 元素（含叶子与复合元素）
 * 调用一次 `getLineMarkerInfo`，同一个行号区位置只应产生一个图标，因此需要判断
 * 「这个叶子是不是它所在行的唯一锚点」。
 *
 * 1.8.0 及以前的判定是「元素起点 == 行首」，只对列 0 的叶子成立；而真实的 PSI 里
 * 缩进行的行首偏移上往往没有叶子（见 [isIndentedLineAnchor] 的实测说明），
 * 导致所有缩进行永远不显示图标（用户反馈的主因）。
 */
object BookmarkGutterAnchors {

    /**
     * 行首位置上的叶子（含行首空白叶子）就是该行的锚点
     *
     * 注意：**不能**排除 [com.intellij.psi.PsiWhiteSpace]——「换行 + 缩进」常常是同一个
     * 空白 token，它的起点落在上一行，本身只能是上一行的锚点；但某些文件的某行行首
     * 恰好是一个从行首开始的空白 token，此时它才是该行锚点。
     *
     * @param isLeaf 是否叶子元素（平台要求：只为叶子元素建 marker）
     * @param textLength 元素文本长度（零长叶子如 Python 缩进标记要跳过）
     * @param elementOffset 元素起始偏移
     * @param lineStartOffset 元素所在行的行首偏移
     * @return 该叶子是否是所在行的锚点
     */
    fun isLineAnchor(isLeaf: Boolean, textLength: Int, elementOffset: Int, lineStartOffset: Int): Boolean =
        isLeaf && textLength > 0 && elementOffset == lineStartOffset

    /**
     * 缩进行的兜底锚点：行首没有独立叶子时，取该行第一个叶子
     *
     * 实测依据（PhpStorm 2023.3 的 PHP 词法器）：`<?php\n    $a = 1;` 经词法分析得到
     * `<?php`[0,5) → 空白[5,10)="\n    " → `$a`[10,12)……，空白 token 把「换行 + 缩进」
     * 合并成一个 token，起点 5 是上一行的换行符位置，而第 2 行的行首偏移是 6。
     * 于是第 2 行的行首偏移 6 上**没有任何叶子**，[isLineAnchor] 恒为 false，
     * 缩进行永远拿不到图标（即用户 1.8.0 反馈的现象）。
     *
     * 该行第一个叶子（缩进后的第一个代码 token）天然满足：起点在行首之后，且它的
     * **前一个叶子**起点在行首之前（前一个叶子跨过了行首）。这个条件每行至多成立一次，
     * 所以不会产生重复图标。
     *
     * @param isLeaf 是否叶子元素
     * @param textLength 元素文本长度
     * @param elementOffset 元素起始偏移
     * @param lineStartOffset 元素所在行的行首偏移
     * @param prevLeafOffset 前一个叶子的起始偏移（没有前一个叶子时传 -1）
     * @return 该叶子是否是缩进行的锚点
     */
    fun isIndentedLineAnchor(
        isLeaf: Boolean,
        textLength: Int,
        elementOffset: Int,
        lineStartOffset: Int,
        prevLeafOffset: Int
    ): Boolean = isLeaf && textLength > 0 && elementOffset > lineStartOffset && prevLeafOffset < lineStartOffset

    /**
     * 该叶子是否是所在行的唯一锚点（组合「行首叶子」与「缩进行兜底」两条规则）
     *
     * 唯一性：若某叶子起点正好等于行首，则它之后的叶子前一个叶子起点必然 >= 行首，兜底规则不成立；
     * 若行首没有叶子，则该行第一个叶子的前一个叶子必然跨过行首，之后叶子的前一个叶子起点必然 >= 行首。
     * 因此同一行至多命中一个叶子，不会出现重复图标。
     *
     * @param isLeaf 是否叶子元素
     * @param textLength 元素文本长度
     * @param elementOffset 元素起始偏移
     * @param lineStartOffset 元素所在行的行首偏移
     * @param prevLeafOffset 前一个叶子的起始偏移（没有前一个叶子时传 -1）
     * @return 该叶子是否是所在行的锚点
     */
    fun isAnchorOfItsLine(
        isLeaf: Boolean,
        textLength: Int,
        elementOffset: Int,
        lineStartOffset: Int,
        prevLeafOffset: Int
    ): Boolean =
        isLineAnchor(isLeaf, textLength, elementOffset, lineStartOffset) ||
            isIndentedLineAnchor(isLeaf, textLength, elementOffset, lineStartOffset, prevLeafOffset)

    /**
     * 叶子元素自身的快捷判定（供 [BookmarkGutterHelper] 调用，避免把 PSI 判空逻辑散落在调用方）
     *
     * @param hasChild 是否有子元素（有子元素即复合元素，不是叶子）
     * @param textLength 元素文本长度
     * @param elementOffset 元素起始偏移
     * @param lineStartOffset 元素所在行的行首偏移
     * @param prevLeafOffset 前一个叶子的起始偏移（没有前一个叶子时传 -1）
     * @return 该元素是否是所在行的锚点
     */
    fun isAnchorOfItsLineForElement(
        hasChild: Boolean,
        textLength: Int,
        elementOffset: Int,
        lineStartOffset: Int,
        prevLeafOffset: Int
    ): Boolean = isAnchorOfItsLine(!hasChild, textLength, elementOffset, lineStartOffset, prevLeafOffset)
}
