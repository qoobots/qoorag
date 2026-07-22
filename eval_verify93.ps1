# 验证 cand=200/topK=20 下 Recall 是否达到 93%（请求级覆盖，无需重启）
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:8080'
$token = (Invoke-RestMethod -Uri "$base/api/auth/login" -Method Post -ContentType 'application/json' -Body '{"username":"admin","password":"123456"}' -UseBasicParsing).data.token
$dsText = Get-Content 'd:\05workspaces\qoorag\qoorag-eval-dataset.json' -Raw -Encoding UTF8
$body = "{`"kbId`":1,`"topK`":20,`"candidatePool`":200,`"queries`":$dsText}"
$r = Invoke-RestMethod -Uri "$base/api/admin/eval" -Method Post -ContentType 'application/json; charset=utf-8' -Headers @{Authorization="Bearer $token"} -Body $body -TimeoutSec 120 -UseBasicParsing
Write-Host ("eval code=" + $r.code)
Write-Host ("recall=" + $r.data.recallAtK + " precision=" + $r.data.precisionAtK + " hit=" + $r.data.hitRate + " mrr=" + $r.data.mrr)
