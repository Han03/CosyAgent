# ============================================================
# CosyAgent 一键启动脚本（多模块聚合版）
# 用法：
#   powershell -ExecutionPolicy Bypass -File C:\MyProjects\CosyAgent\start.ps1        # 构建 + 启动
#   powershell -ExecutionPolicy Bypass -File C:\MyProjects\CosyAgent\start.ps1 -SkipBuild   # 跳过构建直接启动
#
# 流程：① 中间件按依赖序拉起（已运行跳过）
#       ② 根目录 Maven 构建 4 个模块（-SkipBuild 跳过）
#       ③ 停旧后端 → 带 SkyWalking agent 启动 4 个服务
#       ④ 健康探测（端口 + 网关对话）
#       ⑤ 输出全部控制台地址
# ============================================================
param([switch]$SkipBuild)
$ErrorActionPreference = 'Continue'

# ---------- 配置区 ----------
$JAVA25  = 'C:\Program Files\Java\jdk-25.0.4.1\bin\java.exe'
$JAVA21  = 'C:\app\jdk-21.0.2\bin\java.exe'
$JDK25_HOME = 'C:\Program Files\Java\jdk-25.0.4.1'
$JDK21_HOME = 'C:\app\jdk-21.0.2'
$SKY_AGENT = 'C:\app\skywalking-agent\skywalking-agent.jar'
$ROOT    = 'C:\MyProjects\CosyAgent'
$MVN     = 'C:\app\apache-maven-3.9.16\bin\mvn.cmd'
$LOGDIR  = 'C:\app\ai2probe'

$backend = @(
    @{ n = 'cosy-gateway';    p = 8080 },
    @{ n = 'cosy-agent';      p = 8280 },
    @{ n = 'cosy-knowledge';  p = 8281 },
    @{ n = 'cosy-capability'; p = 8282 }
)

function Test-Port([int]$port) {
    [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1)
}
function Wait-Port([int]$port, [int]$sec = 60) {
    for ($i = 0; $i -lt $sec; $i++) {
        if (Test-Port $port) { return $true }
        Start-Sleep -Seconds 1
    }
    return $false
}

Write-Host '========== [1/5] 中间件启动 ==========' -ForegroundColor Cyan

# ① MySQL / Redis（Windows 服务）
if (-not (Test-Port 3306)) { try { Start-Service MySQL80; Write-Host '  MySQL: 启动服务' } catch { Write-Host "  MySQL: 启动失败 $_" } } else { Write-Host '  MySQL: 已运行' }
if (-not (Test-Port 6379)) { try { Start-Service Redis; Write-Host '  Redis: 启动服务' } catch { Write-Host "  Redis: 启动失败 $_" } } else { Write-Host '  Redis: 已运行' }

# ② Nacos（8848 配置中心 / 8849 控制台）
if (-not (Test-Port 8848)) {
    Write-Host '  Nacos: 启动中...'
    Start-Process -FilePath $JAVA25 -ArgumentList @(
        '-Xms512m','-Xmx512m','-Xmn256m',
        '-Dnacos.standalone=true','-Dnacos.deployment.type=merged',
        '-Dloader.path=C:\app\nacos/plugins,C:\app\nacos/plugins/health,C:\app\nacos/plugins/cmd',
        '-Dnacos.home=C:\app\nacos',
        '-jar','C:\app\nacos\target\nacos-server.jar',
        '--spring.config.additional-location=file:C:\app\nacos/conf/') -WindowStyle Hidden
    if (Wait-Port 8848 90) { Write-Host '  Nacos: 已就绪' } else { Write-Host '  Nacos: 启动超时(90s)' }
} else { Write-Host '  Nacos: 已运行' }

