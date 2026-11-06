# ============================================================
# novel-cloud 本地启动脚本 (Windows / PowerShell) —— 优化版
#
# 相比旧版的三点改进：
#   1) 中间件“按需启动”：只在你选的服务真正用到时才起 ES / RabbitMQ
#   2) “真健康检查”：等到 端口/HTTP 就绪才继续，而不是固定 sleep 导致没起来就往下走
#   3) “参数化选服务”：按 Profile 或 -Services 只启动需要的服务，省内存省时间；已启动的自动跳过
#
# 用法示例（在 novel-cloud 目录下执行）：
#   powershell -ExecutionPolicy Bypass -File start-all.ps1                    # 默认 core: gateway,book,user,search
#   powershell -ExecutionPolicy Bypass -File start-all.ps1 -Profile minimal   # 只起 gateway,book,user（最省内存）
#   powershell -ExecutionPolicy Bypass -File start-all.ps1 -Profile full      # 全部 9 个服务
#   powershell -ExecutionPolicy Bypass -File start-all.ps1 -Services gateway,book,user
#   powershell -ExecutionPolicy Bypass -File start-all.ps1 -SkipMiddleware    # 中间件已在跑，只起服务
#   powershell -ExecutionPolicy Bypass -File start-all.ps1 -WithXxl           # 额外起 XXL-Job Admin
# 关闭：
#   powershell -ExecutionPolicy Bypass -File stop-all.ps1
#
# 说明：MySQL 作为系统服务需自行启动（本脚本只做 3306 就绪检查，不负责拉起）。
# ============================================================
param(
    [ValidateSet('minimal', 'core', 'full')]
    [string]$Profile = 'core',
    [string]$Services = '',
    [switch]$SkipMiddleware,
    [switch]$WithXxl
)

$ErrorActionPreference = 'SilentlyContinue'

# ---------- 路径（与本机安装位置保持一致，换机器时改这里即可）----------
$JAVA21     = 'C:\Program Files\Java\jdk-21.0.11\bin\java.exe'
$JAVA8      = 'C:\Program Files\Java\jdk1.8.0_251'
$JAVA8_BIN  = 'C:\Program Files\Java\jdk1.8.0_251\bin\java.exe'
$REDIS      = 'C:\App\Redis-x64-5.0.14.1'
$ES         = 'C:\App\extra\elasticsearch-8.18.1'
$NACOS_HOME = 'C:\yuan\workspace\nacos221\nacos'
$ERLANG     = 'C:\yuan\workspace\erlang'
$RABBITMQ   = 'C:\yuan\workspace\rabbitmq\rabbitmq_server-3.12.12'
$XXL_JAR    = 'C:\yuan\workspace\xxl-job\xxl-job-admin\target\xxl-job-admin-2.3.1.jar'
$BASE       = 'C:\yuan\workspace\novel-cloud'
$LOGS       = 'C:\yuan\workspace\logs'
New-Item -ItemType Directory -Force -Path $LOGS | Out-Null

# ---------- 健康检查工具函数 ----------
function Test-Port($port) { return (Test-NetConnection 127.0.0.1 -Port $port -InformationLevel Quiet) }

# 轮询端口直到就绪，最多等 $sec 秒
function Wait-Port([string]$name, [int]$port, [int]$sec) {
    for ($i = 0; $i -lt $sec; $i++) {
        if (Test-Port $port) { Write-Host ("    [{0}] 端口 {1} 就绪" -f $name, $port); return $true }
        Start-Sleep 1
    }
    Write-Host ("    [{0}] 端口 {1} 在 {2}s 内未就绪！" -f $name, $port, $sec) -ForegroundColor Yellow
    return $false
}

# 轮询 HTTP 直到返回 2xx/3xx，最多等 $sec 秒（用于 ES / Nacos 这类需要“真能响应”的服务）
function Wait-Http([string]$name, [string]$url, [int]$sec) {
    for ($i = 0; $i -lt $sec; $i++) {
        try {
            $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 3 -Method Get
            if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 400) {
                Write-Host ("    [{0}] HTTP 就绪 ({1})" -f $name, $url); return $true
            }
        } catch { }
        Start-Sleep 1
    }
    Write-Host ("    [{0}] HTTP 在 {1}s 内未就绪！" -f $name, $sec) -ForegroundColor Yellow
    return $false
}

