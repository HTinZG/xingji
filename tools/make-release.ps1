$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$repo = "HTinZG/xingji"
$tag = "v0.3.0"
$proxy = "http://127.0.0.1:7897"
$apk = "C:\Users\27603\Downloads\path\xingji-0.3.0-release.apk"
$sha = (Get-FileHash $apk -Algorithm SHA256).Hash

# ---- 从 Git Credential Manager 取 token，全程不打印 ----
$credInput = "protocol=https`nhost=github.com`n`n"
$cred = $credInput | & "C:\Program Files\Git\mingw64\bin\git-credential-manager.exe" get 2>$null
$token = ($cred | Where-Object { $_ -like "password=*" }) -replace "^password=", ""
$user  = ($cred | Where-Object { $_ -like "username=*" }) -replace "^username=", ""
if (-not $token) { throw "取不到 GitHub 凭据" }
Write-Output "凭据: 用户=$user  token 长度=$($token.Length)"

$headers = @{
    Authorization = "token $token"
    Accept        = "application/vnd.github+json"
    "User-Agent"  = "xingji-release-script"
}

$body = @{
    tag_name         = $tag
    name           = "行迹 0.3.0"
    draft          = $false
    prerelease     = $false
    generate_release_notes = $false
    body = @"
本地优先的个人轨迹记录 App。**这个包只给自己和家人用**，不上架、无账号、无服务器。

## 这一版做了什么

**记录**
- 记录页改成地图主视图：地图占满屏幕，实时画轨迹 + 光标跟随，距离/时长退到顶部小卡片
- 结束行程时显示「行程已保存」提示（2.2 秒淡出，不遮挡地图）
- 90 秒没有新定位时，页面顶部红条提示「定位已中断 N 分钟」
- 结束时间按**最后一个轨迹点**收尾，服务被系统杀掉时不再虚报时长

**视觉**
- 全新视觉系统：日落橙 + 近黑底，明暗双主题跟随系统
- 数字统一用 Barlow Condensed（SIL 开源），中文自动回落系统字体
- 列表/详情/分段/设置/AI 全部重做：无卡片盒子，靠细线和超大数字建立层次

**数据**
- 列表页去掉逐条查询（N+1），固定 5 次批量查询
- 新增「导出全部行程」：一键打包成 zip，每条行程一个 GPX
- 自动分段不再静默覆盖手动修改，会先弹确认

**省电与后台**
- 设置页新增「后台运行设置」，一键跳到系统的电量详情页
- 说明：HyperOS 的「省电策略」必须选**无限制**，否则后台定位会被冻结（详见下面的已知问题）

## 安装

APK 是 release 签名包，直接安装覆盖即可（会保留数据）：

```
adb install -r xingji-0.3.0.apk
```

签名指纹 SHA-1：`A4:41:AD:BA:A6:DF:DE:F1:A5:B1:44:F4:A3:62:54:1D:B8:31:91:DE`

APK SHA-256：
`$sha`

## 已知问题

- **户外连续 1 小时记录还没做完整验证**（唯一没打勾的测试项）
- 分享图只有竖版
- 停留时段内的轨迹点不会进 GPX 轨迹线，只留一个 waypoint

## 升级提醒

如果是从 0.2.0 覆盖安装：数据保留，但如果你之前遇到过「记录半小时只有一个点」，请到
**设置 → 后台运行设置**，把省电策略改成「无限制」。
"@
} | ConvertTo-Json -Depth 5

Write-Output "=== 创建 release ==="
$rel = Invoke-RestMethod -Method Post `
    -Uri "https://api.github.com/repos/$repo/releases" `
    -Headers $headers -Body $body -ContentType "application/json" `
    -Proxy $proxy -TimeoutSec 120
Write-Output "release id = $($rel.id)"
Write-Output "html_url   = $($rel.html_url)"

Write-Output "=== 上传 APK（44.5 MB，可能要一会儿）==="
$uploadUri = "https://uploads.github.com/repos/$repo/releases/$($rel.id)/assets?name=xingji-0.3.0.apk"
$asset = Invoke-RestMethod -Method Post -Uri $uploadUri -Headers $headers `
    -InFile $apk -ContentType "application/vnd.android.package-archive" `
    -Proxy $proxy -TimeoutSec 600
Write-Output "asset: $($asset.name)  $([math]::Round($asset.size/1MB,2)) MB  state=$($asset.state)"

Write-Output "=== 复核 ==="
$check = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/tags/$tag" -Headers $headers -Proxy $proxy -TimeoutSec 60
Write-Output "tag=$($check.tag_name)  name=$($check.name)  draft=$($check.draft)  prerelease=$($check.prerelease)"
$check.assets | ForEach-Object { Write-Output ("  附件: {0}  {1:N2} MB" -f $_.name, ($_.size/1MB)) }
