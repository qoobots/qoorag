# 检索配置扫描：在已生效的 hybrid 默认下，扫描 candidate-pool 与 topK 对 Recall 的影响
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File eval_sweep.ps1
$ErrorActionPreference = 'Continue'
$base = 'http://127.0.0.1:8080'
$dsPath = Join-Path $PSScriptRoot 'qoorag-eval-dataset.json'
$log = Join-Path $PSScriptRoot 'eval_sweep.log'
$dsText = Get-Content $dsPath -Raw -Encoding UTF8

# 登录
$token = $null
for ($i=0; $i -lt 3; $i++) {
    try {
        $login = Invoke-RestMethod -Uri "$base/api/auth/login" -Method Post -ContentType 'application/json' `
            -Body '{"username":"admin","password":"123456"}' -TimeoutSec 15 -UseBasicParsing
        $token = $login.data.token
        if ($token) { break }
    } catch { Start-Sleep -Seconds 2 }
}
if (-not $token) { "LOGIN_FAILED" | Out-File $log -Encoding utf8; exit 1 }

# 配置组合：[topK, candidatePool, label]
$configs = @(
    @(2, 10,  'HYB cand10 topK2'),
    @(2, 20,  'HYB cand20 topK2'),
    @(2, 40,  'HYB cand40 topK2'),
    @(3, 20,  'HYB cand20 topK3'),
    @(5, 20,  'HYB cand20 topK5'),
    @(5, 40,  'HYB cand40 topK5')
)

$lines = @()
$lines += "=== SWEEP START $(Get-Date -Format 'HH:mm:ss') ==="
foreach ($cfg in $configs) {
    $topK = $cfg[0]; $cp = $cfg[1]; $label = $cfg[2]
    $body = "{`"kbId`":1,`"topK`":$topK,`"rerankMode`":`"hybrid`",`"candidatePool`":$cp,`"queries`":$dsText}"
    try {
        $r = Invoke-RestMethod -Uri "$base/api/admin/eval" -Method Post `
            -ContentType 'application/json; charset=utf-8' `
            -Headers @{Authorization="Bearer $token"} -Body $body -TimeoutSec 120 -UseBasicParsing
        if ($r.code -eq 0) {
            $line = ("{0,-16} recall={1,6} prec={2,6} hit={3,6} mrr={4,6}" -f $label, $r.data.recallAtK, $r.data.precisionAtK, $r.data.hitRate, $r.data.mrr)
        } else {
            $line = ("{0,-16} ERR code={1} msg={2}" -f $label, $r.code, $r.message)
        }
    } catch {
        $line = ("{0,-16} EXCEPTION {1}" -f $label, $_.Exception.Message)
    }
    $lines += $line
    $line | Out-File $log -Append -Encoding utf8
    Start-Sleep -Seconds 1
}
$lines += "=== SWEEP END $(Get-Date -Format 'HH:mm:ss') ==="
$lines | ForEach-Object { Write-Host $_ }
