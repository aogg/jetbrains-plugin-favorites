# 收藏插件实现计划

## 项目概述

基于 PhpStorm 原生收藏功能，实现一个功能相同的插件。该插件将提供代码行、文件和文件夹的收藏功能，支持匿名收藏和助记符收藏，并提供统一的工具窗口进行管理。

## 功能需求分析

### 核心功能

1. **匿名收藏（Anonymous Bookmarks）**
   - 无标识符，数量无限制
   - 在编辑器行号区域显示书签图标
   - 支持代码行、文件、文件夹收藏

2. **助记符收藏（Mnemonic Bookmarks）**
   - 使用数字（0-9）或字母（A-Z）作为标识符
   - 在编辑器行号区域显示带框的标识符（如 `[1]`、`[A]`）
   - 每个标识符只能使用一次，最多 36 个
   - 支持代码行、文件、文件夹收藏

3. **收藏管理工具窗口**
   - 显示所有收藏（代码行、文件、文件夹）
   - 支持双击跳转
   - 支持编辑、删除操作
   - 支持重命名助记符收藏的描述

4. **快速访问功能**
   - 弹出窗口显示所有收藏，支持快速选择跳转
   - 支持通过快捷键直接跳转到助记符收藏

5. **右键菜单集成**
   - 编辑器行号区域右键菜单
   - 项目工具窗口文件/文件夹右键菜单
   - 编辑器标签页右键菜单

## 快捷键设计（避免冲突）

### 原 PhpStorm 快捷键
- `F11` - 添加/移除匿名收藏
- `Ctrl+F11` - 添加/移除助记符收藏
- `Alt+2` - 打开/关闭 Bookmarks 工具窗口
- `Shift+F11` - 显示行收藏弹出窗口
- `Ctrl + [0-9/A-Z]` - 快速跳转到助记符收藏

### 本插件快捷键方案（避免冲突）

为了避免与 PhpStorm 原生收藏功能冲突，本插件使用以下快捷键：

| 功能 | 快捷键 | 说明 |
|------|--------|------|
| 添加/移除匿名收藏 | `Ctrl+Alt+F11` | 避免与 F11 冲突 |
| 添加/移除助记符收藏 | `Ctrl+Alt+Shift+F11` | 避免与 Ctrl+F11 冲突 |
| 打开/关闭收藏工具窗口 | `Ctrl+Alt+2` | 避免与 Alt+2 冲突 |
| 显示收藏弹出窗口 | `Ctrl+Alt+Shift+B` | 避免与 Shift+F11 冲突 |
| 快速跳转到助记符收藏 | `Ctrl+Alt+[0-9/A-Z]` | 避免与 Ctrl+[0-9/A-Z] 冲突 |

**注意**：所有快捷键都可通过 IDE 设置进行自定义，用户可以根据需要调整。

## 技术实现方案

### 1. 项目结构

```
src/
├── main/
│   ├── kotlin/
│   │   └── com/
│   │       └── aogg/
│   │           └── favorites/
│   │               ├── action/              # 动作类
│   │               │   ├── AddAnonymousBookmarkAction.kt
│   │               │   ├── AddMnemonicBookmarkAction.kt
│   │               │   ├── ShowBookmarksPopupAction.kt
│   │               │   ├── JumpToMnemonicBookmarkAction.kt
│   │               │   └── ToggleBookmarksToolWindowAction.kt
│   │               ├── model/                # 数据模型
│   │               │   ├── Bookmark.kt
│   │               │   ├── BookmarkType.kt
│   │               │   └── BookmarkManager.kt
│   │               ├── ui/                   # UI 组件
│   │               │   ├── BookmarksToolWindow.kt
│   │               │   ├── BookmarksTree.kt
│   │               │   ├── BookmarkPopup.kt
│   │               │   └── MnemonicSelectorDialog.kt
│   │               ├── helper/              # 工具类
│   │               │   ├── BookmarkIconHelper.kt
│   │               │   ├── BookmarkStorageHelper.kt
│   │               │   └── BookmarkGutterHelper.kt
│   │               ├── listener/             # 监听器
│   │               │   ├── BookmarkEditorListener.kt
│   │               │   └── BookmarkProjectListener.kt
│   │               └── FavoritesPlugin.kt    # 插件主类
│   └── resources/
│       ├── META-INF/
│       │   └── plugin.xml                  # 插件配置
│       └── icons/                          # 图标资源
│           ├── bookmark.svg
│           └── mnemonic.svg
```

