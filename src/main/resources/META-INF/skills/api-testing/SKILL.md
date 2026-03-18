---
name: api-testing
description: 接口自动化测试技能。批量测试 REST API，检测响应时间、状态码、业务码异常，生成 Markdown 测试报告，可选推送企业微信。当用户需要测试接口、检查 API 健康状态、批量验证接口可用性时使用此技能。
---

# 接口自动化测试技能

## 概述

此技能用于 REST API 接口自动化测试。支持单接口快速测试和批量接口测试，自动检测响应时间、状态码、业务字段等异常，生成 Markdown 格式的测试报告，可选推送到企业微信群机器人。

## 使用场景

- 快速测试某个接口是否正常
- 批量测试多个接口的可用性和响应时间
- 接口上线前的回归测试
- 对比不同环境（生产/测试）的接口表现
- 定期健康检查并推送结果到企业微信

## 工作流程

### 简单模式（1-3 个接口）

Agent 直接使用 `curl` 测试，无需调用 Python 脚本。

**GET 请求：**
```bash
curl -s -o /dev/null -w "状态码: %{http_code}\n响应时间: %{time_total}s" https://api.example.com/health
```

**带响应体的 GET：**
```bash
curl -s -w "\n---\n状态码: %{http_code}\n响应时间: %{time_total}s" https://api.example.com/health
```

**POST 请求：**
```bash
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}' \
  -w "\n---\n状态码: %{http_code}\n响应时间: %{time_total}s" \
  https://api.example.com/auth/login
```

**带鉴权的请求：**
```bash
curl -s -H "Authorization: Bearer <token>" \
  -w "\n---\n状态码: %{http_code}\n响应时间: %{time_total}s" \
  https://api.example.com/users
```

Agent 根据 curl 返回结果，直接向用户报告接口状态，包括状态码、响应时间、响应体摘要。

### 批量模式（多接口 / 配置文件）

当测试 3 个以上接口，或用户提供了配置文件时，调用 Python 脚本：

```bash
# 脚本位置（从 skill 资源中获取）
SCRIPT_DIR="$(dirname "$0")/scripts"

# 使用配置文件批量测试
python3 scripts/api_test.py --config tests.json

# 批量测试 + 保存报告
python3 scripts/api_test.py --config tests.json --output report.md

# 批量测试 + 企微推送
python3 scripts/api_test.py --config tests.json --wecom-webhook "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx"

# 单接口测试（命令行参数）
python3 scripts/api_test.py --url https://api.example.com/health --method GET --assert-status 200 --assert-max-ms 500
```

> **注意**：脚本位于 `src/main/resources/META-INF/skills/api-testing/scripts/api_test.py`。如果用户环境没有 `requests` 库，先执行 `pip install requests`。

## 接口配置格式

配置文件为 JSON 格式：

```json
{
  "base_url": "https://api.example.com",
  "headers": {
    "Authorization": "Bearer token123"
  },
  "timeout": 5,
  "tests": [
    {
      "name": "健康检查",
      "method": "GET",
      "path": "/health",
      "assert": {
        "status": 200,
        "max_ms": 500
      }
    },
    {
      "name": "用户登录",
      "method": "POST",
      "path": "/auth/login",
      "headers": {
        "Content-Type": "application/json"
      },
      "body": {
        "username": "admin",
        "password": "123456"
      },
      "assert": {
        "status": 200,
        "body_contains": "token",
        "max_ms": 1000
      }
    },
    {
      "name": "获取用户列表",
      "method": "GET",
      "path": "/users",
      "assert": {
        "status": 200,
        "json_has": ["id", "name"],
        "max_ms": 500
      }
    }
  ]
}
```

**字段说明：**

| 字段 | 层级 | 必需 | 说明 |
|------|------|------|------|
| `base_url` | 顶层 | 否 | 基础 URL，拼接到每个 test 的 path 前 |
| `headers` | 顶层 | 否 | 全局请求头，所有 test 共享 |
| `timeout` | 顶层 | 否 | 全局超时秒数，默认 10 |
| `tests` | 顶层 | 是 | 测试用例数组 |
| `name` | test | 否 | 测试名称，用于报告展示 |
| `method` | test | 否 | HTTP 方法，默认 GET |
| `path` | test | 是* | 路径（与 base_url 拼接），或用 `url` 替代 |
| `url` | test | 是* | 完整 URL（覆盖 base_url + path） |
| `headers` | test | 否 | 请求头（合并到全局 headers） |
| `body` | test | 否 | 请求体（对象或字符串） |
| `assert` | test | 否 | 断言规则 |

## 断言规则

