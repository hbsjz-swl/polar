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
- 向导还会询问子 Agent 的开关与限值，每项都带中文说明
- 配置保存在 `~/.dlc/config.properties`（每个参数旁都有中文注释），下次启动自动加载
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
| `/config` | 重新配置 API 连接与子 Agent，立即生效 |
| `/clear` | 清屏并清除对话历史 |
| `/sessions` | 列出本地已持久化的会话 |
| `/resume <id>` | 恢复指定会话 |
| `/fork` | 从当前会话创建一个独立分支 |
| `/status` | 查看当前会话、活跃会话和审批状态 |
| `/sub <任务描述>` | 手动派发一个子 Agent 并等待其总结 |
| `/sub list` | 查看当前会话的子 Agent 任务（含运行中） |
| `/sub cancel <id>` | 取消运行中的子 Agent |
| `/agents` | 子 Agent 任务列表（`/sub list` 的别名） |
| `/skills` | 列出已安装的技能 |
| `/install <名称>` | 从 ClawHub 安装技能 |
| `/uninstall <名称>` | 卸载技能 |
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

高风险命令的判定同时覆盖 POSIX 与 PowerShell 两种写法：`rm -rf /`、
`Remove-Item -Recurse -Force C:\`、`del /s /q C:\`、`format C:`、`diskpart`
一律直接拒绝；`rm`、`del`、`Remove-Item`、`sudo`、`git push --force` 等会暂停
等待确认。匹配会扫描整行而不是只看开头，所以 `echo hi && rm -rf ~/x` 同样会被拦下。

> **Windows 没有内核级沙箱。** 系统不提供 Seatbelt 等价物，因此命令只受工作区
> 路径检查约束，不受内核隔离。如果沙箱不可用（例如容器环境，或 macOS 上
> `sandbox-exec` 被限制），DLC 会在第一次执行命令时打印一行警告，说明此时的实际
> 保护级别。请勿在这种情况下执行不可信命令。

### 跨平台行为差异

DLC 在 macOS、Linux、Windows 上都能运行，但有几处刻意的差异：

| 项目 | macOS / Linux | Windows |
|------|---------------|---------|
| 命令解释器 | `bash -c` | `powershell.exe -NoProfile -NonInteractive -Command` |
| 退出码 | shell 原生返回 | 追加 `; exit $LASTEXITCODE` 显式回传（PowerShell 不继承原生命令的退出码，否则失败的构建会被当成成功） |
| 原生沙箱 | Seatbelt / bubblewrap | 无，仅工作区路径检查 |
| 浏览器查找 | 应用路径 / `PATH` 扫描 | 三处默认安装路径 + 注册表 `App Paths` + `PATH` |
| 终端颜色 | 始终启用 | 依赖控制台能力，`NO_COLOR` 或旧版 `cmd.exe` 自动降级为纯文本 |
| 路径分隔符 | `/` | `\` |

当前平台的名称、shell 语法和替换写法会**注入系统提示词**，模型会据此生成对应
shell 的命令，而不是默认写 POSIX 语法。因此在 Windows 上它用的是
`Remove-Item` 而不是 `rm -rf`，用 `;` 而不是 `&&` 串联命令。

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

子 Agent（subagent）用一个**独立会话**跑一段封闭任务，跑完只把最终总结回传给主会话。
它不共享主会话的历史，因此适合"互不依赖的并行块"——分头审几个文件、并行查几个主题、
分别定位几个独立故障。

子 Agent 有两种触发方式：**模型自动委派**和**用户手动派发**。

#### 触发方式一：模型自动委派

`delegate_task` 工具常驻工具集。任务能拆成 2 个以上互不依赖的块时，模型会自行委派，
你不需要做任何事。也可以在提问时直接要求：

```
请用 delegate_task 委派两个独立子任务，一个查 a.txt，一个查 b.txt，并行跑完后汇总。
```

#### 触发方式二：用户手动派发（`/sub`）

`/sub` **绕过模型**直接派发，适合任务本身就是一组独立子任务、但模型可能觉得
"不值得拆"的情况（比如"把这 5 个接口的文档各抓一份"）。

| 命令 | 说明 |
|------|------|
| `/sub <任务描述>` | 派发一个子 Agent，阻塞等待其总结后打印结果 |
| `/sub` 或 `/sub help` | 查看帮助与限制说明 |
| `/sub list` | 列出本会话的子 Agent 任务，含 `running` 状态 |
| `/sub cancel <id>` | 取消运行中的子 Agent |

```
you> /sub 请读取 src/main/java/com/example/OrderService.java，列出所有吞掉异常的
      catch 分支，输出 文件:行号 列表，不要改代码。