# ---------- 服务清单 ----------
$allServices = @(
    @{ name = 'gateway';  port = 8888; jar = "$BASE\novel-gateway\target\novel-gateway-2.0.0-SNAPSHOT.jar" },
    @{ name = 'home';     port = 9001; jar = "$BASE\novel-home\target\novel-home-service-2.0.0-SNAPSHOT.jar" },
    @{ name = 'author';   port = 9010; jar = "$BASE\novel-author\novel-author-service\target\novel-author-service-2.0.0-SNAPSHOT.jar" },
    @{ name = 'book';     port = 9020; jar = "$BASE\novel-book\novel-book-service\target\novel-book-service-2.0.0-SNAPSHOT.jar" },
    @{ name = 'news';     port = 9030; jar = "$BASE\novel-news\novel-news-service\target\novel-news-service-2.0.0-SNAPSHOT.jar" },
    @{ name = 'resource'; port = 9040; jar = "$BASE\novel-resource\target\novel-resource-service-2.0.0-SNAPSHOT.jar" },
    @{ name = 'search';   port = 9050; jar = "$BASE\novel-search\target\novel-search-service-2.0.0-SNAPSHOT.jar" },
    @{ name = 'user';     port = 9060; jar = "$BASE\novel-user\novel-user-service\target\novel-user-service-2.0.0-SNAPSHOT.jar" }
)

# ---------- 选择要启动的服务 ----------
# 说明：monitor 监控中心已下线（与业务主线无关且需额外维护 Spring Security 配置），
#       full 档即为全部业务服务；需要观测能力时用各服务自带的 actuator 端点即可。
$profileSets = @{
    minimal = @('gateway', 'book', 'user')
    core    = @('gateway', 'book', 'user', 'search')
    full    = @('gateway', 'home', 'author', 'book', 'news', 'resource', 'search', 'user')
}
if ([string]::IsNullOrWhiteSpace($Services)) {
    $selected = $profileSets[$Profile]
} else {
    $selected = $Services.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ }
}
$svcToStart = $allServices | Where-Object { $selected -contains $_.name }

# 根据所选服务决定中间件依赖
$needEs     = $selected -contains 'search'                                   # 搜索服务需要 ES
$needRabbit = ($selected -contains 'book') -or ($selected -contains 'search') # 这两个服务含 MQ 生产者/消费者

Write-Host ''
Write-Host '==================== 启动计划 ===================='
Write-Host ("Profile      : {0}" -f $(if ($Services) { 'custom(-Services)' } else { $Profile }))
Write-Host ("服务         : {0}" -f (($svcToStart | ForEach-Object { $_.name }) -join ', '))
Write-Host ("中间件       : Redis, Nacos{0}{1}{2}" -f $(if ($needEs) { ', Elasticsearch' } else { '' }), $(if ($needRabbit) { ', RabbitMQ' } else { '' }), $(if ($WithXxl) { ', XXL-Job' } else { '' }))
Write-Host ("跳过中间件   : {0}" -f $SkipMiddleware)
Write-Host '=================================================='

# ---------- MySQL 前置检查 ----------
if (-not (Test-Port 3306)) {
    Write-Host '[MySQL] 警告：3306 未就绪，请先启动 MySQL（本项目前置依赖）' -ForegroundColor Red
} else {
    Write-Host '[MySQL] 已在运行'
}

