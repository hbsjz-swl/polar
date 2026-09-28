# Polar - Local AI Cowork Agent

本地 AI 办公助手，在终端中通过自然语言操作代码。所有文件操作在本地执行，仅 LLM 推理通过 API 调用。

---

## 功能

- 读取/写入/编辑文件
- 代码搜索（文件名搜索 + 内容正则搜索）
- 执行 Shell 命令（带沙箱保护）
- 浏览器自动化（打开网页、点击、填写表单、截图等）
- 持久记忆（跨会话记住项目信息和用户偏好）
- 技能系统（安装/使用可复用的自动化工作流）
- 会话持久化、恢复与分支（本地 Markdown）
- 工具调用事件流、取消与人工审批
- `polar exec --jsonl` 无交互执行模式
- 有界的子 Agent 委派（独立会话、超时、取消）
- 支持任意 OpenAI 兼容 API（通义千问、DeepSeek、GPT-4o、Ollama 等）
- 首次启动交互式配置，无需手动编辑配置文件


## 用户安装

### 一键安装（macOS / Linux）

```bash
git clone https://github.com/hbsjz-swl/dlc-cli.git && cd dlc-cli && bash install.sh
```

### 方式三：手动安装

1. 下载 `dlc.jar` 放到 `~/.dlc/` 目录
2. 创建启动脚本 `~/.dlc/bin/polar`：

```bash
mkdir -p ~/.dlc/bin
cp dlc.jar ~/.dlc/dlc.jar
cat > ~/.dlc/bin/polar << 'EOF'
#!/usr/bin/env bash
DLC_HOME="${DLC_HOME:-$HOME/.dlc}"
export DLC_WORKSPACE="${DLC_WORKSPACE:-$(pwd)}"
exec java -jar "$DLC_HOME/dlc.jar" "$@"
EOF
chmod +x ~/.dlc/bin/polar
```

3. 将 `~/.dlc/bin` 加入 PATH：