子代理已派发（无浏览器权限，无法请求审批），等待结果…
Subagent 7f3c1a20-9b4e-4c8d-a1f2-5e6d7c8b9a01 completed:
  OrderService.java:88  (catch (Exception e) { })
  OrderService.java:142 (catch (Throwable t) { return; })

you> /sub list
7f3c1a20-9b4e-4c8d-a1f2-5e6d7c8b9a01  completed  depth=1  child=b2c3d4e5-...
```

`/agents` 是 `/sub list` 的别名，两者输出相同。

> **注意**：`/sub` 是阻塞命令，**必须等它打印出结果才会回到 `you>` 提示符**，
> 期间无法输入 `/sub cancel`。要取消一个正在跑的子代理，有两个办法：
> 用 `Ctrl+C` 中断当前命令，或改用 HTTP 接口从另一个终端取消
> （`POST /api/agent/{taskId}/cancel`）。这一点与 `/sub` 的同步语义有关，
> 不是缺陷——模型通过 `delegate_task` 委派时同样是阻塞等待的。

#### 任务描述必须自包含

子 Agent 是**空白上下文**：它看不到当前对话、看不到你的原始表述、也看不到主 Agent
已经查到的任何东西。路径、事实、约束、期望输出格式都要在描述里重述一遍。

- 没用：`/sub 去查一下刚才那个问题` —— 子 Agent 不知道"刚才"是什么
- 有用：`/sub 读取 /abs/path/a.txt，统计以 ERROR 开头的行数，只回复一个数字`

#### 限制

- **无浏览器权限**：`browser_*` 工具不对子 Agent 开放。`BrowserTool` 是单例、只持有
  一个 CDP 标签页，子 Agent 一导航就会把主 Agent 正在看的页面抽走。需要浏览器的
  步骤请留在主会话。
- **无法请求人工审批**：子 Agent 触发审批时会立即被拒绝（审批提示只由持有主会话的
  渠道渲染，子 Agent 等下去只会空耗到超时）。需要审批的命令请在主会话执行。
- **不能再往下派**：`max-depth` 默认为 1，即子 Agent 不能派孙 Agent。
- **同步阻塞**：调用会一直等到子 Agent 返回或超时。超时或失败会返回明确原因，
  原样重试同一个描述没有意义。

#### 配置

有三层，优先级从低到高：`application.yml` 默认值 < `~/.dlc/config.properties` < 环境变量。

`/config` 命令可以交互式配置模型连接和子 Agent，**改完立即生效，不用重启**：

```
you> /config

  ── 子 Agent（Sub Agent）──
  子 Agent 还能继续派发几层。1 = 子 Agent 不能再往下派；不建议调高，递归委派会迅速失控
  最大嵌套深度 (整数) [1]: 2
```

向导里的每一项都带中文说明，落盘时也会写进 `~/.dlc/config.properties`：

```properties
# 子 Agent 还能继续派发几层。1 = 子 Agent 不能再往下派；不建议调高，递归委派会迅速失控
# 默认值：1
subagent-max-depth=2
```

也可以用环境变量调整（同样可写入 `application.yml` 的 `dlc.subagent.*`）：

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| `DLC_SUBAGENT_ENABLED` | `true` | 设为 `false` 则不向模型暴露 `delegate_task`，`/sub` 也会拒绝 |
| `DLC_SUBAGENT_MAX_DEPTH` | `1` | 最大嵌套深度，1 表示子 Agent 不能继续派发 |
| `DLC_SUBAGENT_MAX_CONCURRENT` | `4` | 同时运行的子 Agent 上限 |
| `DLC_SUBAGENT_TIMEOUT` | `300` | 默认超时秒数 |
| `DLC_SUBAGENT_MAX_TIMEOUT` | `600` | 超时硬上限，防止入参传入超大值占死线程池 |
| `DLC_SUBAGENT_MAX_PROMPT` | `32000` | 单次任务描述的最大字符数 |

```bash
# 彻底关闭子 Agent
DLC_SUBAGENT_ENABLED=false polar

# 放宽并发和超时
DLC_SUBAGENT_MAX_CONCURRENT=8 DLC_SUBAGENT_TIMEOUT=600 polar
```

`enabled` 和 `max-concurrent` 都在**每次调用时**读取，所以 `/config` 里改完立刻影响下一次委派：
关掉开关后模型再也看不到 `delegate_task`（不会去调一个必然失败的工具），调高并发后正在排队的
子 Agent 会被立即放行。`max-depth` 与两个超时同理。

#### HTTP 接口

```text
GET  /api/session/{id}/agents        列出指定会话的子 Agent 任务
POST /api/agent/{taskId}/cancel      取消指定任务
```

```bash
curl -s localhost:19869/api/session/cli/agents
curl -s -XPOST localhost:19869/api/agent/7f3c1a20-9b4e-4c8d-a1f2-5e6d7c8b9a01/cancel
```

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