# ③ RocketMQ（namesrv 9876 → broker 10911）
if (-not (Test-Port 9876)) {
    Write-Host '  RocketMQ NameSrv: 启动中...'
    Start-Process cmd -ArgumentList '/c','C:\app\rocketmq\rmq\bin\mqnamesrv.cmd' -WindowStyle Hidden
    if (Wait-Port 9876 60) { Write-Host '  NameSrv: 已就绪' } else { Write-Host '  NameSrv: 启动超时(60s)' }
} else { Write-Host '  RocketMQ NameSrv: 已运行' }
if (-not (Test-Port 10911)) {
    Write-Host '  RocketMQ Broker: 启动中...'
    Start-Process cmd -ArgumentList '/c','C:\app\rocketmq\rmq\bin\mqbroker.cmd','-n','localhost:9876','autoCreateTopicEnable=true' -WindowStyle Hidden
    if (Wait-Port 10911 60) { Write-Host '  Broker: 已就绪' } else { Write-Host '  Broker: 启动超时(60s)' }
} else { Write-Host '  RocketMQ Broker: 已运行' }

# ④ Elasticsearch / Kibana / Filebeat
if (-not (Test-Port 9200)) {
    Write-Host '  Elasticsearch: 启动中...'
    Start-Process 'C:\app\elk\elasticsearch-8.17.4\bin\elasticsearch.bat' -WindowStyle Hidden
    if (Wait-Port 9200 120) { Write-Host '  ES: 已就绪' } else { Write-Host '  ES: 启动超时(120s)' }
} else { Write-Host '  Elasticsearch: 已运行' }
if (-not (Test-Port 5601)) {
    Write-Host '  Kibana: 启动中...'
    Start-Process 'C:\app\elk\kibana-8.17.4\bin\kibana.bat' -WindowStyle Hidden
    if (Wait-Port 5601 120) { Write-Host '  Kibana: 已就绪' } else { Write-Host '  Kibana: 启动超时(120s)' }
} else { Write-Host '  Kibana: 已运行' }
if (-not (Get-Process -Name 'filebeat' -ErrorAction SilentlyContinue)) {
    Write-Host '  Filebeat: 启动中...'
    Start-Process 'C:\app\elk\filebeat-8.17.4-windows-x86_64\filebeat.exe' -ArgumentList '-c','C:\app\filebeat.yml','-e' -WindowStyle Hidden
    Start-Sleep -Seconds 3
    if (Get-Process -Name 'filebeat' -ErrorAction SilentlyContinue) { Write-Host '  Filebeat: 已启动' } else { Write-Host '  Filebeat: 启动失败' }
} else { Write-Host '  Filebeat: 已运行' }

# ⑤ SkyWalking OAP + UI（OAP 服务端 Groovy 脚本需 JDK≤21，JDK25 编译失败；UI 同样 JDK21）
if (-not (Test-Port 12800)) {
    Write-Host '  SkyWalking OAP: 启动中...'
    $oldJh = $env:JAVA_HOME
    $env:JAVA_HOME = $JDK21_HOME
    Start-Process cmd -ArgumentList '/c','C:\app\apache-skywalking-apm-bin\bin\oapService.bat' -WindowStyle Hidden
    $env:JAVA_HOME = $oldJh
    if (Wait-Port 12800 180) { Write-Host '  OAP: 已就绪' } else { Write-Host '  OAP: 启动超时(180s)' }
} else { Write-Host '  SkyWalking OAP: 已运行' }
if (-not (Test-Port 18080)) {
    Write-Host '  SkyWalking UI: 启动中...'
    Start-Process -FilePath $JAVA21 -ArgumentList @(
        '-cp','C:\app\apache-skywalking-apm-bin\webapp\skywalking-webapp.jar;C:\app\apache-skywalking-apm-bin\webapp',
        '-Dwebapp.logDir=C:\app\apache-skywalking-apm-bin\logs',
        'org.apache.skywalking.oap.server.webapp.ApplicationStartUp') -WindowStyle Hidden
    if (Wait-Port 18080 60) { Write-Host '  SkyWalking UI: 已就绪' } else { Write-Host '  SkyWalking UI: 启动超时(60s)' }
} else { Write-Host '  SkyWalking UI: 已运行' }

# ⑥ RocketMQ Dashboard（JDK21）
if (-not (Test-Port 18082)) {
    Write-Host '  RocketMQ Dashboard: 启动中...'
    Start-Process -FilePath $JAVA21 -ArgumentList @(
        '-jar','C:\app\rmq-dashboard\rocketmq-dashboard-rocketmq-dashboard-2.1.0\target\rocketmq-dashboard-2.1.0.jar') -WindowStyle Hidden
    if (Wait-Port 18082 60) { Write-Host '  Dashboard: 已就绪' } else { Write-Host '  Dashboard: 启动超时(60s)' }
} else { Write-Host '  RocketMQ Dashboard: 已运行' }