```bash
echo 'export PATH="$HOME/.dlc/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

### Windows 一键安装

```cmd
git clone https://github.com/hbsjz-swl/dlc-cli.git && cd dlc-cli && install.bat
```

安装脚本会自动复制 jar、创建启动器、配置 PATH。重新打开终端即可使用。

### Windows 浏览器操作依赖

如需使用 DLC 的浏览器自动化功能（`browser_start`、`browser_view`、`browser_action`），Windows 用户需要额外安装 Python 环境：

**1. 安装 Python**

从官网下载并安装：https://www.python.org/downloads/

> 安装时务必勾选 **"Add Python to PATH"**。

安装完成后打开 CMD 验证：

```cmd
python --version
```

**2. 安装 Playwright**

```cmd
pip install playwright
playwright install chromium(如果失败使用npx安装: npx playwright install chromium)
```

> macOS / Linux 用户通常已预装 Python，将上述命令中的 `python` 替换为 `python3`、`pip` 替换为 `pip3` 即可。

---

## 使用

安装后在任意项目目录执行：

```bash
cd /your/project
polar
```

### 首次启动

首次运行会提示配置 API 连接：

```
  ╔══════════════════════════════════════╗
  ║        DLC - Initial Setup           ║
  ╚══════════════════════════════════════╝

  API Base URL [https://dashscope.aliyuncs.com/compatible-mode]:
  API Key: ********
  Model name [指令模型名称，例如 qwen3.5-plus-2026-02-15]:

  Configuration saved!
```

- 直接回车使用默认值（通义千问）
- API Key 输入时不显示明文
- 配置保存在 `~/.dlc/config.properties`，下次启动自动加载
- API Key 失效时会自动检测并提示重新配置

### 常用 API 配置

| 平台 | Base URL | 模型示例 |
|------|----------|---------|
| 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode` | `qwen3.5-plus-2026-02-15` |
| DeepSeek | `https://api.deepseek.com` | `deepseek-chat` |
| OpenAI | `https://api.openai.com` | `gpt-4o` |
| 本地 Ollama | `http://localhost:11434` | `qwen2.5-coder:7b` |

### 内置命令

| 命令 | 说明 |
|------|------|
| `/config` | 重新配置 API 连接 |
| `/clear` | 清屏并清除对话历史 |
| `/sessions` | 列出本地已持久化的会话 |
| `/resume <id>` | 恢复指定会话 |
| `/fork` | 从当前会话创建一个独立分支 |
| `/status` | 查看当前会话、活跃会话和审批状态 |
| `/agents` | 查看当前会话的子 Agent 任务 |
| `/quit` 或 `/exit` | 退出 |
| `/forget` | 清除所有记忆 |

### 会话持久化规则

每个工作区的会话保存在 `<workspace>/.dlc/sessions/` 下，一个会话对应一个
`<session-id>.md` 文件。文件是可读的 Markdown，消息正文和工具调用/返回值以 JSON
记录保存，方便人工审阅或备份；文件名使用 URL-safe 编码，因此 API 传入的会话 ID
不会穿越工作区边界。

- 新会话第一次完成一轮、工具循环达到上限、出错或被取消时都会写入检查点。
- Polar 重启后，`/resume <id>`、REST 的 `session/{id}/resume` 或 `--session <id>`
  会从 Markdown 重建对话上下文；运行中的模型对象和线程不会写入文件。
- 只保存对话、工具调用和经过截断的工具结果，不保存 API Key、Base URL 或其他配置；
  常见 `api-key`、`token`、`secret`、`password`、Bearer 和密钥前缀会在落盘前做脱敏。
- `/clear` 会清空内存历史并覆盖对应 Markdown 文件；删除会话文件即可彻底移除该会话。
- 会话仍受消息数和字符数上限约束，恢复时会使用与在线会话相同的裁剪规则。

### 权限、审批和进程隔离

`READ_ONLY` 禁止 shell；`STANDARD` 对删除、`sudo` 等高风险命令暂停并发出
`APPROVAL_REQUIRED` 事件，得到确认后才会执行；`AUTONOMOUS` 直接执行策略允许的命令。
所有命令都以工作区为当前目录，并在 macOS 的 Seatbelt 或 Linux 的 bubblewrap 可用时
启用原生写入隔离；其他系统仍使用工作区路径策略。超时会递归终止子进程树，避免后台
进程泄漏。

审批接口：

```text
GET  /api/session/{id}/approvals
POST /api/approval/{approvalId}  {"approved":true|false}
POST /api/session/{sessionId}/approval/{approvalId}  {"approved":true|false}
```

WebSocket 客户端可发送 `{"approvalId":"...","approved":true}`。SSE、WebSocket
和 CLI 都会收到相同的结构化事件。

### 无交互执行（JSONL）

适合脚本、CI 或外部编排器：

```bash
polar exec --jsonl "检查当前项目并修复测试失败"
polar exec --jsonl --session my-session "继续上一次任务"
cat task.txt | polar exec --jsonl
```

每行包含 `type`、`data` 和 `sessionId`。事件类型包括 `TOKEN`、`REASONING`、
`TOOL_CALL_STARTED`、`TOOL_OUTPUT`、`TOOL_CALL_FINISHED`、`APPROVAL_REQUIRED`、
`TURN_COMPLETED` 和 `TURN_FAILED`。

### 子 Agent

模型可通过 `delegate_task` 将独立、可复核的任务交给子 Agent。子 Agent 使用新的会话，
受并发数、最大深度和超时限制，结果以工具输出回传；可以通过 `/agents` 或
`GET /api/session/{id}/agents` 查看，并使用 `POST /api/agent/{taskId}/cancel` 取消。

### HTTP / WebSocket

- `POST /api/chat`：同步 JSON 对话。
- `POST /api/chat/stream`：SSE 事件流。
- WebSocket：默认路径 `/ws/agent`，入站 `{"message":"..."}`，出站为同一事件协议。
- `GET /api/sessions`、`POST /api/session/{id}/resume`、`POST /api/session/{id}/fork`：
  管理本地持久化会话。
- REST 请求可通过 `X-Polar-User`（或 JSON 的 `userId`）绑定会话所有者；不同所有者
  访问非匿名会话会返回 `403`。

---

## 更新

已安装的用户只需执行：

```bash
polar upgrade
```

会自动下载最新版版本并替换，无需重新安装。

---

## 卸载

### macOS / Linux

两步完成，复制粘贴即可：

**第 1 步：删除 DLC 目录**

```bash
rm -rf ~/.dlc
```

**第 2 步：清除 PATH 配置**

根据你使用的 Shell 执行对应命令：

```bash
# zsh 用户（macOS 默认）：
sed -i '' '/\.dlc\/bin/d' ~/.zshrc && source ~/.zshrc

# bash 用户（Linux 默认）：
sed -i '/\.dlc\/bin/d' ~/.bashrc && source ~/.bashrc
```

验证卸载成功：
```bash
which polar    # 应该无输出
ls ~/.dlc    # 应该提示 No such file or directory
```

### Windows

在 **PowerShell** 中执行（不是 CMD）：

**第 1 步：删除 DLC 目录**

```powershell
Remove-Item -Recurse -Force "$env:USERPROFILE\.dlc"
```

**第 2 步：从 PATH 中移除**

```powershell
$path = [Environment]::GetEnvironmentVariable('PATH', 'User') -replace '[;]?[^;]*\.dlc\\bin', ''
[Environment]::SetEnvironmentVariable('PATH', $path, 'User')
```

**第 3 步：验证**（重新打开终端后执行）

```powershell
polar          # 应该提示"不是内部或外部命令"
Test-Path "$env:USERPROFILE\.dlc"   # 应该返回 False
```

---

## 安装目录结构

```
~/.dlc/
├── dlc.jar             
├── bin/polar
├── bin/polar.cmd
├── config.properties  
├── memory.md           
└── skills/             
```

项目级文件（在工作区目录下）：
```
<your-project>/
├── AGENT.md             # 项目规则（可选，手动创建）
└── .dlc/
    └── memory.md        # 项目记忆（自动生成）
```

---

## License

Copyright (c) 2026 Viliam. All rights reserved.
