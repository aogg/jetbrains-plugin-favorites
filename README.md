# JetBrains Plugin - Favorites

收藏插件 - 为 PhpStorm 提供代码行、文件和文件夹的收藏功能，参照 PhpStorm 原生 Bookmarks 提供列表（Lists）分组能力，行为尽量与原生保持一致，但通过独立插件实现。

## 功能特性

- **收藏列表（Lists）**：参照 PhpStorm 原生 Bookmarks 的列表分组，支持新建/重命名/删除列表、收藏跨列表移动；默认列表"收藏"不可删除，删除列表时其收藏自动并入默认列表
- **匿名收藏**：无限制数量的收藏，在编辑器行号区域显示书签图标
- **助记符收藏**：使用数字（0-9）或字母（A-Z）作为标识符，最多36个；助记符在项目内全局唯一（跨列表），跳转快捷键按助记符跨列表查找
- **收藏管理工具窗口**：列表/收藏两级树 + 实时过滤 + 排序 + 速度搜索
- **快速访问**：弹出窗口快速跳转到收藏位置
- **导入原生书签**：一键导入 PhpStorm 原生书签（运行时 API + .idea XML 容错解析双通道，自动去重、增量合并，助记符随迁）
- **隐藏原生书签入口**：默认接管 F11/Ctrl+F11/Shift+F11/Alt+2/Ctrl+0-9 等原生键位并隐藏原生 Bookmarks 工具窗口（可在设置中关闭并完整恢复）
- **多入口支持**：编辑器右键、行号区右键、项目视图右键、编辑器标签页右键均有"收藏"子菜单
- **旧数据迁移**：升级后旧 favorites.xml 中无列表信息的收藏自动归入默认列表，不丢数据

## 使用说明

### 支持的收藏类型

- **匿名收藏**：
  - 无标识符，数量不限
  - 推荐用于临时标记、审查点、调试观察点等
- **助记符收藏**：
  - 使用数字 `0-9` 和字母 `A-Z` 作为标识符（最多 36 个，项目内全局唯一）
  - 推荐用于常驻、需要快速跳转的位置

### 收藏列表

- 新建列表：工具窗口右键 → "新建列表"
- 重命名列表：右键列表（或列表中的收藏）→ "重命名列表"（默认列表"收藏"不可重命名）
- 删除列表：右键列表 → "删除列表"，其中的收藏自动并入默认列表，**不会丢失数据**
- 移动收藏：右键收藏 → "移动到列表" → 选择目标列表
- 排序：工具窗口右上角排序下拉（按添加顺序 / 按描述 / 按位置），**按列表记忆并持久化**

### 快捷键（默认键位）

| 功能 | 默认键位 |
|---|---|
| 添加/移除匿名收藏（编辑器上下文） | `Ctrl+Alt+F11` |
| 添加/移除助记符收藏（编辑器上下文） | `Ctrl+Alt+Shift+F11` |
| 显示/隐藏收藏工具窗口 | `Alt+2` |
| 显示收藏弹出列表 | `Ctrl+Alt+Shift+B` |
| 跳转到助记符收藏 [0-9, A-Z] | `Ctrl+Alt+0-9` / `Ctrl+Alt+A-Z` |
| 工具窗口内：跳转 | `Enter` / 双击 |
| 工具窗口内：编辑描述 | `F2` |
| 工具窗口内：删除 | `Del` |

> 说明：默认配置下，PhpStorm 原生书签的以下键位由本插件**接管**（原生动作 id 不变，用户自定义键位映射保留）：
>
> | 原生键位 | 原生动作 id | 接管后行为 |
> |---|---|---|
> | `F11` | `ToggleBookmark` | 添加/移除匿名收藏 |
> | `Ctrl+F11` | `ToggleBookmarkWithMnemonic` | 添加/移除助记符收藏 |
> | `Shift+F11` | `ShowBookmarks` | 收藏弹出列表 |
> | `Alt+2` | `ActivateBookmarksToolWindow` | 收藏工具窗口开关 |
> | `Ctrl+0-9`（存在时含 `Ctrl+A-Z`） | `GotoBookmark0-9`（`GotoBookmarkA-Z`） | 跳转到对应助记符收藏 |
>
> 同时隐藏未接管的残余原生动作（`ToggleBookmark0-9`/`ToggleBookmarkA-Z` 等）与原生 `Bookmarks` 工具窗口。
> **默认开启**，可在 `Settings → Tools → Favorites` 关闭"隐藏 PhpStorm 原生书签入口"，关闭后原生动作与工具窗口立即恢复。
> 所有键位仍可在 IDE `Settings | Keymap` 中自行修改。

