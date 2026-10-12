# SelfLock · 安卓锁机 App（L1）

全局锁机 + 自定义白名单 + 每日定时。个人自用 / 学习项目。

**全部代码为原创净室实现**，未使用、未参考任何第三方 APK 的反编译产物。

---

## 一、它到底做了什么

| 需求 | 实现方式 |
|---|---|
| **全局锁机** | 锁机期间，`LockScreenActivity` 成为唯一的「桌面」。按 Home 键、从最近任务切回来，都会被无障碍服务立刻弹回锁机页 |
| **只留白名单可用** | 无障碍服务监听前台应用切换，凡不在白名单里的包名一律拦截；锁机页上只列出白名单应用的图标，点一下就能正常用 |
| **白名单可自定义** | 白名单页列出所有带桌面图标的应用，勾选即生效，写入 SharedPreferences |
| **每日定时** | 每条规则 = 起始时间 + 持续时长 + 重复星期。用 `AlarmManager.setExactAndAllowWhileIdle` 精确触发，触发后自动排下一天 |

附加：解锁 PIN、立即锁机、开机自动恢复定时、设备管理员防卸载。

---

## 二、目录结构

```
SelfLock/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/
        │   ├── values/{strings,themes,colors}.xml
        │   ├── xml/{accessibility_service_config,device_admin}.xml
        │   ├── drawable/{ic_launcher_foreground,ic_stat_lock}.xml
        │   └── mipmap-anydpi-v26/{ic_launcher,ic_launcher_round}.xml
        └── java/com/selflock/
            ├── SelfLockApp.kt              Application，初始化仓储 + 通知渠道
            ├── MainActivity.kt             主界面宿主
            ├── model/Schedule.kt           定时规则模型 + 格式化工具
            ├── data/
            │   ├── AppPrefs.kt             唯一落盘入口
            │   ├── Repos.kt                极简服务定位器
            │   ├── WhitelistRepository.kt
            │   ├── ScheduleRepository.kt
            │   ├── LockStateRepository.kt  「现在是否锁定」的唯一真相
            │   └── InstalledAppsRepository.kt
            ├── lock/
            │   ├── LockAccessibilityService.kt   软锁核心：拦截非白名单
            │   ├── LockScreenActivity.kt          锁机页（= 锁定期间的桌面）
            │   ├── LockForegroundService.kt       常驻通知 + 到点自动收摊
            │   ├── LockController.kt              锁/解锁的统一入口
            │   └── DeviceAdminReceiver.kt         防卸载
            ├── alarm/
            │   ├── AlarmScheduler.kt          排闹钟 / 算下一次触发时刻
            │   ├── ScheduleAlarmReceiver.kt   定时触发点
            │   └── BootReceiver.kt            开机 / 时间变更后重建闹钟
            ├── ui/
            │   ├── SelfLockNavHost.kt         底部导航 + NavHost
            │   ├── HomeScreen.kt              状态 + 权限体检 + PIN
            │   ├── WhitelistScreen.kt
            │   ├── ScheduleScreens.kt         列表 + 编辑
            │   └── Components.kt
            └── util/
                ├── Permissions.kt             权限体检与引导跳转
                ├── Notifications.kt
                └── Pin.kt
```

---

## 三、把它跑起来

### 环境要求
- Android Studio Ladybug (2024.2) 或更新
- JDK 17（Android Studio 自带）
- Android SDK Platform 35

### ⚠️ 关于 gradle-wrapper.jar

本工程**没有包含 `gradle/wrapper/gradle-wrapper.jar`**（二进制文件，生成环境无法下载）。
请按下面任一方式处理。

#### 方式 A：让 Gradle 自己生成（如果你装过 Gradle）
在工程根目录执行：
```bash
gradle wrapper --gradle-version 8.11.1
```
之后就能用 `./gradlew` 了。

#### 方式 B：Android Studio 直接同步
1. `File → Open`，选中 `SelfLock` 目录
2. 如果提示 wrapper 缺失，打开 `Settings → Build, Execution, Deployment → Build Tools → Gradle`
3. 把 **Use Gradle from** 改成 **Specified location**，指向你本机已装的 Gradle，或选 Android Studio 自带的 distribution
4. `Sync Project with Gradle Files`

#### 方式 C：最稳的兜底（推荐新手）
1. Android Studio 新建一个 **Empty Activity (Compose)** 工程，包名 `com.selflock`，`minSdk 26`
2. 用本工程的 `app/src/`、`app/build.gradle.kts`、`AndroidManifest.xml` **覆盖**新工程里的同名内容
3. 同步、运行

