# 第三轮扫描：放大 candidate-pool 找 Recall 天花板（候选池太小会漏掉向量排名靠后的相关文档）
$ErrorActionPreference = 'Continue'
$base = 'http://127.0.0.1:8080'
$dsPath = 'd:\05workspaces\qoorag\qoorag-eval-dataset.json'
$log = 'd:\05workspaces\qoorag\eval_sweep3.log'
$dsText = Get-Content $dsPath -Raw -Encoding UTF8

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

$configs = @(
    @(20, 100, 'HYB cand100 topK20'),
    @(20, 200, 'HYB cand200 topK20'),
    @(30, 200, 'HYB cand200 topK30'),
    @(50, 300, 'HYB cand300 topK50')
)

$lines = @()
$lines += "=== SWEEP3 START $(Get-Date -Format 'HH:mm:ss') ==="
foreach ($cfg in $configs) {
    $topK = $cfg[0]; $cp = $cfg[1]; $label = $cfg[2]
    $body = "{`"kbId`":1,`"topK`":$topK,`"rerankMode`":`"hybrid`",`"candidatePool`":$cp,`"queries`":$dsText}"
    try {
        $r = Invoke-RestMethod -Uri "$base/api/admin/eval" -Method Post `
            -ContentType 'application/json; charset=utf-8' `
            -Headers @{Authorization="Bearer $token"} -Body $body -TimeoutSec 180 -UseBasicParsing
        if ($r.code -eq 0) {
            $line = ("{0,-18} recall={1,6} prec={2,6} hit={3,6} mrr={4,6}" -f $label, $r.data.recallAtK, $r.data.precisionAtK, $r.data.hitRate, $r.data.mrr)
        } else {
            $line = ("{0,-18} ERR code={1} msg={2}" -f $label, $r.code, $r.message)
        }
    } catch {
        $line = ("{0,-18} EXCEPTION {1}" -f $label, $_.Exception.Message)
    }
    $lines += $line
    $line | Out-File $log -Append -Encoding utf8
    Start-Sleep -Seconds 1
}
$lines += "=== SWEEP3 END $(Get-Date -Format 'HH:mm:ss') ==="
$lines | ForEach-Object { Write-Host $_ }