### 导入原生书签

- **入口**：收藏工具窗口工具栏"导入原生书签"按钮，或右键"收藏"子菜单 → "导入原生书签"
- **双通道读取**（尽力而为）：
  - 通道一（首选）：运行时 `BookmarksManager` API 读取原生书签（url/行号/助记符/所属列表）
  - 通道二（兜底）：容错解析 `.idea/bookmarks.xml` / `.idea/workspace.xml`（组件 `BookmarksManager`）与旧版 `.idea/favorites.xml` 收藏列表
- **导入规则**：
  - 行书签 → 行收藏；文件/文件夹书签 → 对应收藏
  - 原生列表名存在则同名创建收藏列表，否则进"原生书签"列表
  - 原生助记符随迁；已被现有收藏占用时降级为匿名收藏
  - 与现有收藏按（相对路径+行号）/路径去重，**可重复执行（增量合并），不产生重复**
- **结果通知**：`已导入 N 条原生书签（新建 M 个列表，跳过重复 K 条）`，点击[查看]打开收藏工具窗口
- **首次引导**：打开项目检测到原生书签且未导入过时，弹出通知 `检测到 N 条 PhpStorm 原生书签，可一键导入到本插件`，可选[立即导入]或[不再提示]；可在设置页用"重置导入提示"重新触发

### 隐藏原生书签入口

- **默认开启**：接管上表所列原生键位（动作 id 不变，键位映射保留），隐藏 `ToggleBookmark0-9`/`ToggleBookmarkA-Z`/`ShowTypeBookmarks` 等残余原生动作、原生 `Bookmarks` 动作组与原生 `Bookmarks` 工具窗口（含"被显示就再藏"的兜底监听）
- **关闭方式**：`Settings → Tools → Favorites` 取消勾选"隐藏 PhpStorm 原生书签入口"并 Apply —— 原生动作、动作组与工具窗口立即恢复
- 隐藏为尽力而为：任何一步失败仅记录日志，不影响插件其它功能

### 入口与场景

- **编辑器 / 行号区（Gutter）/ 项目视图 / 编辑器标签页右键**：
  - 均有"收藏"子菜单（菜单置底），内含"添加匿名收藏 / 添加助记符收藏"
  - 编辑器上下文 → 行收藏；项目视图/标签页 → 文件/文件夹收藏；无有效上下文时菜单项自动隐藏
- **项目视图（Project Tool Window）**：支持多选批量收藏
- **收藏工具窗口 `Favorites`**（默认停靠在左侧）：
  - 列表/收藏两级树，列表节点显示收藏数徽标，如 `工作 (3)`
  - 顶部搜索框实时过滤（按描述/文件名/列表名），支持 `TreeSpeedSearch` 输入即搜
  - 工具栏：添加收藏、添加助记符、编辑描述、删除、全部展开/折叠
  - 右键菜单：跳转 / 编辑描述 / 设置助记符 / 移动到列表▸ / 新建列表 / 重命名列表 / 删除列表 / 删除收藏
  - 交互：`Enter`/双击=跳转（行→编辑器定位；文件夹→Project 视图选中）；`F2`=编辑描述；`Del`=删除；选中行收藏时预览打开文件但不抢焦点
- **助记符选择对话框**：`0-9/A-Z` 网格按钮，已占用项置灰禁用，可输入描述
- **设置页**：`Settings → Tools → Favorites`
  - ☑ 隐藏 PhpStorm 原生书签入口（默认勾选，apply 立即生效/恢复）
  - 按钮"重置导入提示"：清空已导入/不再提示标记，重新触发首次引导

### 收藏管理

- **收藏工具窗口 `Favorites`**：统一按列表管理所有收藏（行 / 文件 / 文件夹）
- **快速访问弹出 `收藏列表`**：使用 `Ctrl+Alt+Shift+B` 打开，支持键盘导航和输入过滤

## 开发状态