> 方式 C 之所以最稳：新工程自带完整 wrapper、正确的 JDK 配置和 `local.properties`，绕开了所有环境坑。

### 国内网络（重要，先看这段）

`dl.google.com` 同时承担两件事：

1. Android Studio 的 **SDK 组件列表**
2. Gradle 的 **Google Maven 仓库**（AGP、androidx 全都在这儿）

所以在国内网络下，典型症状是先弹 `Unable to access Android SDK add-on list`，
紧接着构建时又报依赖下载失败。

**本工程已经预先处理了第 2 点**：`settings.gradle.kts` 里把阿里云镜像排在官方仓库前面，
官方仓库保留为兜底。所以只要 SDK 装好了，依赖拉取一般不会卡住。

第 1 点（SDK 下载）需要你自己处理，两个办法：

- **有代理/VPN**：Android Studio → `Settings → Appearance & Behavior → System Settings → HTTP Proxy`，填进去，重启 IDE
- **没有代理**：`Settings → Languages & Frameworks → Android SDK → SDK Update Sites`，
  把站点换成国内镜像，例如 `https://mirrors.cloud.tencent.com/AndroidSDK/`

> ⚠️ 上面这个镜像地址我**无法从当前环境验证**（本机无网络出口），请以实际能不能打开为准。
> 如果你有代理，直接用代理最简单，也不必改镜像。

如果你想改回纯官方仓库，把 `settings.gradle.kts` 里带 `aliyun` 的三行删掉即可，
`google()` / `mavenCentral()` 本来就在列表里。
### 首次运行后会遇到的编译提示

- `kotlinOptions` 已废弃 —— 只是警告，不影响构建
- `queryIntentActivities(intent, 0)` 已废弃 —— 这是为了兼容旧 API，用 `@Suppress("DEPRECATION")` 压掉了
- `LifecycleEventEffect` 需要 `androidx.lifecycle:lifecycle-runtime-compose:2.8.7`，已在依赖里显式声明

### 刻意回避的两个 API

为了把「编译期的不确定性」压到最低，有两处**故意没用**更常见的写法：

| 没用 | 改用 | 原因 |
|---|---|---|
| Material3 的 `TimePicker` / `rememberTimePickerState` | 用 `FilterChip` 拼的选择器（见 `ScheduleScreens.kt`） | 它是 `@ExperimentalMaterial3Api`，签名在 1.2 / 1.3 / 1.4 之间变过，是编译期最不确定的一处 |
| `material-icons-extended` | 只用 `material-icons-core` 自带的图标（`Home` / `List` / `DateRange` / `Lock` / `Delete`） | 该 artifact 在 Compose 1.7+ 已废弃、1.8 后被移除，为了 2 个图标引入它不划算 |

结果是：**全项目没有任何 `@OptIn(ExperimentalMaterial3Api::class)`**，依赖表里也没有已废弃的 artifact。
副作用是时间选择器改成了「整点/5 分钟」的 chip 列表 —— 对锁机场景（23:00、07:00 这类）反而更好点。

---

## 四、装到手机上之后，必须做的 5 件事

打开 App → 首页 → **权限体检**，一项一项点「去开启」：

| # | 权限 | 不给会怎样 |
|---|---|---|
| 1 | **无障碍服务** | **锁机完全不生效**。这是地基 |
| 2 | **闹钟和提醒**（Android 12+） | 定时触发不准，可能差几分钟到几十分钟 |
| 3 | **通知权限**（Android 13+） | 锁机期间看不到常驻通知 |
| 4 | **忽略电池优化** | 后台被系统冻结，表现为「时灵时不灵」 |
| 5 | **设备管理员** | 别人（或你自己）能一键卸载，锁机中途失效 |

然后：
1. 去「白名单」勾选锁定期间要保留的应用（**建议至少加上电话、短信、闹钟**）
2. 回首页设置 **解锁 PIN**（不设也能用，但锁定期间可以无密码解除）
3. 去「定时」新建规则，例如 `23:00 + 8 小时 + 每天`

### 国产 ROM 额外注意

小米/红米（HyperOS）、OPPO、vivo 需要额外在系统设置里给本应用：
- **自启动**
- **后台弹出界面**（否则锁机页拉不起来）
- **锁定后台任务**（最近任务里下拉加锁）

### Redmi K70 / HyperOS 专项设置 ⭐

小米系 ROM 对本 App 的后台管控最严。**下面第一项不做，锁机页大概率拉不起来** ——
症状是「无障碍开着、定时也到点了，但屏幕没被锁上」。

