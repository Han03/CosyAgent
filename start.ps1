# CosyAgent 标准启动脚本（Windows）
# 作用：以统一环境变量启动后端，保证各持久化组件按预期装配：
#   - 能力注册中心落 MySQL（否则目录重启即空）
#   - 模型路由配置落 MySQL（平台/规则重启保留）
#   - 任务/会话落 MySQL（否则会话重启即失！）
# 用法：powershell -ExecutionPolicy Bypass -File C:\MyProjects\CosyAgent\start.ps1
$ErrorActionPreference = 'Stop'

# 停止已在监听的旧进程
$listener = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if ($listener) {
    Stop-Process -Id $listener.OwningProcess -Force
    Start-Sleep -Seconds 2
}

Set-Location C:\MyProjects\CosyAgent

# ---- 持久化装配开关（缺一不可，缺失即回退内存实现） ----
$env:COSY_AGENT_CAPABILITY_ENABLED = 'true'      # 能力注册中心
$env:COSY_AGENT_CAPABILITY_STORE = 'mysql'
$env:COSY_MODEL_ROUTING_STORE    = 'mysql'       # 模型路由配置
$env:COSY_AGENT_TASK_STORE       = 'mysql'       # 任务/会话业务数据（关键！）

Start-Process cmd.exe '/c mvn spring-boot:run > C:\app\cosyagent_model.log 2>&1' -WindowStyle Hidden
Write-Host '后端启动中（日志: C:\app\cosyagent_model.log）...'