### ✅ 阶段一 ~ 八：核心功能与集成（已完成）

- 收藏数据模型与管理器、持久化存储与加载
- 编辑器、项目视图、标签页右键菜单集成
- 工具窗口与弹出列表、监听器与性能优化
- 单元测试与文档

### ✅ v1.1.0 阶段A：入口修复与列表重构（已完成）

- 修复 action 误写在 `<extensions>` 内导致安装后无任何操作入口的问题（全部移入 `<actions>`，新增"收藏"右键子菜单 ×4）
- 收藏列表（List）模型：新建/重命名/删除/移动/按列表排序，助记符跨列表唯一，旧数据自动迁移进默认列表
- 工具窗口重构：ToolbarDecorator 两级树、实时过滤、排序下拉、展开/折叠、快捷键操作、选中预览
- 助记符选择对话框网格化（已占用置灰）
- 添加/删除/移动收藏后强制刷新 gutter
- 设置页与应用级/项目级状态持久化（供后续版本使用）
- 快捷键修复：工具窗口开关改为 `Alt+2`，移除 `Ctrl+Alt+2` 冲突

### ✅ v1.1.0 阶段B：原生书签集成（已完成）

- 导入 PhpStorm 原生书签：运行时 `BookmarksManager` API + `.idea` XML 容错解析双通道；同名列表创建、"原生书签"兜底列表、助记符随迁与占用降级、（路径+行号）去重、可重复执行增量合并；完成通知与首次引导 BALLOON 通知
- 隐藏原生书签入口：`ActionConfigurationCustomizer` + `replaceAction` 接管 `ToggleBookmark`/`ToggleBookmarkWithMnemonic`/`ShowBookmarks`/`ActivateBookmarksToolWindow`/`GotoBookmark0-9(A-Z)`（id 不变，键位映射保留）；残余原生动作与原生 `Bookmarks` 工具窗口隐藏（监听兜底）；关闭开关按记录恢复原生动作与工具窗口
- 设置页联动：勾选变化立即应用/恢复（覆盖所有已打开项目），新增"重置导入提示"按钮

## 项目结构

```
src/
├── main/
│   ├── kotlin/
│   │   └── com/
│   │       └── aogg/
│   │           └── favorites/
│   │               ├── FavoritesPlugin.kt      # 插件主类
│   │               ├── action/                  # 动作（添加收藏/助记符/跳转/工具窗口开关等）
│   │               ├── helper/                  # 持久化/gutter 图标/刷新助手
│   │               ├── listener/                # 文件变更与编辑器监听
│   │               ├── model/                   # Bookmark/ListInfo/BookmarkManager
│   │               ├── nativebookmarks/         # 原生书签导入与原生入口隐藏（阶段B）
│   │               ├── settings/                # 设置页与应用/项目级状态
│   │               └── ui/                      # 工具窗口/树/助记符对话框/弹出列表
│   └── resources/
│       └── META-INF/
│           └── plugin.xml                      # 插件配置
```

## 构建说明

### 方式一：使用 IntelliJ IDEA（推荐）

1. 在 IntelliJ IDEA 中打开项目
2. IDE 会自动下载 Gradle wrapper
3. 在 Gradle 工具窗口中运行 `buildPlugin` 任务
4. 插件包将生成在 `build/distributions/` 目录

### 方式二：使用命令行

```bash
# Windows
gradlew.bat buildPlugin

# Linux/macOS
./gradlew buildPlugin
```

## 安装插件

1. 构建完成后，在 `build/distributions/` 目录找到生成的 zip 文件
2. 在 PhpStorm 中：`File` -> `Settings` -> `Plugins` -> `Install Plugin from Disk`
3. 选择生成的 zip 文件进行安装
4. 重启 PhpStorm

## 技术栈

- **语言**：Kotlin + JDK17
- **平台**：IntelliJ Platform SDK
- **目标IDE**：PhpStorm 2023.3+（build 233 ~ 262.*）
- **构建工具**：Gradle

## 开发计划

详见 [docs/v2-design.md](docs/v2-design.md) 与 [docs/favorites-plugin-implementation-plan.md](docs/favorites-plugin-implementation-plan.md)

## 作者

- 作者：aogg
- 官网：https://github.com/aogg

## 许可证

待定
