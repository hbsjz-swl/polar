# DLC - Local AI Cowork Agent

本地 AI 办公助手，在终端中通过自然语言操作代码。所有文件操作在本地执行，仅 LLM 推理通过 API 调用。

**蒂爱嘉(北京)有限公司**

---

## 功能

- 读取/写入/编辑文件
- 代码搜索（文件名搜索 + 内容正则搜索）
- 执行 Shell 命令（带沙箱保护）
- 支持任意 OpenAI 兼容 API（通义千问、DeepSeek、GPT-4o、Ollama 等）
- 首次启动交互式配置，无需手动编辑配置文件


## 用户安装

### 一键安装（macOS / Linux）

```bash
git clone https://git.dlchm.cn/sunweilin/coding-agent.git && cd coding-agent && bash install.sh
```

### 方式三：手动安装

1. 下载 `dlc.jar` 放到 `~/.dlc/` 目录
2. 创建启动脚本 `~/.dlc/bin/dlc`：

```bash
mkdir -p ~/.dlc/bin
cp dlc.jar ~/.dlc/dlc.jar
cat > ~/.dlc/bin/dlc << 'EOF'
#!/usr/bin/env bash
DLC_HOME="${DLC_HOME:-$HOME/.dlc}"
export DLC_WORKSPACE="${DLC_WORKSPACE:-$(pwd)}"
exec java -jar "$DLC_HOME/dlc.jar" "$@"
EOF
chmod +x ~/.dlc/bin/dlc
```

3. 将 `~/.dlc/bin` 加入 PATH：

```bash
echo 'export PATH="$HOME/.dlc/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

### Windows 一键安装

```cmd
git clone https://git.dlchm.cn/sunweilin/coding-agent.git && cd coding-agent && install.bat
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
dlc
```

### 首次启动

首次运行会提示配置 API 连接：

```
  ╔══════════════════════════════════════╗
  ║        DLC - Initial Setup           ║
  ╚══════════════════════════════════════╝

  API Base URL [https://dashscope.aliyuncs.com/compatible-mode]:
  API Key: ********
  Model name [qwen3.5-plus-2026-02-15]:

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
| `/clear` | 清屏 |
| `/quit` | 退出 |

---

## 更新

已安装的用户只需执行：

```bash
dlc upgrade
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
which dlc    # 应该无输出
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
dlc          # 应该提示"不是内部或外部命令"
Test-Path "$env:USERPROFILE\.dlc"   # 应该返回 False
```

---

## 安装目录结构

```
~/.dlc/
├── dlc.jar              # 主程序
├── bin/dlc              # 启动脚本（macOS/Linux）
├── bin/dlc.cmd          # 启动脚本（Windows）
├── config.properties    # API 配置（首次启动自动生成）
├── memory.md            # 全局记忆（跨项目，自动生成）
└── skills/              # 技能脚本（自动提取）
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

Copyright (c) 2026 蒂爱嘉(北京)有限公司. All rights reserved.
