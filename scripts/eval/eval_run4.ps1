$base='http://localhost:8080'
$out=Join-Path $PSScriptRoot 'eval_compare.log'
$raw=Get-Content (Join-Path $PSScriptRoot 'qoorag-eval-dataset.json') -Raw -Encoding UTF8
$token=(Invoke-RestMethod -Uri "$base/api/auth/login" -Method Post -ContentType 'application/json' -Body '{"username":"admin","password":"123456"}' -UseBasicParsing).data.token
function Log($s){ $s | Out-File -Append -Encoding utf8 $out }
function Run($label,$extra){
  try {
    $body='{"kbId":1,"topK":2,"queries":'+$raw+$extra+'}'
    $r=Invoke-RestMethod -Uri "$base/api/admin/eval" -Method Post -ContentType 'application/json; charset=utf-8' -Headers @{Authorization="Bearer $token"} -Body $body -TimeoutSec 150 -UseBasicParsing
    if($r.code -ne 0){ Log ($label+": ERR code="+$r.code) }
    else { Log ($label+": recall="+$r.data.recallAtK+" precision="+$r.data.precisionAtK+" hit="+$r.data.hitRate+" mrr="+$r.data.mrr+" loop="+$r.data.loopSuspectedCount) }
  } catch { Log ($label+": EXCEPTION "+$_.Exception.Message) }
}
Log "LOGIN_OK"
Run "NONE              " ""
Run "HYBRID            " ',"rerankMode":"hybrid","candidatePool":20,"diversityMaxPerDoc":2'
Run "HYB+min0.30       " ',"rerankMode":"hybrid","candidatePool":20,"diversityMaxPerDoc":2,"minScore":0.3'
Run "DIVERSITY         " ',"rerankMode":"diversity","diversityMaxPerDoc":2'
Run "HYB+min0.30+p40   " ',"rerankMode":"hybrid","candidatePool":40,"diversityMaxPerDoc":2,"minScore":0.3'
Log "DONE"