| 设置 | 路径 | 为什么必须做 |
|---|---|---|
| **后台弹出界面** ⭐⭐ | 设置 → 应用设置 → 应用管理 → SelfLock → 权限管理 → **其他权限** → **后台弹出界面** → 允许 | **最关键的一项。** 没有它，无障碍服务检测到非白名单应用后，**无法把锁机页拉到前台**，拦截会静默失败。这是 MIUI/HyperOS 独有的一道闸 |
| **自启动** | 设置 → 应用设置 → 应用管理 → SelfLock → **自启动** → 打开 | 开机后定时闹钟才能重建 |
| **省电策略 → 无限制** | 设置 → 应用设置 → 应用管理 → SelfLock → **省电策略** → 无限制 | 否则息屏后进程被冻结，定时锁机不触发 |
| **锁定后台任务** | 进入最近任务 → 找到 SelfLock 卡片 → 下拉（或长按）→ 点**小锁**图标 | 防止「一键清理」把它杀掉 |
| **通知权限** | 设置 → 应用设置 → 应用管理 → SelfLock → 通知管理 → 允许 | 锁机常驻通知才可见 |
| **无障碍服务** | 设置 → 更多设置 → 无障碍 → 已下载的服务 → **SelfLock 锁机服务** → 打开 | 锁机的地基 |

> 💡 **HyperOS 的设置项名称在不同小版本会变。最快的方法是在「设置」顶部的搜索框直接搜关键词**：
> `后台弹出界面`、`自启动`、`省电策略`、`无障碍`。
> 上面的路径是 HyperOS 常见层级，如果你的菜单对不上，以搜索框结果为准。

#### 两个已知的 HyperOS 行为

1. **系统更新后「省电策略」可能被重置。**
   如果发现锁机用了一段时间后开始失灵：手机管家 → 应用管理 → SelfLock → 确认省电策略仍是「无限制」。

2. **锁机期间 MIUI 的系统弹窗会被一并拦住。**
   这是刻意的 —— 比如「系统更新提示」「低电量提醒」弹出时会被弹回锁机页。
   如果你需要临时放行某个系统组件，把它加进白名单即可。
   被永远放行的只有：输入法、系统界面、拨号盘（保证打得出紧急电话）。

#### Redmi K70 上验证功能的顺序

1. 先开无障碍，**白名单先只留「电话」**，点「立即锁机」
2. 试着打开微信/浏览器 —— 应该被弹回锁机页；试着打开电话 —— 应该能正常用
3. 输入 PIN 解除
4. 确认无误后，再配白名单和每日定时

---

#### 关于「启动应用」确认框（已修复 + 你还能做一件事）

从锁机页点白名单应用时，MIUI/HyperOS 会先弹一个确认框：

> SelfLock 想要打开 XX，是否允许？　　　[本次允许] [始终允许] [拒绝]

**这个框一度会被本 App 自己秒关掉。** 原因：它属于系统/安全中心，包名不在白名单里，
无障碍服务把它当成「非白名单应用」，立刻把锁机页拉回前台 —— 用户根本来不及点。

**代码已修**：从锁机页启动应用时，会先开一段 **10 秒免打扰窗口**，窗口内一律不拦截；
窗口结束后由前台服务补一次前台检查，把可能的空子堵上。

这个修法**刻意不判包名** —— 那个确认框到底属于哪个包，各家 ROM、各版本都不一样，
猜错了就白改。用时间窗口绕过包名识别，最稳。

**你还可以一劳永逸**：在那个确认框弹出时直接点 **「始终允许」**，每个应用只需点一次，
之后不再弹。或者去系统设置里给 SelfLock 放行「关联启动 / 链式启动」。

路径关键词（HyperOS 各版本名称不同，**用设置顶部的搜索框搜最快**）：

`关联启动`、`链式启动`、`启动其他应用`

## 五、工作原理（为什么这样能锁住）

```
定时到点
   ↓
ScheduleAlarmReceiver → LockController.lock()
   ↓                      ├─ 写 lockedUntil 时间戳（落盘）
   ↓                      ├─ 拉起 LockForegroundService（常驻通知）
   ↓                      └─ 拉起 LockScreenActivity（黑底锁机页）
   ↓
用户点任何非白名单应用
   ↓
LockAccessibilityService.onAccessibilityEvent()
   → shouldBlock(pkg)? 是 → LockScreenActivity.show()
```

三个关键设计：

