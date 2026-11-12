# WhereverCode V5 Hand 一键安装脚本（Windows）。
#
# V5 原生：只装 V5 Hand（hand_server.py，单文件 Python 服务），
# 不装旧 Execution Host、不连旧服务器、不从网上下载文件——
# 安装包（本脚本 + hand_server.py）由 App 传过来，或由用户自己拷到目标机上。
# 老项目只是思路参考（见下“安全机制”），不是模板：本脚本按 V5 自己的设计写。
#
# 安全机制（学老项目思路，原生实现）：
#   state dir、标记文件、版本化 releases、current 版本指针、
#   健康检查（端口接通 + 指纹可读）、保留一版回滚、幂等更新。
#
# 用法（在目标机上以普通用户跑 PowerShell）：
#   powershell -ExecutionPolicy Bypass -File v5-install-hand.ps1 -Version <版本> [-StateDir DIR] [-Port 18743] [-DeviceId ID] [-DisplayName NAME] [-BundleDir DIR]
#   -Version：必填，release 名，只许字母/数字/./_/-，1~80 字符。
#   -BundleDir：hand_server.py 所在目录，默认本脚本所在目录。
#   成功时最后一行打印（供 App 解析）：
#     V5_HAND_INSTALLED device_id=... fingerprint=... port=... version=... state_dir=... python=...
#   失败时退出非 0，并在 stderr 说明原因，已装版本不受影响。
#   注意：Windows 上没有 launchd/systemd；自启走“计划任务（登录时）”，
#   建任务失败时只启动本次、不静默失败（会明说）。
param(
    [Parameter(Mandatory=$true)][string]$Version,
    [string]$StateDir = "",
    [int]$Port = 18743,
    [string]$DeviceId = "",
    [string]$DisplayName = "",
    [string]$BundleDir = ""
)
$ErrorActionPreference = "Stop"

if ($Version -notmatch '^[A-Za-z0-9._-]{1,80}$') {
    Write-Error "请提供 -Version（只许字母/数字/./_/-，1~80 字符）。"; exit 2
}
if ($Port -lt 1 -or $Port -gt 65535) { Write-Error "-Port 必须在 1~65535 之间。"; exit 2 }

# --- 安装包位置：hand_server.py 须与本脚本在同一目录（或 -BundleDir）。---
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $BundleDir) { $BundleDir = $ScriptDir }
$HandPy = Join-Path $BundleDir "hand_server.py"
if (-not (Test-Path $HandPy)) { Write-Error "安装包里没有 hand_server.py（应在 $BundleDir 里）。"; exit 1 }

# --- 环境：Python 3.10+ + cryptography。---
function Find-Python {
    foreach ($cmd in @("py", "python", "python3")) {
        try {
            $out = & $cmd -3 --version 2>$null
            if (-not $out) { $out = & $cmd --version 2>$null }
            if ($out -match 'Python (\d+)\.(\d+)') {
                if ([int]$Matches[1] -gt 3 -or ([int]$Matches[1] -eq 3 -and [int]$Matches[2] -ge 10)) { return $cmd }
            }
        } catch { }
    }
    return $null
}
$Python = Find-Python
if (-not $Python) { Write-Error "需要 Python 3.10+（请从 python.org 安装后重跑）。"; exit 1 }
function Test-Cryptography {
    try { & $Python -c "import cryptography" 2>$null; return ($LASTEXITCODE -eq 0) } catch { return $false }
}
if (-not (Test-Cryptography)) {
    Write-Host "本机 Python 没有 cryptography，尝试安装……"
    try { & $Python -m pip install --user cryptography 2>$null | Out-Null } catch { }
    if (-not (Test-Cryptography)) {
        Write-Error "装不上 cryptography：请手动跑 '$Python -m pip install cryptography' 后重跑本脚本。"; exit 1
    }
}

# --- 状态目录：默认 $env:LOCALAPPDATA\WhereverCode\V5Hand。---
if (-not $StateDir) { $StateDir = Join-Path $env:LOCALAPPDATA "WhereverCode\V5Hand" }
$HostDir = $StateDir
if (Test-Path $HostDir -PathType Leaf) { Write-Error "设备状态目录不能是文件。"; exit 2 }
New-Item -ItemType Directory -Force -Path $HostDir | Out-Null
$Marker = Join-Path $HostDir ".wherevercode-v5-hand-state"
if (Test-Path $Marker) {
    if ((Get-Content $Marker -Raw).Trim() -ne 'WhereverCode V5 Hand state v1') {
        Write-Error "设备状态目录标记无效。"; exit 2
    }
} elseif ((Test-Path (Join-Path $HostDir "server_key.json")) -or -not (Get-ChildItem $HostDir -Force | Select-Object -First 1)) {
    'WhereverCode V5 Hand state v1' | Set-Content -Path $Marker -NoNewline
} else {
    Write-Error "指定目录不是空目录或已安装的 V5 Hand 目录；为避免覆盖其他文件，安装已停止。"; exit 2
}

