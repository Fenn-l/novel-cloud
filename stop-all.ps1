# ============================================================
# novel-cloud 停止脚本：仅停止本项目的 Java 进程 + Redis
# (MySQL 是系统服务，不在此停止)
# ============================================================
$ErrorActionPreference = 'SilentlyContinue'

# Redis
& 'C:\App\Redis-x64-5.0.14.1\redis-cli.exe' shutdown nosave 2>$null | Out-Null

# 匹配本项目相关 java 进程：业务服务 jar / Nacos / Elasticsearch
$procs = Get-CimInstance Win32_Process -Filter "Name='java.exe'"
foreach ($p in $procs) {
    $cl = $p.CommandLine
    if ($cl -match 'novel-.*-2\.0\.0-SNAPSHOT\.jar' -or
        $cl -match 'xxl-job-admin' -or
        $cl -match 'nacos-server\.jar' -or
        $cl -match 'org\.elasticsearch\.bootstrap') {
        Write-Host ("stopping PID {0}" -f $p.ProcessId)
        Stop-Process -Id $p.ProcessId -Force
    }
}
# RabbitMQ (Erlang)
Get-Process erl,epmd -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process -Filter "Name='cmd.exe'" | Where-Object { $_.CommandLine -match 'rabbitmq-server' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Write-Host 'Stopped novel-cloud services, Nacos, Elasticsearch, RabbitMQ, XXL-Job Admin, Redis.'
