 WaitLeisure（等闲）工程静态校验
#
# 在没有 Android SDK / Gradle 的机器上，用纯 PowerShell 校验那些
# 「不写出来编译器就会报错、但人眼很容易漏掉」的问题：
#   1) 所有 XML 是否良构（未转义的 &、标签不闭合等）
#   2) Manifest 声明的组件类是否存在，且是否继承正确的 Android 基类
#   3) 代码与 XML 中引用的每个 R.xxx / @xxx 资源是否真的定义了
#   4) 每个 .kt 的 package 声明是否与它的目录层级一致
#   5) 每个 import com.waitleisure.* 是否能在源码里解析到
#
# 用法： $env:WAITLEISURE_ROOT = "<工程根目录>"; & "<工程>\tools\verify.ps1"
#
# !!! 重要 !!!
# 本文件必须保存为「UTF-8 带 BOM」。Windows PowerShell 5.1 在没有 BOM 时
# 会按系统 ANSI 代码页(GBK)读取，中文会被解坏甚至吞掉字节，导致 XML 解析失败。
# 同理，脚本内部读取工程文件一律走 Read-Text（显式 UTF-8），绝不用 Get-Content 的默认编码。

$ErrorActionPreference = "Continue"

$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Read-Text([string]$path) {
    return [System.IO.File]::ReadAllText($path, (New-Object System.Text.UTF8Encoding($false)))
}

$root = $env:WAITLEISURE_ROOT
if ([string]::IsNullOrEmpty($root)) {
    # 没给环境变量时自己猜项目位置。
    #
    # 两种情况都要照顾到：
    #   1. 从项目根目录跑（最常见）→ 当前目录本身就是项目
    #   2. 从项目**外面**跑        → 当前目录下还有个同名子目录
    #
    # 判据用 settings.gradle.kts + app 目录同时存在，比猜名字可靠。
    # （踩过：项目改名后这里还把新名字又拼了一次，
    #   变成 E:\...\WaitLeisure\WaitLeisure，直接 ROOT NOT FOUND。）
    $here = (Get-Location).Path
    if ((Test-Path -LiteralPath (Join-Path $here "settings.gradle.kts")) -and
        (Test-Path -LiteralPath (Join-Path $here "app"))) {
        $root = $here
    } else {
        $root = Join-Path $here "WaitLeisure"
    }
}
if (-not (Test-Path -LiteralPath $root)) { Write-Output "ROOT NOT FOUND: $root"; exit 1 }

$main = Join-Path $root "app\src\main"
if (-not (Test-Path -LiteralPath $main)) { Write-Output "MAIN NOT FOUND: $main"; exit 1 }

$ktRoot = Join-Path $main "java"
$namespace = "com.waitleisure"
$nsDir = $namespace -replace '\.', '\'

$issues = New-Object System.Collections.Generic.List[string]
$checks = 0

