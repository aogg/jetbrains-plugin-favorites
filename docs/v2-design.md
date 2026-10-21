# Favorites 插件 v2 规划与设计（参考 PhpStorm 原生 Bookmarks）

> 状态：已评审通过，进入开发。本文档同时作为开发规格书。

## 一、背景与问题诊断（规划）

用户反馈：**安装插件后完全没有操作入口，无法添加收藏**。

对现有 1.0.12 代码排查，根因如下：

| # | 问题 | 位置 | 后果 |
|---|------|------|------|
| 1 | `<action>` 及 `<add-to-group>` 写在 `<extensions>` 里 | plugin.xml L88-114 | 非法写法，编辑器/项目视图/标签页右键菜单**全部未注册** → 无右键入口 |
| 2 | `<actions>` 里的全局动作没有 `add-to-group` | plugin.xml L118-135 | 动作存在但不出现在任何菜单，仅能靠 Find Action 发现 |
| 3 | 快捷键冲突：`ToggleBookmarksToolWindow` 与 `JumpToMnemonic.2` 都绑了 `Ctrl+Alt+2` | plugin.xml L129/L144 | 行为不确定 |
| 4 | 收藏后未强制刷新 gutter（LineMarker 依赖 daemon） | action/helper | 添加后图标可能不出现，被误认为"没生效" |
| 5 | 无任何首次引导（通知/入口提示） | - | 用户找不到功能 |

v2 目标：修复入口 + 参照 PhpStorm Bookmarks 补齐「列表分组」能力 + 导入原生书签数据 + 隐藏原生书签入口 + 完善工具窗口体验。

## 二、总体方案（设计）

### 2.1 数据模型升级：引入收藏列表（List）

对标 PhpStorm 2021.3+ Bookmarks 的列表（Lists）概念：

- `Bookmark` 增加字段 `listName: String`（所属列表名，默认列表名固定为 **"收藏"**）。
- 新增 `ListInfo(name: String, sortType: SortType)`；`SortType = ADDED(默认) | DESCRIPTION | LOCATION`。
- 全局约束保持不变：助记符 0-9/A-Z 在**项目内全局唯一**（跨列表）。
- **存储迁移**：`favorites.xml`（`@State(name="FavoritesBookmarks")`）旧格式无 `listName`，加载时缺失字段一律归入默认列表"收藏"，不丢数据；新增 `lists: MutableList<ListInfo>` 存列表元信息（排序方式等）。

### 2.2 入口修复与菜单重构（plugin.xml 全部重写）

- **所有 `<action>` 全部移入 `<actions>` 段**，彻底废弃 extensions 里的非法 action。
- 新建 popup 组 `Favorites.Group`（text="收藏"，icon=AllIcons.Gutter.Bookmark），`add-to-group`：
  - `EditorPopupMenu`（编辑器右键）
  - `EditorGutterPopupMenu`（行号区右键）
  - `ProjectViewPopupMenu`（项目视图右键）
  - `EditorTabPopupMenu`（标签页右键）
  - 均为 `anchor="last"`。
- 组内动作：添加匿名收藏 / 添加助记符收藏 / 编辑描述 / 删除收藏 / 导入原生书签（占位，由原生集成任务实现）。
- 动作 `update()` 必须按上下文启用/禁用（编辑器→行收藏；项目视图/标签页→文件/文件夹），禁止灰色无效项常驻。

### 2.3 快捷键方案（消除冲突 + 贴近原生肌肉记忆）

| 功能 | v2 默认键 | 说明 |
|---|---|---|
| 添加/移除匿名收藏 | `F11`、`Ctrl+Alt+F11` | F11 由"原生动作替换"机制获得（见 2.6），Ctrl+Alt+F11 保留 |
| 添加助记符收藏 | `Ctrl+F11`、`Ctrl+Alt+Shift+F11` | 同上 |
| 打开/关闭收藏工具窗口 | `Alt+2`、`Ctrl+Alt+2`→**移除** | Alt+2 原生映射同样靠替换机制；删除与 Ctrl+Alt+2 的冲突 |
| 收藏弹出列表 | `Shift+F11`（替换获得）、`Ctrl+Alt+Shift+B` | 保留原有 |
| 跳转助记符 0-9/A-Z | `Ctrl+Alt+0-9`、`Ctrl+Alt+A-Z`（保留）；`Ctrl+0-9`（替换获得） | 不再占用 Ctrl+Alt+2 |

### 2.4 工具窗口重构（体验核心）

- 注册改为 `anchor="left"`（贴近 PhpStorm Bookmarks 的位置习惯），id 仍为 `Favorites`。
- 使用 `ToolbarDecorator` + `Tree`（原生 IDE 观感）：
  - **树结构**：第一层=列表（默认"收藏"+用户自建），第二层=收藏项（行收藏显示 `文件:行号`+描述；文件/文件夹收藏显示名称）。
  - **工具栏**：添加收藏、添加助记符、导入原生书签、编辑描述(F2)、删除(Del)、排序（按添加/描述/位置，按列表记忆）、全部折叠/展开。
  - **过滤**：树上置 `SearchTextField` 实时过滤（按描述/文件名）。
  - **交互**：Enter/双击=跳转（行→编辑器定位；文件夹→Project 视图选中）；F2=改描述；Del=删除；右键菜单：跳转/编辑描述/设置助记符/移动到列表▸/新建列表/重命名列表/删除列表（内容并入默认列表）/删除收藏；`TreeSpeedSearch` 必装。
  - 选中行收藏时预览打开文件（不抢焦点）。
- 助记符选择对话框保留并打磨：0-9/A-Z 网格，已占用项禁用置灰，可输入描述。