# --- 版本化 release：releases\<version>\hand_server.py，current-version.txt 指针，
#     保留当前版 + 上一版（回滚），其余删掉。---
$ReleaseDir = Join-Path (Join-Path $HostDir "releases") $Version
New-Item -ItemType Directory -Force -Path $ReleaseDir | Out-Null
Copy-Item $HandPy (Join-Path $ReleaseDir "hand_server.py") -Force
$CurrentFile = Join-Path $HostDir "current-version.txt"
$PrevVersion = $null
if (Test-Path $CurrentFile) { $PrevVersion = (Get-Content $CurrentFile -Raw).Trim() }
$Version | Set-Content -Path $CurrentFile -NoNewline
$ReleasesRoot = Join-Path $HostDir "releases"
foreach ($d in Get-ChildItem $ReleasesRoot -Directory) {
    if ($d.Name -ne $Version -and $d.Name -ne $PrevVersion) {
        Remove-Item $d.FullName -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if (-not $DeviceId) { $DeviceId = $env:COMPUTERNAME }
if (-not $DisplayName) { $DisplayName = $env:COMPUTERNAME }
$CurrentPy = Join-Path $ReleaseDir "hand_server.py"

# --- 启动器：wherevercode-hand.bat。---
$Launcher = Join-Path $HostDir "wherevercode-hand.bat"
@"
@echo off
"$Python" "$CurrentPy" --state-dir "$HostDir" --device-id "$DeviceId" --display-name "$DisplayName" --host 0.0.0.0 --port $Port %*
"@ | Set-Content -Path $Launcher -Encoding ascii

# --- 自启：计划任务（登录时），用户级，不需管理员。建失败只启动本次、明说。---
$TaskName = "WhereverCodeV5Hand"
$Service = "none"
try {
    $Action = New-ScheduledTaskAction -Execute $Launcher
    $Trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
    $Settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
    Register-ScheduledTask -TaskName $TaskName -Action $Action -Trigger $Trigger -Settings $Settings -Force | Out-Null
    Start-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    $Service = "scheduled-task"
} catch {
    Write-Host "计划任务建失败（$_），改用本次启动，重启后请手动跑 $Launcher。"
}

# --- 本次启动（若计划任务没起来）+ 健康检查：端口接通（最多等 20 秒）+ 指纹可读。---
function Test-Port {
    param([int]$P)
    try {
        $c = New-Object System.Net.Sockets.TcpClient
        $r = $c.BeginConnect("127.0.0.1", $P, $null, $null)
        if ($r.AsyncWaitHandle.WaitOne(2000)) { $c.EndConnect($r); $c.Close(); return $true }
        $c.Close(); return $false
    } catch { return $false }
}
$Ok = $false
for ($i = 0; $i -lt 20; $i++) {
    if (Test-Port -P $Port) { $Ok = $true; break }
    if ($i -eq 0) { Start-Process -FilePath $Launcher -WindowStyle Hidden -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 1
}
if (-not $Ok) {
    Write-Error "Hand 服务未在 20 秒内监听端口 $Port；请看 $HostDir\v5-hand.log。"; exit 1
}
$Fingerprint = (& $Python $CurrentPy --state-dir $HostDir fingerprint 2>$null) -join "" -replace '\s',''
if (-not $Fingerprint -or $Fingerprint.Length -ne 64) {
    Write-Error "读不到 Hand 指纹；请看 $HostDir\v5-hand.log。"; exit 1
}
$PairCode = (& $Python $CurrentPy --state-dir $HostDir pair-code 2>$null) -join "" -replace '\s',''
Write-Host "V5_HAND_INSTALLED device_id=$DeviceId fingerprint=$Fingerprint pair_code=$PairCode port=$Port version=$Version state_dir=$HostDir python=$Python service=$Service"
Write-Host "V5 Hand 已安装并启动（$Service）。App 里核对指纹一致、填入配对码 $PairCode 后确认配对。"
