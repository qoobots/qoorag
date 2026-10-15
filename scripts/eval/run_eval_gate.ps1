<#
.SYNOPSIS
    RAG 评测回归门禁（RAG-004）。

.DESCRIPTION
    改造前：评测靠人肉执行 scripts/eval/*.ps1，报告只打印到控制台，不落库、不可对比，
    "改完之后指标到底涨没涨"永远说不清，更没有门禁——回归悄悄上线。

    本脚本把评测接入 CI：
      1. 登录获取令牌；
      2. 触发一次评测运行（POST /api/admin/eval/run），结果落库并带环境指纹；
      3. 与历史基线对比（同一脚本内完成，无需额外接口）；
      4. 任一指标跌破阈值即以非 0 退出码失败，阻断合并/发布。

.PARAMETER BaseUrl
    应用地址，默认 http://127.0.0.1:8080

.PARAMETER KbId
    知识库 ID（必填）

.PARAMETER DatasetId
    评测集 ID（必填）。可通过 GET /api/admin/eval/datasets?kbId=<id> 查询。

.PARAMETER TopK
    topK，默认 5

.PARAMETER WithGen
    是否评估生成端指标（会调用 Judge LLM，有成本），默认关闭。

.PARAMETER ThresholdFile
    阈值配置 JSON，默认 scripts/eval/gate-thresholds.json

.PARAMETER BaselineFile
    基线快照输出/对比文件，默认 scripts/eval/baseline.json。
    -UpdateBaseline 时把本次结果写为该文件。

.PARAMETER UpdateBaseline
    把本次运行结果设为新基线（首次接入或策略 intentional 变更时使用）。

.EXAMPLE
    .\run_eval_gate.ps1 -KbId 1 -DatasetId 3
    与基线对比，指标跌破阈值则退出码 1。

.EXAMPLE
    .\run_eval_gate.ps1 -KbId 1 -DatasetId 3 -UpdateBaseline
    首次接入：把当前指标设为基线。

.NOTES
    退出码：0 = 通过；1 = 指标回归；2 = 运行失败/参数错误。
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [Parameter(Mandatory = $true)][long]$KbId,
    [Parameter(Mandatory = $true)][long]$DatasetId,
    [int]$TopK = 5,
    [switch]$WithGen,
    [string]$ThresholdFile = "scripts\eval\gate-thresholds.json",
    [string]$BaselineFile = "scripts\eval\baseline.json",
    [switch]$UpdateBaseline,
    [string]$Username = "admin",
    [string]$Password = "123456"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Write-Step([string]$msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }
function Write-Ok([string]$msg)   { Write-Host "  [PASS] $msg" -ForegroundColor Green }
function Write-Bad([string]$msg)  { Write-Host "  [FAIL] $msg" -ForegroundColor Red }
function Write-Warn([string]$msg) { Write-Host "  [WARN] $msg" -ForegroundColor Yellow }

# ---------------------------------------------------------------- 1. 登录
Write-Step "登录"
try {
    $login = Invoke-RestMethod -Uri "$BaseUrl/api/auth/login" -Method Post -TimeoutSec 15 `
        -ContentType "application/json" `
        -Body (@{ username = $Username; password = $Password } | ConvertTo-Json)
    if ($login.code -ne 0) { throw "登录返回 code=$($login.code)" }
    $token = $login.data.token
    Write-Ok "登录成功（userId=$($login.data.userId)）"
} catch {
    Write-Bad "登录失败: $($_.Exception.Message)"
    exit 2
}
$headers = @{ Authorization = "Bearer $token" }

# ---------------------------------------------------------------- 2. 触发评测
Write-Step "触发评测运行 (datasetId=$DatasetId, topK=$TopK, withGen=$WithGen)"
$runBody = @{
    kbId       = $KbId
    datasetId  = $DatasetId
    topK       = $TopK
    rerank     = $true
    withGen    = [bool]$WithGen
} | ConvertTo-Json
try {
    $resp = Invoke-RestMethod -Uri "$BaseUrl/api/admin/eval/run" -Method Post -TimeoutSec 600 `
        -Headers $headers -ContentType "application/json" -Body $runBody
    if ($resp.code -ne 0) { throw "评测返回 code=$($resp.code) msg=$($resp.message)" }
    $run = $resp.data.run
    Write-Ok "运行完成 runId=$($run.id)，耗时 $($run.durationMs)ms"
} catch {
    Write-Bad "评测运行失败: $($_.Exception.Message)"
    exit 2
}

Write-Host "  --- 本次指标 ---"
Write-Host ("  recallAtK       = {0:N4}" -f $run.recallAtK)
Write-Host ("  precisionAtK    = {0:N4}" -f $run.precisionAtK)
Write-Host ("  hitRate         = {0:N4}" -f $run.hitRate)
Write-Host ("  mrr             = {0:N4}" -f $run.mrr)
if ($null -ne $run.faithfulness) {
    Write-Host ("  faithfulness    = {0:N4}" -f $run.faithfulness)
    Write-Host ("  answerRelevancy = {0:N4}" -f $run.answerRelevancy)
}
Write-Host "  --- 环境指纹 ---"
Write-Host "  embeddingModel=$($run.embeddingModel) chatModel=$($run.chatModel)"
Write-Host "  strategy=$($run.strategySnapshot) rerankEnabled=$($run.rerankEnabled)"

if ($run.trustworthy -eq $false) {
    Write-Warn "指标自检不可信（平均 Recall 与 Precision 双百，疑似标注污染），"
    Write-Warn "请检查评测集标注质量后再据此判断回归。"
}

# ---------------------------------------------------------------- 3. 更新基线
if ($UpdateBaseline) {
    Write-Step "更新基线快照"
    $snapshot = [ordered]@{
        runId             = $run.id
        createdAt         = (Get-Date).ToString("o")
        kbId              = $KbId
        datasetId         = $DatasetId
        topK              = $TopK
        recallAtK         = $run.recallAtK
        precisionAtK      = $run.precisionAtK
        hitRate           = $run.hitRate
        mrr               = $run.mrr
        faithfulness      = $run.faithfulness
        answerRelevancy   = $run.answerRelevancy
        embeddingModel    = $run.embeddingModel
        chatModel         = $run.chatModel
        strategySnapshot  = $run.strategySnapshot
    }
    $dir = Split-Path -Parent $BaselineFile
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    $snapshot | ConvertTo-Json -Depth 5 | Set-Content -Path $BaselineFile -Encoding utf8
    Write-Ok "基线已写入 $BaselineFile"
    exit 0
}

# ---------------------------------------------------------------- 4. 加载阈值与基线
Write-Step "加载阈值与基线"
if (-not (Test-Path $ThresholdFile)) {
    Write-Warn "阈值文件不存在: $ThresholdFile —— 跳过门禁，仅打印指标"
    exit 0
}
$thresholds = Get-Content $ThresholdFile -Raw -Encoding utf8 | ConvertFrom-Json
if (-not (Test-Path $BaselineFile)) {
    Write-Warn "基线文件不存在: $BaselineFile"
    Write-Warn "请先执行: .\run_eval_gate.ps1 -KbId $KbId -DatasetId $DatasetId -UpdateBaseline"
    exit 0
}
$baseline = Get-Content $BaselineFile -Raw -Encoding utf8 | ConvertFrom-Json
Write-Ok "基线 runId=$($baseline.runId)（$($baseline.createdAt)）"

# 环境指纹变化时提示（指标变化可能来自策略而非代码）
$envChanged = ($baseline.strategySnapshot -ne $run.strategySnapshot) -or
              ($baseline.embeddingModel -ne $run.embeddingModel)
if ($envChanged) {
    Write-Warn "环境指纹与基线不一致（策略或模型已变更），指标差异可能来自配置而非代码回归。"
}

# ---------------------------------------------------------------- 5. 逐项对比
Write-Step "指标门禁"
$failed = 0
$metricMap = [ordered]@{
    "recallAtK"       = $run.recallAtK
    "precisionAtK"    = $run.precisionAtK
    "hitRate"         = $run.hitRate
    "mrr"             = $run.mrr
    "faithfulness"    = $run.faithfulness
    "answerRelevancy" = $run.answerRelevancy
}

foreach ($prop in $thresholds.PSObject.Properties) {
    $name = $prop.Name
    if (-not $metricMap.Contains($name)) { continue }
    $current = $metricMap[$name]
    if ($null -eq $current) {
        Write-Warn "$name 未评测（跳过该项）"
        continue
    }
    $minDelta = [double]$prop.Value.maxDrop
    $absMin   = [double]$prop.Value.min
    $base     = [double]$baseline.$name
    $delta    = $current - $base

    $dropOk = ($delta -ge -1 * $minDelta)
    $absOk  = ($current -ge $absMin)
    if ($dropOk -and $absOk) {
        Write-Ok ("{0,-16} current={1:N4} baseline={2:N4} delta={3:+0.0000;-0.0000} (maxDrop={4}, min={5})" -f `
                $name, $current, $base, $delta, $minDelta, $absMin)
    } else {
        if (-not $absOk) {
            Write-Bad ("{0,-16} current={1:N4} 低于绝对下限 {2}" -f $name, $current, $absMin)
        }
        if (-not $dropOk) {
            Write-Bad ("{0,-16} current={1:N4} baseline={2:N4} 跌幅 {3:N4} 超过允许 {4}" -f `
                    $name, $current, $base, $delta, $minDelta)
        }
        $failed++
    }
}

Write-Step "结果"
if ($failed -gt 0) {
    Write-Bad "$failed 项指标未过门禁，判定为回归。"
    Write-Host "  若是策略/模型的预期变更，请用 -UpdateBaseline 更新基线并说明原因。"
    exit 1
}
Write-Ok "全部指标通过门禁。"
exit 0