### 2. 核心组件实现

#### 2.1 数据模型

**Bookmark.kt**
- 收藏项数据模型
- 包含类型（代码行/文件/文件夹）、位置信息、标识符、描述等

**BookmarkType.kt**
- 枚举类型：LINE（代码行）、FILE（文件）、FOLDER（文件夹）

**BookmarkManager.kt**
- 收藏管理器（单例）
- 负责收藏的增删改查
- 负责持久化存储
- 负责助记符冲突检测

#### 2.2 UI 组件

**BookmarksToolWindow.kt**
- 工具窗口实现
- 使用 Tree 组件展示收藏列表
- 支持分类显示（按类型、按助记符）

**BookmarksTree.kt**
- 自定义 Tree 组件
- 支持双击跳转
- 支持右键菜单（编辑、删除）

**BookmarkPopup.kt**
- 快速访问弹出窗口
- 使用 ListPopup 实现
- 支持键盘导航和搜索

**MnemonicSelectorDialog.kt**
- 助记符选择对话框
- 显示可用和已用的标识符
- 支持输入描述信息

#### 2.3 动作类

**AddAnonymousBookmarkAction.kt**
- 添加匿名收藏的动作
- 支持编辑器、项目窗口、标签页上下文

**AddMnemonicBookmarkAction.kt**
- 添加助记符收藏的动作
- 弹出助记符选择对话框
- 处理标识符冲突

**ShowBookmarksPopupAction.kt**
- 显示收藏弹出窗口的动作
- 支持过滤和搜索

**JumpToMnemonicBookmarkAction.kt**
- 跳转到助记符收藏的动作
- 动态注册 36 个快捷键（0-9, A-Z）

**ToggleBookmarksToolWindowAction.kt**
- 切换工具窗口显示的动作

#### 2.4 工具类

**BookmarkIconHelper.kt**
- 图标管理
- 提供匿名收藏和助记符收藏的图标

**BookmarkStorageHelper.kt**
- 持久化存储
- 使用 ComponentState 保存到项目配置
- 支持序列化和反序列化

**BookmarkGutterHelper.kt**
- 编辑器行号区域图标显示
- 使用 LineMarkerProvider 实现
- 处理图标点击事件

#### 2.5 监听器

**BookmarkEditorListener.kt**
- 编辑器事件监听
- 处理文件关闭时的收藏清理
- 处理文件重命名/移动时的收藏更新

**BookmarkProjectListener.kt**
- 项目事件监听
- 处理文件/文件夹删除时的收藏清理
- 处理项目关闭时的收藏保存

### 3. 持久化存储方案

- 使用 `ComponentState` 接口实现持久化
- 存储位置：`.idea/favorites.xml`（项目级别）
- 存储内容：
  - 匿名收藏列表
  - 助记符收藏映射（标识符 -> 收藏项）
  - 收藏的描述信息

### 4. 编辑器集成

- 使用 `LineMarkerProvider` 在行号区域显示图标
- 使用 `EditorGutterComponentEx` 处理图标点击
- 监听编辑器选择变化，更新收藏状态

### 5. 右键菜单集成

- 在 `plugin.xml` 中注册 `EditorPopupMenu` 和 `ProjectViewPopupMenu`
- 添加 "Add Bookmark" 和 "Add Mnemonic Bookmark" 菜单项
- 支持多选文件/文件夹批量添加收藏

## 实现步骤

### 阶段一：基础框架（1-2 天）

1. ✅ 创建项目结构
2. ✅ 配置 `plugin.xml` 和 `build.gradle.kts`
3. ✅ 实现插件主类 `FavoritesPlugin`
4. ✅ 实现基础数据模型 `Bookmark`、`BookmarkType`
5. ✅ 实现 `BookmarkManager` 单例

### 阶段二：核心功能（3-4 天）

6. ✅ 实现 `BookmarkStorageHelper` 持久化
7. ✅ 实现 `AddAnonymousBookmarkAction`
8. ✅ 实现 `AddMnemonicBookmarkAction`
9. ✅ 实现 `MnemonicSelectorDialog`
10. ✅ 实现 `BookmarkGutterHelper` 编辑器图标显示
11. ✅ 实现编辑器右键菜单集成

### 阶段三：工具窗口（2-3 天）