# ---------- 中间件（按需 + 健康检查）----------
if (-not $SkipMiddleware) {

    # Redis
    if (-not (Test-Port 6379)) {
        Write-Host '[Redis] starting...'
        Start-Process -FilePath "$REDIS\redis-server.exe" -WorkingDirectory $REDIS -WindowStyle Hidden
        Wait-Port 'Redis' 6379 15 | Out-Null
    } else { Write-Host '[Redis] 已在运行' }

    # Nacos（需要 JDK8，启动完成后恢复原 JAVA_HOME）
    if (-not (Test-Port 8848)) {
        Write-Host '[Nacos 2.2.1] starting standalone...'
        $savedJavaHome = $env:JAVA_HOME
        $env:JAVA_HOME = $JAVA8
        Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', "`"$NACOS_HOME\bin\startup.cmd`" -m standalone" -WorkingDirectory "$NACOS_HOME\bin" -WindowStyle Hidden -RedirectStandardOutput "$LOGS\nacos.out.log" -RedirectStandardError "$LOGS\nacos.err.log"
        Wait-Port 'Nacos' 8848 40 | Out-Null
        Wait-Port 'Nacos-gRPC' 9848 20 | Out-Null
        Wait-Http 'Nacos' 'http://127.0.0.1:8848/nacos/v1/console/health/readiness' 30 | Out-Null
        $env:JAVA_HOME = $savedJavaHome
    } else { Write-Host '[Nacos] 已在运行' }

    # Elasticsearch（仅在选中 search 时启动；限制堆大小，16G 机器更友好）
    if ($needEs) {
        if (-not (Test-Port 9200)) {
            Write-Host '[Elasticsearch] starting (heap -Xms512m -Xmx512m)...'
            $env:ES_JAVA_OPTS = '-Xms512m -Xmx512m'
            Start-Process -FilePath "$ES\bin\elasticsearch.bat" -WorkingDirectory $ES -WindowStyle Hidden -RedirectStandardOutput "$LOGS\es-out.log" -RedirectStandardError "$LOGS\es-err.log"
            Wait-Port 'Elasticsearch' 9200 90 | Out-Null
            Wait-Http 'Elasticsearch' 'http://127.0.0.1:9200/_cluster/health?wait_for_status=yellow&timeout=1s' 30 | Out-Null
        } else { Write-Host '[Elasticsearch] 已在运行' }
    } else { Write-Host '[Elasticsearch] 跳过（未选择 search）' }

    # RabbitMQ（仅在选中 book/search 时启动）
    if ($needRabbit) {
        if (-not (Test-Port 5672)) {
            Write-Host '[RabbitMQ] starting...'
            $env:ERLANG_HOME = $ERLANG
            $env:RABBITMQ_BASE = 'C:\yuan\workspace\rabbitmq\data'
            $savedPath = $env:Path
            $env:Path = "$ERLANG\bin;$RABBITMQ\sbin;$env:Path"
            Start-Process -FilePath "$RABBITMQ\sbin\rabbitmq-server.bat" -WorkingDirectory $RABBITMQ -WindowStyle Hidden -RedirectStandardOutput "$LOGS\rabbit.out.log" -RedirectStandardError "$LOGS\rabbit.err.log"
            Wait-Port 'RabbitMQ' 5672 40 | Out-Null
            Wait-Port 'RabbitMQ-Mgmt' 15672 20 | Out-Null
            $env:Path = $savedPath
        } else { Write-Host '[RabbitMQ] 已在运行' }
    } else { Write-Host '[RabbitMQ] 跳过（未选择 book/search）' }

    # XXL-Job Admin（可选）
    if ($WithXxl) {
        if (-not (Test-Port 8080)) {
            Write-Host '[XXL-Job Admin] starting...'
            Start-Process -FilePath $JAVA8_BIN -ArgumentList '-jar', $XXL_JAR -WindowStyle Hidden -RedirectStandardOutput "$LOGS\xxl-admin.out.log" -RedirectStandardError "$LOGS\xxl-admin.err.log"
            Wait-Port 'XXL-Job' 8080 40 | Out-Null
        } else { Write-Host '[XXL-Job Admin] 已在运行' }
    }

} else {
    Write-Host '[中间件] 已按 -SkipMiddleware 跳过启动'
}

# ---------- 启动服务 ----------
Write-Host ''
foreach ($s in $svcToStart) {
    if (Test-Port $s.port) { Write-Host ("[{0}] 已在运行 (端口 {1})" -f $s.name, $s.port); continue }
    if (-not (Test-Path $s.jar)) { Write-Host ("[{0}] jar 不存在：{1}（请先 mvn -DskipTests package）" -f $s.name, $s.jar) -ForegroundColor Red; continue }
    Write-Host ("[{0}] starting (端口 {1})..." -f $s.name, $s.port)
    $argList = @('-jar', $s.jar)
    if ($s.args) { $argList += $s.args }
    Start-Process -FilePath $JAVA21 -ArgumentList $argList -WindowStyle Hidden -RedirectStandardOutput "$LOGS\$($s.name).out.log" -RedirectStandardError "$LOGS\$($s.name).err.log"
}

# ---------- 等待服务端口就绪并打印状态 ----------
Write-Host ''
Write-Host '等待服务就绪（每个最多 120s，日志见 C:\yuan\workspace\logs）...'
foreach ($s in $svcToStart) { Wait-Port $s.name $s.port 120 | Out-Null }

Write-Host ''
Write-Host '==================== 状态 (127.0.0.1) ===================='
foreach ($s in $svcToStart) {
    $st = if (Test-Port $s.port) { 'UP' } else { 'DOWN' }
    $color = if ($st -eq 'UP') { 'Green' } else { 'Red' }
    Write-Host ("  {0,-9} {1,-5} {2}" -f $s.name, $s.port, $st) -ForegroundColor $color
}
$mw = @(@('redis', 6379), @('nacos', 8848), @('elasticsearch', 9200), @('rabbitmq', 5672), @('xxl-job', 8080), @('mysql', 3306))
foreach ($p in $mw) {
    $st = if (Test-Port $p[1]) { 'UP' } else { 'DOWN' }
    $color = if ($st -eq 'UP') { 'Green' } else { 'DarkGray' }
    Write-Host ("  {0,-13} {1,-5} {2}" -f $p[0], $p[1], $st) -ForegroundColor $color
}
Write-Host '=========================================================='
Write-Host ('网关入口: http://127.0.0.1:8888/api/front/...')