### 2.5 导入原生书签（双通道，尽力而为）

- **通道一（首选，运行时 API）**：`com.intellij.ide.bookmark.BookmarksManager.getInstance(project)` 读取原生书签（url/line/mnemonic/所属列表名）。实现前必须用 233 分支源码核对签名（见 8. 参考资料），所有调用包 `runCatching`。
- **通道二（兜底，XML 解析）**：API 不可用/为空时解析 `.idea/bookmarks.xml`（component=`BookmarksManager`）；同时对旧版 `favorites.xml`（PhpStorm ≤2021.2 的收藏列表）做**尽力而为**的容错解析，解析失败静默降级，不影响主流程。
- 导入规则：
  - 目标列表：原生有列表名则同名建我们的列表，否则进"原生书签"列表。
  - 去重：按 (规范化文件路径 + 行号) / 文件路径 去重，可重复执行（增量合并）。
  - 完成后弹通知：`已导入 N 条原生书签（新建 M 个列表，跳过重复 K 条）` + [查看] 按钮。
- **首次引导**：项目打开后检测到原生书签且未导入过（项目级标记），弹通知 `检测到 N 条 PhpStorm 原生书签`，动作 [立即导入] [不再提示]。

### 2.6 隐藏原生书签入口（可关闭，隐藏失败自动降级）

设置项 `hideNativeBookmarks`（**默认开启**，应用级，设置页可关）：

1. **动作接管（ActionManager.replaceAction）**：把原生动作 id 替换为我们的委托动作（保留用户已有键位映射，不污染 keymap）：
   - `ToggleBookmark`(F11) → 添加匿名收藏
   - `ToggleBookmarkWithMnemonic`(Ctrl+F11) → 添加助记符收藏
   - `ShowBookmarks`(Shift+F11) → 收藏弹出列表
   - `ActivateBookmarksToolWindow`(Alt+2) → 收藏工具窗口开关
   - `GotoBookmark0`..`GotoBookmark9`（及存在时的字母版）→ 对应助记符跳转
   - 逐 id 判空：id 不存在（不同版本）直接跳过；替换记录到应用级状态用于恢复。
2. **残余原生动作隐藏**：`ToggleBookmark0..9` 等未替换的原生书签动作 `templatePresentation.isVisible=false`；原生 `Bookmarks` 动作组（若存在）整体隐藏。
3. **原生工具窗口隐藏**：对 `Bookmarks` 工具窗口尽力隐藏（`setAvailable(false)` + 监听器兜底再隐藏），失败仅记日志，**不影响插件其他功能**（用户原话："隐藏不掉就算了"）。
4. **关闭开关时**：按记录恢复原动作（`replaceAction(id, original)`）与工具窗口可见性。
5. 通过 `com.intellij.actionConfigurationCustomizer`（`ActionConfigurationCustomizer`）扩展点实现替换，早于界面初始化执行一次。

### 2.7 设置页

`FavoritesConfigurable`（Settings → Tools → Favorites）：
- ☑ 隐藏 PhpStorm 原生书签入口（默认勾选；apply 时即时生效/恢复）。
- 按钮：重置"导入提示"标记（重新触发首次引导检测）。

### 2.8 版本与文档

- 版本 `1.0.12 → 1.1.0`（build.gradle.kts 与 plugin.xml 同步），change-notes 增加 1.1.0 条目。
- README 更新：入口图、快捷键表、导入/隐藏说明。
- 单元测试同步更新（列表迁移、去重合并），保证可编译；CI 只跑 `buildPlugin`。

## 三、分阶段实施（委派）

| 阶段 | 内容 | 产出 |
|---|---|---|
| A | 入口修复 + 列表数据模型/迁移 + 工具窗口重构 + 菜单/快捷键重构 + 设置页 + 版本 1.1.0 | commit 1 |
| B | 原生书签导入（API+XML 兜底）+ 隐藏原生（replaceAction/工具窗口）+ 首次引导通知 + change-notes/README 收尾 | commit 2 |
| C | 打包：push master → GitHub Actions 构建 → Release latest → 下载 zip 交付 | zip 路径 |

## 四、验收清单

1. 全新安装后：编辑器/行号区/项目视图/标签页右键均有"收藏"子菜单；左侧有 Favorites 工具窗口；Find Action 可搜到全部动作。
2. `F11/Ctrl+F11/Alt+2/Shift+F11/Ctrl+0-9` 原生键位下行为由本插件接管（默认配置时）。
3. 旧数据（无 listName 的 favorites.xml）加载后全部出现在默认列表"收藏"。
4. 能从原生书签导入数据且重复导入不产生重复项。
5. 关闭"隐藏原生"后，原生书签功能恢复。

## 五、约束

- 目标平台：PhpStorm 2023.3（build 233）～ 2026.2（until 262.*），Kotlin + JDK17，**不新增第三方依赖**。
- 所有对原生 API 的调用必须判空 + `runCatching`，保证 233→262 区间兼容。
- 本地无 JDK，编译验证依赖 CI；代码需保守、API 签名以 233 源码为准。

## 六、参考资料（实现前必须核对）

- BookmarksManager API（233 分支）：
  https://raw.githubusercontent.com/JetBrains/intellij-community/233/platform/platform-impl/src/com/intellij/ide/bookmark/BookmarksManager.kt
- Bookmark 接口（233 分支）：
  https://raw.githubusercontent.com/JetBrains/intellij-community/233/platform/platform-impl/src/com/intellij/ide/bookmark/Bookmark.kt
- ActionConfigurationCustomizer：`com.intellij.openapi.actionSystem.impl.ActionConfigurationCustomizer`，扩展点名 `com.intellij.actionConfigurationCustomizer`
