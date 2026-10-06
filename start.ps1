# CosyAgent 标准启动脚本（Windows）
# 作用：以统一环境变量启动后端，保证各持久化组件按预期装配：
#   - 单一开关 COSY_AGENT_PERSISTENCE=mysql：
#     能力注册中心落库（CP 目录重启恢复）、模型路由配置落库（平台/规则重启保留）、
#     任务/会话落 MySQL（否则会话重启即失）、LLM 调用记录落 MySQL（否则重启即失）
# 用法：powershell -ExecutionPolicy Bypass -File C:\MyProjects\CosyAgent\start.ps1
$ErrorActionPreference = 'Stop'

# 停止已在监听的旧进程
$listener = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if ($listener) {
    Stop-Process -Id $listener.OwningProcess -Force
    Start-Sleep -Seconds 2
}

Set-Location C:\MyProjects\CosyAgent

# ---- 持久化统一开关（v2：memory | mysql；mysql 时 MyBatis-Plus 装配全部业务存储） ----
$env:COSY_AGENT_CAPABILITY_ENABLED = 'true'      # 能力注册中心功能开关
$env:COSY_AGENT_PERSISTENCE        = 'mysql'     # 统一持久化：任务/会话、能力、路由、LLM 调用记录

Start-Process cmd.exe '/c mvn spring-boot:run > C:\app\cosyagent_model.log 2>&1' -WindowStyle Hidden
Write-Host '后端启动中（日志: C:\app\cosyagent_model.log）...'