1. **「是否锁定」只由时间戳决定**，不设额外的开关位。
   即使进程被杀、闹钟被延迟，只要重新读一次时间戳就能得到正确结论，不会出现状态错乱。

2. **锁机页就是锁定期间的桌面**。
   桌面（Launcher）本身不在白名单里，所以按 Home 会被弹回锁机页；
   想在锁机期间用白名单应用，只能从锁机页上的图标进 —— 这正是想要的效果。

3. **输入法 / 系统界面 / 拨号盘永远放行**（见 `LockAccessibilityService.alwaysAllowedPackages()`）。
   少了输入法你连 PIN 都输不进去；少了拨号盘就打不出紧急电话。

---

## 六、已知限制（非 root 无法根治）

诚实说明，这些是 Android 的架构限制，不是代码 bug：

| 绕过方式 | 能否防住 | 说明 |
|---|---|---|
| 按返回键 / 返回手势 | ✅ | `BackHandler` 吞掉了 |
| 按 Home 键 | ✅ | 无障碍服务弹回 |
| 从最近任务切回 | ✅ | 锁机页 `excludeFromRecents` |
| 一键卸载 | ✅ | 设备管理员激活后卸载按钮置灰 |
| **进设置关闭无障碍服务** | ❌ | 设置页不在白名单里，但用户总能在锁机页之外（例如另一台设备/重启瞬间）操作 |
| **安全模式** | ❌ | 安全模式下第三方无障碍服务不运行，可直接卸载 |
| **强制停止 / 清除数据** | ❌ | 系统设置里可操作 |
| **修改系统时间** | ⚠️ | `TIME_SET` 广播会重排闹钟，但仍可能有短暂窗口 |

**要根治这四条，唯一正解是把 App 提升为 Device Owner（设备所有者）**，见下一节。

---

## 七、下一步：升级成硬锁（Device Owner）

本工程已经预留了结构，升级需要做的是：

1. **恢复出厂设置**（或在全新设备上），开机时**不要**登录任何 Google/小米账号
2. 安装本 App 的 debug 包
3. 用电脑执行：
   ```bash
   adb shell dpm set-device-owner com.selflock/.lock.DeviceAdminReceiver
   ```
4. 成功后即可在代码里使用：
   - `dpm.setLockTaskPackages(admin, arrayOf(白名单包名))` —— 系统级白名单
   - `startLockTask()` / `setLockTaskFeatures()` —— 锁定任务模式，**Home 和最近任务键被系统禁用**
   - `dpm.setStatusBarDisabled()`、`dpm.setKeyguardDisabled()` —— 禁用状态栏和锁屏
   - `DevicePolicyManager` 还可以禁止安全模式、禁止恢复出厂、禁止卸载

`LockController` 里加一个 `if (isDeviceOwner()) { ... }` 分支即可，其余代码不用动。

---

## 八、免责声明

本项目仅供个人学习与自用。请勿用于锁定他人设备——那属于违法行为。

---

## 九、静态校验工具（编译前预检）

工程自带一个纯 PowerShell 的静态校验脚本，**不需要 Android SDK / Gradle** 就能跑，
用来在编译前抓出那些「人眼容易漏、但编译器一定会报」的问题：

```powershell
$env:SELFLOCK_ROOT = "<工程根目录>"
powershell -ExecutionPolicy Bypass -File "<工程根目录>\tools\verify.ps1"

> 若提示「无法加载文件……未进行数字签名」，那是 PowerShell 默认的 Restricted 执行策略所致，
> 用上面的 `-ExecutionPolicy Bypass` 即可，**不需要**全局修改系统策略。
> 也可以改为执行一次：`Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`，之后就能直接 `& .\tools\verify.ps1`。
>
> 退出码 0 表示全部通过；非 0 表示发现了问题（问题清单会打印在结尾）。
```

当前工程通过结果：**302 个检查点，全部通过**（7 类检查）。

它检查 7 类问题：

| # | 检查 | 能抓到什么 |
|---|---|---|
| 1 | XML 良构性 | 未转义的 `&`、标签不闭合、`</string>` 写错 |
| 2 | Manifest 组件 | 声明了 `.lock.Foo` 却没有 `Foo.kt`；或类没继承正确的基类（Activity / Service / BroadcastReceiver） |
| 3 | R 资源引用 | `R.string.xxx` / `@string/xxx` 指向不存在的资源 |
| 4 | package 与目录 | Kotlin 包名和目录层级对不上 |
| 5 | 内部 import | `import com.selflock.a.b.C` 在源码里解析不到 |
| 6 | **自有类型成员** | `Schedule` 上不存在的属性、`Repos.whitelist.toggleXYZ()` 这类链式调用打错（**这是最接近"编译器级"的一类检查**） |
| 7 | **跨包类型 import** | 用了 `com.selflock.util.Pin` 却忘了写 `import` |