| 断言 | 说明 | 示例 |
|------|------|------|
| `status` | HTTP 状态码，支持单值或数组 | `200` 或 `[200, 201]` |
| `max_ms` | 最大响应时间（毫秒） | `500` |
| `body_contains` | 响应体必须包含的字符串 | `"token"` |
| `body_not_contains` | 响应体不能包含的字符串 | `"error"` |
| `json_field` | JSON 字段值匹配 | `{"code": 0, "status": "ok"}` |
| `json_has` | JSON 响应必须包含的字段名 | `["id", "name", "email"]` |

不设置 assert 时，默认只检查接口是否返回（不超时、不报错）。

## 报告格式

脚本生成的 Markdown 报告格式：

```markdown
# API 测试报告
时间: 2026-03-17 15:30:00
总计: 10 | 通过: 8 | 失败: 2

| # | 接口 | 方法 | 状态码 | 响应(ms) | 结果 |
|---|------|------|--------|----------|------|
| 1 | /health | GET | 200 | 45 | ✅ |
| 2 | /auth/login | POST | 200 | 320 | ✅ |
| 3 | /users | GET | 500 | 1205 | ❌ 状态码错误 |

## 失败详情
### 3. GET /users
- 预期状态码: 200, 实际: 500
- 响应时间: 1205ms (超过阈值 500ms)
- 响应体: {"error": "database connection failed"}
```

## 企微推送

通过 `--wecom-webhook` 参数传入企业微信群机器人的 Webhook 地址，测试完成后自动推送摘要消息。

推送消息格式：
```
【API测试报告】
环境: https://api.example.com
时间: 2026-03-17 15:30
结果: 8/10 通过, 2 失败 ❌

失败接口:
- GET /users → 500 (1205ms)
- POST /orders → timeout
```

## 使用示例

**示例1：快速测试单个接口**
```
用户：测一下 https://api.example.com/health 的响应时间

→ Agent 用 curl 直接测试：
  curl -s -o /dev/null -w "状态码: %{http_code}\n响应时间: %{time_total}s" https://api.example.com/health
→ 返回结果：状态码 200，响应时间 0.045s
```

**示例2：批量测试多个接口**
```
用户：批量测试登录、用户列表、订单查询接口，超过500ms标红

→ Agent 构建 JSON 配置文件 tests.json，包含3个接口
→ 调用：python3 api_test.py --config tests.json
→ 输出 Markdown 表格报告，超过500ms的标记为失败
```

**示例3：配置文件 + 企微推送**
```
用户：读取 api-tests.json 跑全部接口，结果发到企微

→ 调用：python3 api_test.py --config api-tests.json --wecom-webhook "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx"
→ 输出报告到终端，同时推送摘要到企微群
```

**示例4：POST 测试带请求体**
```
用户：POST 测试登录接口 https://api.example.com/auth/login，body 是 {"username": "admin", "password": "123456"}

→ Agent 用 curl 直接测试：
  curl -s -X POST -H "Content-Type: application/json" \
    -d '{"username":"admin","password":"123456"}' \
    -w "\n---\n状态码: %{http_code}\n响应时间: %{time_total}s" \
    https://api.example.com/auth/login
→ 返回响应体 + 状态码 + 响应时间
```

**示例5：对比两个环境**
```
用户：对比生产和测试环境的用户接口响应速度

→ Agent 分别用 curl 测试两个环境的相同接口
→ 输出对比表格：
  | 接口 | 生产(ms) | 测试(ms) | 差异 |
  |------|----------|----------|------|
  | /users | 45 | 120 | +75ms |
  | /orders | 80 | 95 | +15ms |
```

## 错误处理

| 错误类型 | 处理方式 |
|---------|---------|
| 连接超时 | 标记为失败，记录 "timeout"，建议检查网络或目标服务 |
| DNS 解析失败 | 标记为失败，提示检查域名是否正确 |
| SSL 证书错误 | 提示用户，可用 `--insecure` 跳过验证 |
| JSON 解析失败 | 跳过 JSON 相关断言，记录原始响应体 |
| 配置文件格式错误 | 报告具体的 JSON 解析错误位置 |
| requests 库缺失 | 提示执行 `pip install requests` |
| 企微推送失败 | 报告推送错误，但不影响测试结果输出 |

## 注意事项

1. **简单优先**：1-3 个接口直接用 curl，不必调用 Python 脚本
2. **安全性**：不要在报告中明文展示敏感信息（密码、token），可用 `***` 遮挡
3. **超时设置**：默认超时 10 秒，可通过配置文件的 `timeout` 字段调整
4. **并发**：当前为串行执行，保证结果稳定可复现
5. **依赖**：脚本仅依赖 `requests` 库，无其他第三方依赖
