# 待办

> 做完一项就删掉一项。跨会话继续做的时候，先读 `HANDOVER.md`。

---

## 待用户确认的问题

**长按拖动 vs 直接拖动**（白名单分组 / 定时规则排序）

建议：**长按后拖动**，因为条目本身是「点击进入编辑」，直接拖会误触。

---

## 1b. 锁机页白名单图标网格排序（数据层已完成，UI 待做）

- 页面文件不叫 `WhitelistScreens.kt`，**先定位**（`grep -r "白名单" app/src/main/java`）
- Compose 无内置可重排列表，需要自己写：
  ```
  detectDragGesturesAfterLongPress + 拖动换位 + 移动动画
  ```
- 排序持久化：分组数据本来就是 JSON 数组，**数组顺序 = 显示顺序**，
  拖动后调整数组顺序再存回即可，**不需要加新字段**

## 2. 定时页：同上 ✅ 已完成（2.3.0）

- 文件：`ui/ScheduleScreens.kt`
- 列表：`ScheduleListScreen` → `LazyColumn` → `items(schedules, key = { it.id })` → `ScheduleCard`
- 复用第 1 项写好的可重排组件（抽成公共 Modifier / 组件）
- 持久化同上（`ScheduleRepository` 的 JSON 数组顺序）

## 3. 取景页加锁机预览

- 文件：`ui/WallpaperCropDialog.kt`（142 行）
- `FramedWallpaper` 已是锁机页**同一个渲染器**，取景结果本身就等于锁机壁纸
- 缺的只是锁机页上那层文字：时间 / 日期 / 「已锁定」/ 个性签名
- 做法：在预览上叠一层半透明文字元素 + 一个「预览」开关（能切走看纯图）
- 注意：签名可能为空；空的时候不显示那一行（和锁机页行为一致）

---

## 更早的遗留（优先级低）

- 定时列表里任务名较长时会折行（例如「任务 · 番茄钟 4×25」），略挤
  可选方案：去掉「任务 · 」前缀 / 单行 + 省略号
- Releases 页面里 `v2.1.8` 是旧构建（不含「二选一」改动），建议手动删掉

---

## 2.7 计划：壁纸库（历史记录 + 多壁纸）

### 需求

1. **壁纸历史记录**：用过的壁纸都留一份，可以逐条删除
2. **多壁纸轮换**：每次锁机从库里取一张
   - 模式 A：**随机**
   - 模式 B：**顺序**（按库里的排列依次用）

### 现状（2.6.17）

- 只有**一张**壁纸：`filesDir/wallpaper.jpg`
- 取景参数存在 prefs：`wallpaperScale / wallpaperOffsetX / wallpaperOffsetY`
- 渲染组件 `ui/FramedWallpaper.kt`（锁机页和取景页共用）
- 选图流程：`SettingsScreen` → `WallpaperCropDialog` → 确认后写 `wallpaper.jpg`

### 设计

**存储**

```
filesDir/
  wallpapers/
    wp_<id>.jpg        ← 每张壁纸一个文件
  wallpaper.jpg        ← 旧版单张壁纸（迁移源，迁完可删）
```

prefs 新增：

- `wallpaperLibraryJson`：JSON 数组，每项 `{id, name, scale, offsetX, offsetY, addedAt}`
- `wallpaperMode`：`single` / `random` / `sequential`
- `wallpaperActiveId`：当前锁机正在用的那张
- `wallpaperSeqCursor`：顺序模式的下标

**迁移**：首次启动若 `wallpapers/` 为空且 `wallpaper.jpg` 存在 → 把旧图搬进库，
沿用旧的 scale/offset，`id=1`，并把 `wallpaperMode` 设为 `single`（**默认行为不变** ✓）。

**选图时机**：`LockController.lock()` 里，锁机开始时按模式挑一张，
写入 `wallpaperActiveId`，锁机页读它。不要在渲染时随机 —— 否则每次重组换一张 ✗。

**UI（设置 → 锁机外观）**

- 顶部：模式选择（单张 / 随机 / 顺序）
- 列表：每张缩略图 + 名称 + 删除按钮 + 「设为当前」
- 「＋ 添加壁纸」：走现有选图 + 取景流程，确认后进库

### 注意

- `FramedWallpaper` 的 scale/offset 是**按容器倍数**存的，每张壁纸各存一份 ✓ 不能共用
- 删除壁纸时要连文件一起删；删到只剩 0 张时回退到纯色背景
- 锁机页读图失败（文件被清）要兜底成纯色，不能崩
- 随机模式要**避免连续两次同一张**（除非库里只有一张）