### ⚠️ 关于编码（踩过的坑）

**该脚本必须以「UTF-8 带 BOM」保存。**

Windows PowerShell 5.1 在没有 BOM 时会按系统 ANSI 代码页（GBK）读取脚本文件，
中文会被解坏，甚至**吞掉字节导致字符串里的 `<` 消失**，进而误报一堆"XML 解析失败"。
脚本内部读取工程文件时一律显式走 UTF-8（`Read-Text` 函数），所以工程文件本身不受影响，
只有脚本文件自身的编码需要 BOM。

如果你用编辑器改过这个脚本，请确认保存为「UTF-8 with BOM」。

### 它不能替代什么

静态校验**不能**替代真机编译。以下问题只有 Gradle 才能发现：

- Kotlin 语法错误、类型不匹配
- Compose / Material3 的 API 在具体版本上的差异
- 资源在运行时的表现（图标是否解析、主题是否正确）

所以：**跑通 `verify.ps1` ≠ 能编译**。它只是把最容易犯的低级错误提前挡掉。

---

## 十、构建验证记录

**本工程已在真实 Android 工具链下完整编译通过。**

| 项 | 值 |
|---|---|
| 构建命令 | `gradlew.bat clean :app:assembleDebug` |
| 结果 | **BUILD SUCCESSFUL**，退出码 0 |
| 错误 / 警告 | **0 / 0** |
| 产物 | `app/build/outputs/apk/debug/app-debug.apk`（11.6 MB） |
| Gradle | 9.6.0 |
| AGP | 9.4.0 |
| Kotlin | 2.2.10 |
| Compose BOM | 2026.02.01 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 |
| JDK | 21 |

### 为了通过编译做的三处适配

实际工具链比设计时（AGP 8.7.3 / Kotlin 2.1.0 / Compose 2024.12）新了近两年，
而且模板工程**没有**引入下面三个库。为了做到「一个依赖都不多加」，代码直接改成等价的原生写法：

| 原本会用 | 改成 | 原因 |
|---|---|---|
| `androidx.navigation` 的 `NavHost` | `SelfLockNavHost.kt` 里用 `when` + `mutableStateOf` 手写路由 | 只有 4 个页面，不值得多引一个依赖。附带好处：离开的页面会被 dispose，回来时 `LaunchedEffect(Unit)` 重跑，正好用来刷新权限状态 |
| `lifecycle-runtime-compose` 的 `LifecycleEventEffect` | `LaunchedEffect(Unit)` + 手动「重新检测」按钮 | 少一个依赖 |
| `material-icons` 的图标 | 导航图标改用 BMP 区字符（`\u2302` / `\u2630` / `\u23F0`）；锁机页和删除按钮改用文字 | 该 artifact 已废弃；同时让源码保持纯 ASCII，规避编码问题 |

**结果：`app/build.gradle.kts` 没有为迁就依赖而改动**（唯一改动是版本号自动化，见第十一节）。

### 还没验证的部分

编译通过 ≠ 功能可用。以下只能在真机上验证：

- 无障碍服务能否正常拦截非白名单应用
- 定时锁机是否按点触发
- 各厂商 ROM 的后台限制是否需要额外放行（见第四节「国产 ROM 额外注意」）

---

## 十一、版本号自动化

`app/build.gradle.kts` 里的版本号**从 git 自动派生**，每次提交自动递增，不需要手动改任何文件：

| 字段 | 取值 | 例子 |
|---|---|---|
| `versionCode` | 仓库提交总数（`git rev-list --count HEAD`） | `7` |
| `versionName` | `1.0.<提交数>+<短 hash>` | `1.0.7+e6c9ee0` |

好处：

- `versionCode` 严格单调递增，满足 Android 对覆盖安装的要求
- 从版本号就能直接对上是哪一次提交，排查问题时不用猜

**取不到 git 时自动退回 `versionCode = 1` / `versionName = "1.0.1+nogit"`，不会因此让构建失败**
（比如把 ZIP 解压出来直接构建，或机器上没装 git）。

用 `providers.exec` 而不是 `Runtime.exec`：前者是配置缓存安全的写法，
Gradle 会把命令输出纳入配置缓存的指纹，所以提交之后版本号一定会跟着变。

如果你想手动控制版本号，把 `defaultConfig` 里那两行改回常量即可。
