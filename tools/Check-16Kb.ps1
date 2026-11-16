<#
.SYNOPSIS
    检查 Android 原生库（.so）是否满足 16 KB 页对齐。

.DESCRIPTION
    Android 15 起，部分设备（含部分 Android 16 机型）使用 16 KB 内存页。
    这类设备要求 .so 的 ELF 段按 16 KB 对齐（p_align >= 16384），
    否则 App 一加载 native 库就崩溃，且无法在真机上补救。

    本脚本直接解析 ELF 程序头，不依赖 NDK / readelf / Python。
    支持输入：.apk / .aab / .aar / .zip / .so 文件，或一个目录。

.EXAMPLE
    .\Check-16Kb.ps1 .\app\build\outputs\apk\debug\app-debug.apk

.EXAMPLE
    .\Check-16Kb.ps1 D:\sdk\AMap_3DMap_V11.3.100.aar
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Path,

    # 默认只看名字里含这些关键字的库；加 -All 检查全部 .so
    [string[]]$Only = @('AMap', 'amap'),

    [switch]$All
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

function Get-ElfLoadAlign {
    param([byte[]]$Bytes)

    if ($null -eq $Bytes -or $Bytes.Length -lt 52) { throw '文件太小，不是有效的 ELF' }
    if (-not ($Bytes[0] -eq 0x7F -and $Bytes[1] -eq 0x45 -and $Bytes[2] -eq 0x4C -and $Bytes[3] -eq 0x46)) {
        throw '文件头不是 ELF magic（可能不是 .so）'
    }

    $is64 = ($Bytes[4] -eq 2)
    $isLittleEndian = ($Bytes[5] -eq 1)
    if (-not $isLittleEndian) { throw '暂不支持大端 ELF' }
    if ($is64 -and $Bytes.Length -lt 64) { throw 'ELF64 头不完整' }

    if ($is64) {
        $phoff = [BitConverter]::ToUInt64($Bytes, 32)
        $phentsize = [BitConverter]::ToUInt16($Bytes, 54)
        $phnum = [BitConverter]::ToUInt16($Bytes, 56)
        $alignOffsetInPhdr = 48
    }
    else {
        $phoff = [uint64][BitConverter]::ToUInt32($Bytes, 28)
        $phentsize = [BitConverter]::ToUInt16($Bytes, 42)
        $phnum = [BitConverter]::ToUInt16($Bytes, 44)
        $alignOffsetInPhdr = 28
    }

    $aligns = New-Object 'System.Collections.Generic.List[uint64]'
    for ($i = 0; $i -lt $phnum; $i++) {
        $base = [int]($phoff + [uint64]($i * $phentsize))
        if (($base + $phentsize) -gt $Bytes.Length) { break }
        $pType = [BitConverter]::ToUInt32($Bytes, $base)
        if ($pType -ne 1) { continue }   # 只看 PT_LOAD
        if ($is64) {
            $align = [BitConverter]::ToUInt64($Bytes, $base + $alignOffsetInPhdr)
        }
        else {
            $align = [uint64][BitConverter]::ToUInt32($Bytes, $base + $alignOffsetInPhdr)
        }
        $aligns.Add($align)
    }
    return , $aligns
}

# ---------- 收集待检查的 .so ----------
$items = New-Object 'System.Collections.Generic.List[object]'
$resolved = (Resolve-Path -LiteralPath $Path).ProviderPath

if (Test-Path -LiteralPath $resolved -PathType Container) {
    Get-ChildItem -LiteralPath $resolved -Recurse -File -Filter '*.so' | ForEach-Object {
        $items.Add([pscustomobject]@{ Name = $_.FullName; Bytes = [System.IO.File]::ReadAllBytes($_.FullName) })
    }
}
elseif ($resolved -match '\.so$') {
    $items.Add([pscustomobject]@{ Name = $resolved; Bytes = [System.IO.File]::ReadAllBytes($resolved) })
}
else {
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    $zip = [System.IO.Compression.ZipFile]::OpenRead($resolved)
    try {
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName -notmatch '\.so$') { continue }
            $ms = New-Object System.IO.MemoryStream
            $stream = $entry.Open()
            try { $stream.CopyTo($ms) } finally { $stream.Dispose() }
            $items.Add([pscustomobject]@{ Name = $entry.FullName; Bytes = $ms.ToArray() })
            $ms.Dispose()
        }
    }
    finally { $zip.Dispose() }
}

if (-not $All) {
    $filtered = New-Object 'System.Collections.Generic.List[object]'
    foreach ($it in $items) {
        $leaf = ($it.Name -split '[\\/]')[-1]
        $hit = $false
        foreach ($k in $Only) { if ($leaf -like "*$k*") { $hit = $true; break } }
        if ($hit) { $filtered.Add($it) }
    }
    $items = $filtered
}

if ($items.Count -eq 0) {
    Write-Host '没有找到要检查的 .so。' -ForegroundColor Yellow
    Write-Host '如果确认包里有 .so，请加 -All 再试一次。' -ForegroundColor Yellow
    exit 2
}

# ---------- 检查并输出 ----------
Write-Host ''
Write-Host ('检查目标：' + $resolved) -ForegroundColor Cyan
Write-Host ('共 ' + $items.Count + ' 个 native 库') -ForegroundColor Cyan
Write-Host ''

$allPass = $true
$rows = New-Object 'System.Collections.Generic.List[object]'

foreach ($it in $items) {
    $leaf = ($it.Name -split '[\\/]')[-1]
    $abi = '?'
    if ($it.Name -match '(arm64-v8a|armeabi-v7a|x86_64|x86)') { $abi = $Matches[1] }

    $alignText = ''
    $verdict = ''
    $ok = $false

    try {
        $aligns = Get-ElfLoadAlign -Bytes $it.Bytes
        if ($aligns.Count -eq 0) {
            $alignText = '(无 PT_LOAD)'
            $verdict = '跳过'
            $ok = $true
        }
        else {
            $min = ($aligns | Measure-Object -Minimum).Minimum
            $alignText = [string]$min
            if ($min -ge 16384) { $verdict = '通过 16KB'; $ok = $true }
            else { $verdict = '不通过 (' + [string]([int]$min / 1024) + 'KB)'; $ok = $false }
        }
    }
    catch {
        $alignText = '-'
        $verdict = '解析失败：' + $_.Exception.Message
        $ok = $false
    }

    if (-not $ok) { $allPass = $false }
    $rows.Add([pscustomobject]@{
            Abi     = $abi
            Library = $leaf
            Align   = $alignText
            Verdict = $verdict
            Ok      = $ok
        })
}

$rows | Format-Table -AutoSize -Property Abi, Library, Align, Verdict | Out-String -Width 200 | Write-Host

if ($allPass) {
    Write-Host '结论：全部通过 16 KB 页对齐，可以在这类设备上运行。' -ForegroundColor Green
    Write-Host 'RESULT: PASS (all PT_LOAD p_align >= 16384)'
    exit 0
}
else {
    Write-Host '结论：存在 4KB 对齐的库，在 16KB 页设备上加载会崩溃，不能继续。' -ForegroundColor Red
    Write-Host 'RESULT: FAIL (found PT_LOAD p_align < 16384)'
    exit 1
}