Write-Host '========== [2/5] Maven 构建 ==========' -ForegroundColor Cyan
if ($SkipBuild) {
    Write-Host '  -SkipBuild，跳过构建'
} else {
    $env:JAVA_HOME = $JDK25_HOME
    Push-Location $ROOT
    Write-Host '  构建中（根目录 mvn clean package -DskipTests）...'
    & $MVN clean package -DskipTests
    Pop-Location
    Write-Host '  构建完成'
}

Write-Host '========== [3/5] 后端服务启动 ==========' -ForegroundColor Cyan
# 停旧后端进程
foreach ($s in $backend) {
    $t = Get-NetTCPConnection -LocalPort $s.p -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($t) { Stop-Process -Id $t.OwningProcess -Force -ErrorAction SilentlyContinue; Write-Host ("  停止 {0}:{1} (pid {2})" -f $s.n, $s.p, $t.OwningProcess) }
}
Start-Sleep -Seconds 3

# 统一环境变量（持久化开关）
$env:COSY_AGENT_CAPABILITY_ENABLED = 'true'
$env:COSY_AGENT_PERSISTENCE        = 'mysql'

# 带 SkyWalking agent 启动
foreach ($s in $backend) {
    $jar = "$ROOT\$($s.n)\target\$($s.n)-0.1.0-SNAPSHOT.jar"
    if (-not (Test-Path $jar)) { Write-Host "  !! $($s.n) jar 不存在: $jar（请先构建）" -ForegroundColor Red; continue }
    Start-Process -FilePath $JAVA25 -ArgumentList @("-javaagent:$SKY_AGENT", '-jar', $jar) -WindowStyle Hidden `
        -RedirectStandardOutput "$LOGDIR\agg_$($s.n)_out.log" -RedirectStandardError "$LOGDIR\agg_$($s.n)_err.log"
    Write-Host ("  {0} 启动中 (jar: {1})" -f $s.n, $jar)
}

Write-Host '========== [4/5] 健康探测 ==========' -ForegroundColor Cyan
Write-Host '  等待服务就绪（SkyWalking agent 注入较慢，最长 150s）...'
$allOk = $true
foreach ($s in $backend) {
    if (Wait-Port $s.p 150) { Write-Host ("  {0}:{1} OK" -f $s.n, $s.p) } else { Write-Host ("  {0}:{1} FAIL" -f $s.n, $s.p) -ForegroundColor Red; $allOk = $false }
}
# 网关对话链路
if (Test-Port 8080) {
    try {
        $code = (Invoke-WebRequest -Uri 'http://localhost:8080/api/agent/chat' -Method POST -ContentType 'application/json' -Body '{"sessionId":"startup-check","message":"你好","stream":false}' -TimeoutSec 30 -UseBasicParsing).StatusCode
        Write-Host "  网关对话: HTTP $code"
    } catch { Write-Host "  网关对话: 探测失败 $($_.Exception.Message)" -ForegroundColor Red }
}

Write-Host '========== [5/5] 控制台地址 ==========' -ForegroundColor Cyan
Write-Host '  后端:'
Write-Host '    Gateway          http://localhost:8080'
Write-Host '    Agent            http://localhost:8280'
Write-Host '    Knowledge        http://localhost:8281'
Write-Host '    Capability       http://localhost:8282'
Write-Host '  中间件控制台:'
Write-Host '    Nacos            http://localhost:8849/nacos  (nacos/nacos)'
Write-Host '    SkyWalking UI    http://localhost:18080'
Write-Host '    RocketMQ Dash    http://localhost:18082  (super/admin)'
Write-Host '    Kibana           http://localhost:5601'
Write-Host '    Elasticsearch    http://localhost:9200'
Write-Host "  后端日志: $LOGDIR\agg_*.log"
if ($allOk) { Write-Host '  全部服务已就绪 ✔' -ForegroundColor Green } else { Write-Host '  存在未就绪服务，见上方 FAIL' -ForegroundColor Red }
