# 重新生成 protobuf Java 代码
#
# 生成结果已提交在 app/src/main/java/hbb/ 下，正常构建不需要跑这个脚本。
# 只有当上游 .proto 变更时才需要重新生成。
#
# 用法：
#   .\tools\regen-protos.ps1                       # 自动在 gradle 缓存里找 protoc 3.20.1
#   .\tools\regen-protos.ps1 -ProtocPath C:\x\protoc.exe
#   .\tools\regen-protos.ps1 -ProtoRoot ..\        # 仓库根（上游 .proto 在它下面的 host\ 里）
param(
    [string]$ProtocPath = "",
    [string]$ProtoRoot = ""
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $ProtoRoot) { $ProtoRoot = Split-Path -Parent $projectRoot }
if (-not (Test-Path $ProtoRoot)) { throw "找不到上游仓库根目录: $ProtoRoot" }

# 版本必须与 app/build.gradle 里的 protobuf-javalite 一致，否则生成代码与运行时 API 不匹配。
$ProtocVersion = '3.20.1'

if (-not $ProtocPath) {
    $cacheRoot = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\com.google.protobuf\protoc"
    if (Test-Path $cacheRoot) {
        $candidate = Get-ChildItem $cacheRoot -Recurse -Filter "*windows-x86_64.exe" -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like "protoc-$ProtocVersion-*" } |
            Select-Object -First 1
        if ($candidate) { $ProtocPath = $candidate.FullName }
    }
}
if (-not $ProtocPath -or -not (Test-Path $ProtocPath)) {
    throw "找不到 protoc $ProtocVersion。请从 https://repo1.maven.org/maven2/com/google/protobuf/protoc/$ProtocVersion/ 下载 protoc-$ProtocVersion-windows-x86_64.exe，然后用 -ProtocPath 指定。"
}

$messageProto  = Join-Path $ProtoRoot 'host\libs\base\protos\message.proto'
$rendezvousProto = Join-Path $ProtoRoot 'host\libs\hbb_common\protos\rendezvous.proto'
foreach ($p in @($messageProto, $rendezvousProto)) {
    if (-not (Test-Path $p)) { throw "缺少协议文件: $p" }
}

$outDir = Join-Path $projectRoot 'app\src\main\java'
Write-Host "protoc   : $ProtocPath"
Write-Host "输出目录 : $outDir"

# lite 运行时，与 protobuf-javalite 匹配。
& $ProtocPath `
    "-I" (Split-Path -Parent $messageProto) `
    "-I" (Split-Path -Parent $rendezvousProto) `
    "--java_out=lite:$outDir" `
    $messageProto $rendezvousProto

if ($LASTEXITCODE -ne 0) { throw "protoc 执行失败，退出码 $LASTEXITCODE" }

Get-ChildItem (Join-Path $outDir 'hbb') -Filter '*.java' |
    ForEach-Object { Write-Host ("生成 {0} ({1:N0} KB)" -f $_.Name, ($_.Length / 1KB)) }
Write-Host "完成。"