12. ✅ 实现 `BookmarksToolWindow`
13. ✅ 实现 `BookmarksTree`
14. ✅ 实现双击跳转功能
15. ✅ 实现右键菜单（编辑、删除）
16. ✅ 实现 `ToggleBookmarksToolWindowAction`

### 阶段四：快速访问（2 天）

17. ✅ 实现 `BookmarkPopup`
18. ✅ 实现 `ShowBookmarksPopupAction`
19. ✅ 实现 `JumpToMnemonicBookmarkAction`（36 个快捷键）

### 阶段五：项目窗口集成（1-2 天）

20. ✅ 实现项目窗口右键菜单集成
21. ✅ 实现文件/文件夹收藏功能
22. ✅ 实现批量添加收藏功能

### 阶段六：编辑器标签集成（1 天）

23. ✅ 实现编辑器标签页右键菜单集成
24. ✅ 实现标签页收藏功能

### 阶段七：监听器和优化（2 天）

25. ✅ 实现 `BookmarkEditorListener`
26. ✅ 实现 `BookmarkProjectListener`
27. ✅ 处理文件删除/重命名/移动场景
28. ✅ 性能优化和缓存机制

### 阶段八：测试和文档（2-3 天）

29. ✅ 单元测试
30. ✅ 集成测试
31. ✅ 用户文档编写
32. ✅ 快捷键冲突检查
33. ✅ 最终测试和修复

## 技术要点

### 1. IntelliJ Platform API 使用

- **LineMarkerProvider**: 在编辑器行号区域显示图标
- **ToolWindow**: 创建工具窗口
- **AnAction**: 创建动作类
- **ComponentState**: 持久化存储
- **Project**: 项目上下文管理
- **VirtualFile**: 文件和文件夹操作
- **Document**: 文档和行号操作

### 2. 图标和 UI

- 使用 SVG 图标（支持高 DPI）
- 图标大小：16x16 像素
- 使用 IntelliJ 主题系统，支持深色/浅色主题

### 3. 性能考虑

- 收藏列表使用缓存机制
- 大量收藏时使用虚拟列表（VirtualList）
- 延迟加载文件信息
- 异步处理文件操作

### 4. 错误处理

- 文件不存在时的处理
- 文件被删除时的清理
- 项目关闭时的状态保存
- 无效收藏的自动清理

## 测试计划

### 功能测试

1. 添加/删除匿名收藏
2. 添加/删除助记符收藏
3. 助记符冲突检测
4. 工具窗口显示和操作
5. 快速访问弹出窗口
6. 快捷键跳转
7. 文件/文件夹收藏
8. 批量操作
9. 持久化存储和恢复

### 边界测试

1. 36 个助记符全部使用
2. 大量匿名收藏（100+）
3. 文件删除后的收藏清理
4. 文件重命名后的收藏更新
5. 项目关闭和重新打开
6. 多项目切换

### 兼容性测试

1. PhpStorm 2023.3+
2. 不同操作系统（Windows、macOS、Linux）
3. 不同主题（浅色、深色）
4. 不同字体大小设置

## 注意事项

1. **快捷键冲突**：所有快捷键都使用 `Ctrl+Alt` 前缀，避免与原生功能冲突
2. **数据持久化**：收藏数据保存在项目配置中，不提交到版本控制
3. **性能优化**：大量收藏时注意性能，使用缓存和延迟加载
4. **用户体验**：保持与原生收藏功能类似的交互体验
5. **错误处理**：妥善处理文件不存在、项目关闭等异常情况
6. **国际化**：所有 UI 文本使用中文，但代码结构支持后续国际化

## 后续优化方向

1. 支持收藏分组/分类
2. 支持收藏导入/导出
3. 支持收藏搜索和过滤
4. 支持收藏统计和分析
5. 支持收藏同步（如果有多设备需求）
6. 支持收藏分享（团队协作场景）

## 参考资料

- [IntelliJ Platform SDK Documentation](https://plugins.jetbrains.com/docs/intellij/welcome.html)
- [Line Marker Provider](https://plugins.jetbrains.com/docs/intellij/line-marker-provider.html)
- [Tool Windows](https://plugins.jetbrains.com/docs/intellij/tool-windows.html)
- [Actions](https://plugins.jetbrains.com/docs/intellij/basic-action-system.html)
- [Persistent State Components](https://plugins.jetbrains.com/docs/intellij/persisting-state-of-components.html)

---

*计划创建时间：2025年1月*
*预计完成时间：15-20 个工作日*
*适用于 PhpStorm 2023.3 及以上版本*