function Add-Issue([string]$m) { $script:issues.Add($m) | Out-Null }
function Rel([string]$full) { return $full.Substring($script:root.Length).TrimStart('\') }

Write-Output "=== WaitLeisure 静态校验 ==="
Write-Output ("工程根目录: " + $root)
Write-Output ""

# ---------------------------------------------------------------- 1. XML 良构性
Write-Output "--- [1/7] XML 良构性 ---"
$xmlFiles = @(Get-ChildItem -LiteralPath $main -Recurse -File -Filter *.xml)
foreach ($f in $xmlFiles) {
    $checks++
    try {
        [void][xml](Read-Text $f.FullName)
    } catch {
        Add-Issue ("XML 解析失败: " + (Rel $f.FullName) + "  ==>  " + $_.Exception.Message)
    }
}
Write-Output ("检查了 " + $xmlFiles.Count + " 个 XML 文件")
Write-Output ""

# ---------------------------------------------------------------- 2. Manifest 组件
Write-Output "--- [2/7] Manifest 组件：存在性 + 基类正确性 ---"
$manifestPath = Join-Path $main "AndroidManifest.xml"
$manifestRaw = Read-Text $manifestPath

# 基类族：超类名字里含有这个关键字就算通过
$baseFamily = @{
    "application" = "Application"
    "activity"    = "Activity"
    "service"     = "Service"
    "receiver"    = "BroadcastReceiver|DeviceAdminReceiver"
}

# 从 manifest 里按元素类型提取组件名
function Get-ComponentsOfKind([string]$kind) {
    $result = New-Object System.Collections.Generic.List[string]
    $pattern = "(?s)<" + $kind + "\b(.*?)(?:/>|>)"
    foreach ($m in [regex]::Matches($manifestRaw, $pattern)) {
        $block = $m.Groups[1].Value
        $n = [regex]::Match($block, 'android:name="([^"]+)"')
        if (-not $n.Success) { continue }
        $v = $n.Groups[1].Value
        if ($v.StartsWith(".")) { $v = $namespace + $v }
        if (-not $v.StartsWith($namespace + ".")) { continue }   # 第三方组件跳过
        $result.Add($v.Substring($namespace.Length + 1)) | Out-Null
    }
    return $result
}

$totalComponents = 0
foreach ($kind in @("application", "activity", "service", "receiver")) {
    foreach ($c in (Get-ComponentsOfKind $kind)) {
        $totalComponents++
        $checks++
        $rel = $c -replace '\.', '\'
        $expectedFile = Join-Path $ktRoot (Join-Path $nsDir ($rel + ".kt"))
        $leaf = ($c -split '\.')[-1]

        if (-not (Test-Path -LiteralPath $expectedFile)) {
            Add-Issue ("[" + $kind + "] " + $c + " 找不到对应文件: " + $expectedFile)
            continue
        }

        $body = Read-Text $expectedFile

        # 2a. 类是否声明
        $declPattern = '(?m)^[^\n]*\b(?:class|object)\s+' + [regex]::Escape($leaf) + '\b([^\n]*)'
        $decl = [regex]::Match($body, $declPattern)
        if (-not $decl.Success) {
            Add-Issue ("[" + $kind + "] " + $c + " 文件里没有 class/object " + $leaf + " 声明")
            continue
        }

        # 2b. 基类是否正确
        $supertypes = $decl.Groups[1].Value
        $want = $baseFamily[$kind]
        if ($supertypes -notmatch $want) {
            Add-Issue ("[" + $kind + "] " + $c + " 继承关系可疑，期望基类含 '" + $want + "'，实际声明: class " + $leaf + $supertypes)
        }
    }
}
Write-Output ("检查了 " + $totalComponents + " 个组件")
Write-Output ""

# ---------------------------------------------------------------- 3. 资源引用
Write-Output "--- [3/7] R 资源引用可解析 ---"

$stringNames = New-Object System.Collections.Generic.List[string]
$styleNames = New-Object System.Collections.Generic.List[string]
$colorNames = New-Object System.Collections.Generic.List[string]

$valuesDir = Join-Path $main "res\values"
if (Test-Path -LiteralPath $valuesDir) {
    foreach ($f in @(Get-ChildItem -LiteralPath $valuesDir -File -Filter *.xml)) {
        try { $x = [xml](Read-Text $f.FullName) } catch { continue }
        foreach ($n in @($x.resources.string)) { if ($n -and $n.name) { $stringNames.Add([string]$n.name) | Out-Null } }
        foreach ($n in @($x.resources.style))  { if ($n -and $n.name) { $styleNames.Add([string]$n.name) | Out-Null } }
        foreach ($n in @($x.resources.color))  { if ($n -and $n.name) { $colorNames.Add([string]$n.name) | Out-Null } }
    }
}

function Test-DrawableRes([string]$n) {
    # 扫**所有** drawable* 目录，而不只是 drawable / drawable-v24。
    # 图标改成位图前景后放在 drawable-nodpi 下，原来那份白名单认不出来，
    # 于是 @drawable/ic_launcher_foreground 被误报成「未定义」。
    $dirs = @(Get-ChildItem -LiteralPath (Join-Path $main "res") -Directory -Filter "drawable*" -ErrorAction SilentlyContinue)
    foreach ($d in $dirs) {
        foreach ($ext in @(".xml", ".png", ".webp", ".jpg")) {
            if (Test-Path -LiteralPath (Join-Path $d.FullName ($n + $ext))) { return $true }
        }
    }
    return $false
}
function Test-MipmapRes([string]$n) {
    $dirs = @(Get-ChildItem -LiteralPath (Join-Path $main "res") -Directory -Filter "mipmap*" -ErrorAction SilentlyContinue)
    foreach ($d in $dirs) {
        foreach ($ext in @(".xml", ".png", ".webp")) {
            if (Test-Path -LiteralPath (Join-Path $d.FullName ($n + $ext))) { return $true }
        }
    }
    return $false
}
function Test-XmlRes([string]$n) {
    return (Test-Path -LiteralPath (Join-Path $main ("res\xml\" + $n + ".xml")))
}

$ktFiles = @(Get-ChildItem -LiteralPath $ktRoot -Recurse -File -Filter *.kt)

foreach ($f in $ktFiles) {
    $raw = Read-Text $f.FullName
    foreach ($m in [regex]::Matches($raw, 'R\.(string|drawable|mipmap|style|xml|color)\.([A-Za-z0-9_]+)')) {
        $checks++
        $kind = $m.Groups[1].Value
        $name = $m.Groups[2].Value
        $found = $false
        if     ($kind -eq "string")   { $found = $stringNames.Contains($name) }
        elseif ($kind -eq "style")    { $found = $styleNames.Contains($name) }
        elseif ($kind -eq "color")    { $found = $colorNames.Contains($name) }
        elseif ($kind -eq "drawable") { $found = Test-DrawableRes $name }
        elseif ($kind -eq "mipmap")   { $found = Test-MipmapRes $name }
        elseif ($kind -eq "xml")      { $found = Test-XmlRes $name }
        if (-not $found) {
            Add-Issue ("R." + $kind + "." + $name + " 未定义（出现在 " + (Rel $f.FullName) + "）")
        }
    }
}

foreach ($f in $xmlFiles) {
    $raw = Read-Text $f.FullName
    foreach ($m in [regex]::Matches($raw, '@(string|drawable|mipmap|style|xml|color)/([A-Za-z0-9_\.]+)')) {
        $checks++
        $kind = $m.Groups[1].Value
        $name = $m.Groups[2].Value
        $found = $false
        if     ($kind -eq "string")   { $found = $stringNames.Contains($name) }
        elseif ($kind -eq "color")    { $found = $colorNames.Contains($name) }
        elseif ($kind -eq "drawable") { $found = Test-DrawableRes $name }
        elseif ($kind -eq "mipmap")   { $found = Test-MipmapRes $name }
        elseif ($kind -eq "xml")      { $found = Test-XmlRes $name }
        elseif ($kind -eq "style") {
            $found = $styleNames.Contains($name)
            if (-not $found -and $name.Contains(".")) {
                $found = $styleNames.Contains($name.Substring($name.LastIndexOf('.') + 1))
            }
        }
        if (-not $found) {
            Add-Issue ("@" + $kind + "/" + $name + " 未定义（出现在 " + (Rel $f.FullName) + "）")
        }
    }
}
Write-Output ("已定义: string=" + $stringNames.Count + " style=" + $styleNames.Count + " color=" + $colorNames.Count)
Write-Output ""

# ---------------------------------------------------------------- 4. package 与目录
Write-Output "--- [4/7] Kotlin package 与目录一致性 ---"
$pkgOf = @{}
foreach ($f in $ktFiles) {
    $checks++
    $raw = Read-Text $f.FullName
    $m = [regex]::Match($raw, '(?m)^\s*package\s+([A-Za-z0-9_\.]+)')
    if (-not $m.Success) { Add-Issue ("缺少 package 声明: " + (Rel $f.FullName)); continue }
    $pkg = $m.Groups[1].Value
    $pkgOf[$f.FullName] = $pkg
    $expectedSuffix = $pkg -replace '\.', '\'
    if ($f.DirectoryName -notlike ("*" + $expectedSuffix)) {
        Add-Issue ("package '" + $pkg + "' 与目录不匹配: " + (Rel $f.FullName))
    }
}
Write-Output ("检查了 " + $ktFiles.Count + " 个 Kotlin 文件")
Write-Output ""

# ---------------------------------------------------------------- 5. 内部 import 可解析
Write-Output "--- [5/7] import com.waitleisure.* 可解析 ---"
# 预扫描：按「包目录」收集该包下所有文件里出现过的标识符
$declCache = @{}
foreach ($f in $ktFiles) {
    if (-not $pkgOf.ContainsKey($f.FullName)) { continue }
    $pkg = $pkgOf[$f.FullName]
    if (-not $declCache.ContainsKey($pkg)) {
        $declCache[$pkg] = New-Object System.Collections.Generic.HashSet[string]
    }
    $body = Read-Text $f.FullName
    foreach ($d in [regex]::Matches($body, '(?m)^[^\n]*\b(?:class|interface|object|fun|val|var)\b[^\n]*')) {
        foreach ($id in [regex]::Matches($d.Value, '\b([A-Za-z_][A-Za-z0-9_]*)\b')) {
            [void]$declCache[$pkg].Add($id.Groups[1].Value)
        }
    }
}

$importCount = 0
foreach ($f in $ktFiles) {
    $raw = Read-Text $f.FullName
    foreach ($m in [regex]::Matches($raw, '(?m)^\s*import\s+(com\.waitleisure\.[A-Za-z0-9_\.]+)\s*$')) {
        $importCount++
        $fq = $m.Groups[1].Value
        if ($fq -eq ($namespace + ".R") -or $fq.StartsWith($namespace + ".R.")) { continue }  # R 由编译器生成
        $checks++

        $parts = $fq.Split('.')
        $simple = $parts[-1]
        $pkg = ($parts[0..($parts.Count - 2)] -join '.')
        $ok = $false

        # 情况 A：符号名就是文件名
        $candidate = Join-Path $ktRoot (Join-Path ($fq -replace '\.', '\') ".kt")
        if (Test-Path -LiteralPath $candidate) { $ok = $true }

        # 情况 B：该包目录下某个文件声明了这个标识符
        if (-not $ok -and $declCache.ContainsKey($pkg) -and $declCache[$pkg].Contains($simple)) { $ok = $true }

        # 情况 C：父包下的嵌套符号（如 com.waitleisure.model.Schedule.Companion）
        if (-not $ok -and $parts.Count -ge 2) {
            $parentPkg = ($parts[0..($parts.Count - 3)] -join '.')
            $parentSym = $parts[-2]
            if ($declCache.ContainsKey($parentPkg) -and $declCache[$parentPkg].Contains($parentSym)) { $ok = $true }
        }

        if (-not $ok) {
            Add-Issue ("import " + $fq + " 无法解析（出现在 " + (Rel $f.FullName) + "）")
        }
    }
}
Write-Output ("检查了 " + $importCount + " 条内部 import")
Write-Output ""

# ---------------------------------------------------------------- 6. 自有类型成员解析
Write-Output "--- [6/7] 自有类型成员引用可解析 ---"

# 收集每个文件声明了什么类型、以及文件里出现过的所有 fun/val/var 名字
$fileInfo = @{}
foreach ($f in $ktFiles) {
    $raw = Read-Text $f.FullName

    $types = New-Object System.Collections.Generic.List[string]
    $typePattern = '(?m)^\s*(?:public\s+|internal\s+|private\s+|abstract\s+|open\s+|sealed\s+|data\s+|enum\s+|value\s+)*(?:class|object|interface)\s+([A-Za-z_][A-Za-z0-9_]*)'
    foreach ($m in [regex]::Matches($raw, $typePattern)) {
        $types.Add($m.Groups[1].Value) | Out-Null
    }

    $syms = New-Object System.Collections.Generic.HashSet[string]
    # 枚举项：enum class X { A, B, C } —— 它们既不是 fun 也不是 val/var，必须单独收
    foreach ($em in [regex]::Matches($raw, '(?s)enum\s+class\s+[A-Za-z_][A-Za-z0-9_]*\s*(?:\([^)]*\))?\s*\{([^}]*)\}')) {
        foreach ($e in [regex]::Matches($em.Groups[1].Value, '\b([A-Z][A-Za-z0-9_]*)\b')) {
            [void]$syms.Add($e.Groups[1].Value)
        }
        # Kotlin 枚举自带这两个静态方法，源码里看不到，但 X.valueOf(...) / X.values() 合法
        [void]$syms.Add("valueOf")
        [void]$syms.Add("values")
    }
    # 同时收扩展函数：fun Schedule.matchesDate(...) 里的名字是 matchesDate 而不是接收者，
    # 直接 \bfun\s+(\w+) 会抓成 Schedule，导致 Schedule.matchesDate 被误报「找不到声明」。
    foreach ($m in [regex]::Matches($raw, '\b(?:fun|val|var)\s+(?:[A-Za-z_][A-Za-z0-9_.]*\s*\.\s*)?([A-Za-z_][A-Za-z0-9_]*)')) {
        [void]$syms.Add($m.Groups[1].Value)
    }
    # 主构造参数里的属性：data class Schedule(val id: Long, val days: Set<Int>)
    foreach ($m in [regex]::Matches($raw, '\b(?:class|object)\s+[A-Za-z_][A-Za-z0-9_]*\s*\(([^)]*)\)')) {
        foreach ($p in [regex]::Matches($m.Groups[1].Value, '\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)')) {
            [void]$syms.Add($p.Groups[1].Value)
        }
    }

    $fileInfo[$f.FullName] = @{ Types = $types; Syms = $syms }
}

$typeToFile = @{}
$typeToPkg = @{}
foreach ($k in $fileInfo.Keys) {
    if (-not $pkgOf.ContainsKey($k)) { continue }
    $pkg = $pkgOf[$k]
    foreach ($t in $fileInfo[$k].Types) {
        if (-not $typeToFile.ContainsKey($t)) {
            $typeToFile[$t] = $k
            $typeToPkg[$t] = $pkg
        }
    }
}

# 6a. 任何 Type.member 形式的引用，member 必须在该类型所在文件里出现过声明
$memberChecks = 0
foreach ($t in $typeToFile.Keys) {
    $syms = $fileInfo[$typeToFile[$t]].Syms
    foreach ($f in $ktFiles) {
        $raw = Read-Text $f.FullName
        foreach ($m in [regex]::Matches($raw, '\b' + [regex]::Escape($t) + '\s*\.\s*([A-Za-z_][A-Za-z0-9_]*)')) {
            $memberChecks++
            $mem = $m.Groups[1].Value
            if (-not $syms.Contains($mem)) {
                Add-Issue ($t + "." + $mem + " 找不到声明（出现在 " + (Rel $f.FullName) + "）")
            }
        }
    }
}

# 6b. 链式访问 Repos.<属性>.<成员>：解析属性类型后再查成员
$chainChecks = 0
if ($typeToFile.ContainsKey("Repos")) {
    $reposRaw = Read-Text $typeToFile["Repos"]
    $reposProps = @{}
    foreach ($m in [regex]::Matches($reposRaw, '(?:lateinit\s+)?va[lr]\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*([A-Za-z_][A-Za-z0-9_]*)')) {
        $reposProps[$m.Groups[1].Value] = $m.Groups[2].Value
    }
    foreach ($f in $ktFiles) {
        $raw = Read-Text $f.FullName
        foreach ($m in [regex]::Matches($raw, 'Repos\s*\.\s*([A-Za-z_][A-Za-z0-9_]*)\s*\.\s*([A-Za-z_][A-Za-z0-9_]*)')) {
            $prop = $m.Groups[1].Value
            $mem = $m.Groups[2].Value
            if (-not $reposProps.ContainsKey($prop)) { continue }
            $owner = $reposProps[$prop]
            if (-not $typeToFile.ContainsKey($owner)) { continue }
            $chainChecks++
            if (-not $fileInfo[$typeToFile[$owner]].Syms.Contains($mem)) {
                Add-Issue ("Repos." + $prop + "." + $mem + " 在 " + $owner + " 里找不到（出现在 " + (Rel $f.FullName) + "）")
            }
        }
    }
}

Write-Output ("类型 " + $typeToFile.Count + " 个 / 成员引用 " + $memberChecks + " 处 / 链式 " + $chainChecks + " 处")
    $checks += $memberChecks + $chainChecks
Write-Output ""

# ---------------------------------------------------------------- 7. 跨包类型是否已 import
Write-Output "--- [7/7] 跨包类型是否已 import ---"
$importChecks = 0
foreach ($f in $ktFiles) {
    if (-not $pkgOf.ContainsKey($f.FullName)) { continue }
    $raw = Read-Text $f.FullName
    $pkg = $pkgOf[$f.FullName]

    $imported = New-Object System.Collections.Generic.HashSet[string]
    foreach ($m in [regex]::Matches($raw, '(?m)^\s*import\s+([A-Za-z0-9_\.]+)')) {
        [void]$imported.Add($m.Groups[1].Value.Split('.')[-1])
    }

    # 去掉 package / import 行，避免把 import 语句自身当成使用点
    $body = [regex]::Replace($raw, '(?m)^\s*(?:import|package)\s+[^\n]*$', '')
    $body = [regex]::Replace($body, '(?s)/\*.*?\*/', '')
    $body = [regex]::Replace($body, '(?m)//[^\n]*$', '')

    foreach ($t in $typeToPkg.Keys) {
        if ($typeToPkg[$t] -eq $pkg) { continue }
        if ($imported.Contains($t)) { continue }
        # (?<![\w.]) 排除「限定名」里的同名词 —— 例如 Screen.Permissions 里的
        # Permissions 是枚举值，跟 com.waitleisure.util.Permissions 这个工具类没关系，
        # 用 \b 会把后者也匹配进来，产生误报。
        if ($body -cmatch ('(?<![\w.])' + [regex]::Escape($t) + '\b')) {
            $importChecks++
            Add-Issue ("跨包类型 " + $t + "（" + $typeToPkg[$t] + "）被使用但未 import，出现在 " + $pkg + " 包的 " + (Rel $f.FullName))
        }
    }
}
Write-Output ("检查了 " + $importChecks + " 处可疑的跨包引用")
Write-Output ""
# ---------------------------------------------------------------- 汇总
Write-Output "========================================"
Write-Output ("总检查点: " + $checks)
if ($issues.Count -eq 0) {
    Write-Output "结果: 全部通过，未发现问题"
} else {
    Write-Output ("结果: 发现 " + $issues.Count + " 个问题")
    $i = 1
    foreach ($x in $issues) {
        Write-Output ("  " + $i + ". " + $x)
        $i++
    }
}
Write-Output "========================================"

# 发现问题时返回非 0 退出码，便于被脚本 / CI 调用
if ($issues.Count -gt 0) { exit 1 }
