# 交接文档（给下一个会话的 AI / 给自己）

> 这份文件存在的理由：上下文会用完，**对话里的决策原因会丢，代码不会**。
> 每完成一批功能就更新这里的「当前状态」和「待办」。

---

## 一、项目基本事实

| | |
|---|---|
| 路径 | `E:\AndroidStudioProjects\WaitLeisure` |
| 仓库 | `git@github.com:QinguQiu/WaitLeisure.git`（公开） |
| 包名 | `com.waitleisure` |
| prefs | `waitleisure_prefs` |
| 日志 tag | `WaitLeisure` |
| 技术栈 | Kotlin 2.2.10 + Compose(M3)、AGP 9.4.0、compileSdk 37、minSdk 26、JDK 21 |

### 构建与校验

```powershell
cd E:\AndroidStudioProjects\WaitLeisure
.\gradlew.bat :app:assembleRelease :app:testDebugUnitTest --console=plain
powershell -ExecutionPolicy Bypass -File tools\verify.ps1
```

- **必须** 0 错 0 警；单测全绿；静态检查全过才能发版
- `tools/verify.ps1` **必须是 UTF-8 with BOM**（PowerShell 5.1 没有 BOM 会当 ANSI 读，直接 ParserError）
- 版本号：`versionCode = gitCommitCount`（自动），`versionName` 手写在 `app/build.gradle.kts`

### 发版流程

1. 改 `versionName`
2. 提交 + `git push origin main`
3. `git tag -a vX.Y.Z -m "..."` + `git push origin vX.Y.Z`
4. GitHub Actions（`.github/workflows/release.yml`）构建并创建 Release
5. **必须**下载 Release 里的 APK 校验签名后再算完成

**签名指纹（SHA-256）**：`1549b28b583c8dd43867fca8e578f78d811863939fa8399542064787280f488c`

密钥库路径与密码在 `local.properties` / CI Secrets 里，**不要写进仓库**。

---

## 二、环境注意事项

- 设备 `9a8b5550`（Redmi K70 / HyperOS / Android 16）**经常掉线**，装机前先 `adb devices` 确认
- `adb install -r` 会清掉无障碍绑定，需要重新授权：
  ```powershell
  adb shell settings put secure enabled_accessibility_services com.waitleisure/com.waitleisure.lock.LockAccessibilityService
  adb shell settings put secure accessibility_enabled 1
  ```
  但 **2.2.2 之后不用了** —— App 启动会自动重绑（需 `WRITE_SECURE_SETTINGS` 已授予）：
  ```powershell
  adb shell pm grant com.waitleisure android.permission.WRITE_SECURE_SETTINGS
  ```
  重装 APK 后这条授权**会保留**（同签名）。刷机才需要重打。

- `github.com` 在本机经常不通，但 `api.github.com` / `objects.githubusercontent.com` 通。
  下载 Release 附件走 API：
  ```
  GET https://api.github.com/repos/QinguQiu/WaitLeisure/releases/tags/vX.Y.Z
  → assets[].url（配 Accept: application/octet-stream 下载，会跳到 objects 域名）
  ```

- `adb shell input text` **不能输入中文** —— 别用它污染用户手写的中文内容
- 截图：`adb shell screencap -p /sdcard/s.png` + `adb pull`

---

## 三、代码里的坑（踩过的）

1. **`AppPrefs` 的 `companion object` 是 private** —— `ui` / `lock` 包要用常量，必须写成
   `AppPrefs.kt` 的**顶层声明**（已踩过两次：`wallpaperMaxOffset`、`SIGNATURE_MAX_LENGTH`）

2. **不要加 AGP 9 的 `optimization { packageScope = ... }`** —— 会让 release 包在真机
   崩溃（`IllegalAccessError: Class kotlin.collections.i extended by class qi is inaccessible`）

3. **Compose 的 `Modifier.size()` 会被父约束夹住** —— 壁纸要按真实像素布局必须用 `requiredSize`

4. **`pointerInput(Unit)` 会把闭包参数冻结** —— 需要最新值就配 `rememberUpdatedState`

5. **锁机到期不要拉首页** —— `LockController.unlock()` 默认 `pullAppToFront = true`，
   只在**手动解锁**时用；到期路径必须传 `false`（番茄钟每秒触发一次，会变成拉首页死循环）

6. **`stopService` 里必须判 `Pomodoro.isActive()`** —— 番茄钟靠前台服务每秒 `tick`
   推进相位，停了服务休息就永远结束不了

7. **列表里的名字别用字符串拼进大字号 Text** —— 会继承字号撑爆行宽
   （定时规则的名字栏踩过，后来改成独立 13sp 一行）

8. **CI 的 Release job 会失败于「Release 已存在」**
9. **不要用「两层文字叠描边」** —— 描边层的测量宽高和填充层总有细微差别，
   两层叠起来必然基错位、出重影（`OutlinedText` 试了 4 版没洗干净，已删除）。
   要描边就单层 + `Shadow(offset = Zero, blurRadius)`，即 `ui/HaloText.kt`。
 —— tag 建了之后如果再改代码重推 tag，
   旧 Release 已经占位。要么先删 Release，要么直接升版本号

---

## 四、当前状态

**最新版本 2.2.6**（2026-10-26）

已完成并在真机验证过的：

- 番茄钟：休息后恢复锁机 ✓（用户确认）
- 番茄钟：休息期间不再被踢回首页 ✓（真机验证：休息时停在 B 站 80 秒不被打断）
- 无障碍自动重绑（`WRITE_SECURE_SETTINGS`）✓
- 定时规则名字栏 ✓
- 文案「循环次数 → 番茄钟个数」✓

**待真机验证**（代码已提交）：

- 2.2.4：休息阶段卡片显示「休息中 / 不能停止」
- 2.2.4：休息阶段进设置页被整页挡住

---

## 五、待办

见 `TODO.md`。
