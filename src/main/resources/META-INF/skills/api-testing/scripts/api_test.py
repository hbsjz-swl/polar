#!/usr/bin/env python3
"""API 批量测试脚本 - 支持配置文件、断言、报告生成、企微推送"""

import argparse
import json
import sys
import time
from datetime import datetime
from pathlib import Path

try:
    import requests
except ImportError:
    print("错误: 缺少 requests 库，请执行: pip install requests", file=sys.stderr)
    sys.exit(1)


def run_single_test(test_case, base_url="", global_headers=None, timeout=10):
    """执行单个接口测试，返回测试结果字典"""
    url = test_case.get("url") or (base_url.rstrip("/") + "/" + test_case.get("path", "").lstrip("/"))
    method = test_case.get("method", "GET").upper()
    name = test_case.get("name", f"{method} {url}")

    # 合并 headers：全局 + 单测
    headers = dict(global_headers or {})
    headers.update(test_case.get("headers", {}))

    # 构建请求体
    body = test_case.get("body")
    kwargs = {"headers": headers, "timeout": timeout}
    if body is not None:
        if isinstance(body, dict):
            kwargs["json"] = body
        else:
            kwargs["data"] = str(body)

    result = {
        "name": name,
        "method": method,
        "url": url,
        "path": test_case.get("path", url),
        "status_code": None,
        "time_ms": None,
        "body": "",
        "passed": True,
        "failures": [],
    }

    try:
        start = time.time()
        resp = requests.request(method, url, **kwargs)
        elapsed_ms = round((time.time() - start) * 1000)

        result["status_code"] = resp.status_code
        result["time_ms"] = elapsed_ms
        result["body"] = resp.text[:2000]  # 截断过长响应

        # 执行断言
        asserts = test_case.get("assert", {})
        check_assertions(result, asserts, resp)

    except requests.exceptions.Timeout:
        result["passed"] = False
        result["failures"].append("请求超时")
        result["time_ms"] = round(timeout * 1000)
    except requests.exceptions.ConnectionError as e:
        result["passed"] = False
        result["failures"].append(f"连接失败: {e}")
    except requests.exceptions.RequestException as e:
        result["passed"] = False
        result["failures"].append(f"请求异常: {e}")

    return result


def check_assertions(result, asserts, resp):
    """检查所有断言规则"""
    if not asserts:
        return

    # status
    expected_status = asserts.get("status")
    if expected_status is not None:
        if isinstance(expected_status, list):
            if result["status_code"] not in expected_status:
                result["passed"] = False
                result["failures"].append(
                    f"预期状态码: {expected_status}, 实际: {result['status_code']}"
                )
        else:
            if result["status_code"] != expected_status:
                result["passed"] = False
                result["failures"].append(
                    f"预期状态码: {expected_status}, 实际: {result['status_code']}"
                )

    # max_ms
    max_ms = asserts.get("max_ms")
    if max_ms is not None and result["time_ms"] is not None:
        if result["time_ms"] > max_ms:
            result["passed"] = False
            result["failures"].append(
                f"响应时间: {result['time_ms']}ms (超过阈值 {max_ms}ms)"
            )

    # body_contains
    body_contains = asserts.get("body_contains")
    if body_contains is not None:
        if body_contains not in result["body"]:
            result["passed"] = False
            result["failures"].append(f"响应体不包含: \"{body_contains}\"")

    # body_not_contains
    body_not_contains = asserts.get("body_not_contains")
    if body_not_contains is not None:
        if body_not_contains in result["body"]:
            result["passed"] = False
            result["failures"].append(f"响应体不应包含: \"{body_not_contains}\"")

    # json_field
    json_field = asserts.get("json_field")
    if json_field is not None:
        try:
            data = resp.json()
            for key, expected_val in json_field.items():
                actual_val = data.get(key)
                if actual_val != expected_val:
                    result["passed"] = False
                    result["failures"].append(
                        f"JSON 字段 \"{key}\": 预期 {expected_val!r}, 实际 {actual_val!r}"
                    )
        except (json.JSONDecodeError, ValueError):
            result["passed"] = False
            result["failures"].append("响应体不是有效 JSON，无法校验 json_field")

    # json_has
    json_has = asserts.get("json_has")
    if json_has is not None:
        try:
            data = resp.json()
            # 支持数组响应：检查第一个元素
            check_obj = data[0] if isinstance(data, list) and data else data
            if isinstance(check_obj, dict):
                missing = [f for f in json_has if f not in check_obj]
                if missing:
                    result["passed"] = False
                    result["failures"].append(f"JSON 缺少字段: {missing}")
            else:
                result["passed"] = False
                result["failures"].append("响应体不是 JSON 对象，无法校验 json_has")
        except (json.JSONDecodeError, ValueError):
            result["passed"] = False
            result["failures"].append("响应体不是有效 JSON，无法校验 json_has")


def generate_report(results, base_url=""):
    """生成 Markdown 测试报告"""
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    total = len(results)
    passed = sum(1 for r in results if r["passed"])
    failed = total - passed

    lines = [
        "# API 测试报告",
        f"时间: {now}",
    ]
    if base_url:
        lines.append(f"环境: {base_url}")
    lines.append(f"总计: {total} | 通过: {passed} | 失败: {failed}")
    lines.append("")
    lines.append("| # | 接口 | 方法 | 状态码 | 响应(ms) | 结果 |")
    lines.append("|---|------|------|--------|----------|------|")

    for i, r in enumerate(results, 1):
        status = r["status_code"] if r["status_code"] is not None else "-"
        time_ms = r["time_ms"] if r["time_ms"] is not None else "-"
        if r["passed"]:
            result_str = "✅"
        else:
            short_reason = r["failures"][0] if r["failures"] else "失败"
            # 截断过长的原因
            if len(short_reason) > 30:
                short_reason = short_reason[:27] + "..."
            result_str = f"❌ {short_reason}"
        path_display = r.get("path", r["url"])
        lines.append(f"| {i} | {path_display} | {r['method']} | {status} | {time_ms} | {result_str} |")

    # 失败详情
    failed_results = [(i, r) for i, r in enumerate(results, 1) if not r["passed"]]
    if failed_results:
        lines.append("")
        lines.append("## 失败详情")
        for i, r in failed_results:
            lines.append(f"### {i}. {r['method']} {r.get('path', r['url'])}")
            for f in r["failures"]:
                lines.append(f"- {f}")
            if r["body"]:
                body_preview = r["body"][:500]
                lines.append(f"- 响应体: {body_preview}")
            lines.append("")

    return "\n".join(lines)


def send_wecom(webhook_url, results, base_url=""):
    """推送测试结果到企业微信群机器人"""
    now = datetime.now().strftime("%Y-%m-%d %H:%M")
    total = len(results)
    passed = sum(1 for r in results if r["passed"])
    failed = total - passed

    content_lines = [
        "【API测试报告】",
    ]
    if base_url:
        content_lines.append(f"环境: {base_url}")
    content_lines.append(f"时间: {now}")

    if failed == 0:
        content_lines.append(f"结果: {passed}/{total} 全部通过 ✅")
    else:
        content_lines.append(f"结果: {passed}/{total} 通过, {failed} 失败 ❌")
        content_lines.append("")
        content_lines.append("失败接口:")
        for r in results:
            if not r["passed"]:
                status = r["status_code"] if r["status_code"] is not None else "timeout"
                time_ms = f"{r['time_ms']}ms" if r["time_ms"] is not None else "-"
                content_lines.append(
                    f"- {r['method']} {r.get('path', r['url'])} → {status} ({time_ms})"
                )

    content = "\n".join(content_lines)

    payload = {
        "msgtype": "text",
        "text": {"content": content},
    }

    try:
        resp = requests.post(webhook_url, json=payload, timeout=10)
        if resp.status_code == 200:
            data = resp.json()
            if data.get("errcode") == 0:
                print("\n✅ 企微推送成功")
            else:
                print(f"\n❌ 企微推送失败: {data}", file=sys.stderr)
        else:
            print(f"\n❌ 企微推送失败: HTTP {resp.status_code}", file=sys.stderr)
    except Exception as e:
        print(f"\n❌ 企微推送异常: {e}", file=sys.stderr)


def load_config(config_path):
    """加载 JSON 配置文件"""
    path = Path(config_path)
    if not path.exists():
        print(f"错误: 配置文件不存在: {config_path}", file=sys.stderr)
        sys.exit(1)
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except json.JSONDecodeError as e:
        print(f"错误: 配置文件 JSON 格式错误: {e}", file=sys.stderr)
        sys.exit(1)


def build_cli_test(args):
    """从命令行参数构建单个测试用例"""
    test_case = {
        "url": args.url,
        "method": args.method or "GET",
        "name": f"{args.method or 'GET'} {args.url}",
    }
    asserts = {}
    if args.assert_status is not None:
        asserts["status"] = args.assert_status
    if args.assert_max_ms is not None:
        asserts["max_ms"] = args.assert_max_ms
    if asserts:
        test_case["assert"] = asserts

    if args.header:
        headers = {}
        for h in args.header:
            if ":" in h:
                key, val = h.split(":", 1)
                headers[key.strip()] = val.strip()
        test_case["headers"] = headers

    if args.body:
        try:
            test_case["body"] = json.loads(args.body)
        except json.JSONDecodeError:
            test_case["body"] = args.body

    return test_case


def main():
    parser = argparse.ArgumentParser(description="API 批量测试工具")

    # 配置文件模式
    parser.add_argument("--config", "-c", help="JSON 配置文件路径")

    # 单接口模式
    parser.add_argument("--url", help="接口 URL")
    parser.add_argument("--method", "-m", default=None, help="HTTP 方法 (默认 GET)")
    parser.add_argument("--header", "-H", action="append", help="请求头 (格式: Key: Value)")
    parser.add_argument("--body", "-d", help="请求体 (JSON 字符串)")

    # 断言
    parser.add_argument("--assert-status", type=int, help="预期状态码")
    parser.add_argument("--assert-max-ms", type=int, help="最大响应时间(ms)")

    # 输出
    parser.add_argument("--output", "-o", help="报告输出文件路径")
    parser.add_argument("--wecom-webhook", help="企业微信机器人 Webhook URL")
    parser.add_argument("--timeout", type=int, default=10, help="请求超时秒数 (默认 10)")

    args = parser.parse_args()

    if not args.config and not args.url:
        parser.print_help()
        print("\n错误: 必须指定 --config 或 --url", file=sys.stderr)
        sys.exit(1)

    base_url = ""
    global_headers = {}
    timeout = args.timeout
    test_cases = []

    if args.config:
        config = load_config(args.config)
        base_url = config.get("base_url", "")
        global_headers = config.get("headers", {})
        timeout = config.get("timeout", args.timeout)
        test_cases = config.get("tests", [])
        if not test_cases:
            print("错误: 配置文件中没有测试用例 (tests 为空)", file=sys.stderr)
            sys.exit(1)
    else:
        test_cases = [build_cli_test(args)]

    # 执行测试
    results = []
    total = len(test_cases)
    for i, tc in enumerate(test_cases, 1):
        name = tc.get("name", f"Test {i}")
        print(f"[{i}/{total}] 测试: {name} ...", end=" ", flush=True)
        r = run_single_test(tc, base_url, global_headers, timeout)
        results.append(r)
        if r["passed"]:
            print(f"✅ {r['status_code']} ({r['time_ms']}ms)")
        else:
            print(f"❌ {r['failures'][0] if r['failures'] else '失败'}")

    # 生成报告
    report = generate_report(results, base_url)
    print("\n" + report)

    # 保存报告
    if args.output:
        Path(args.output).write_text(report, encoding="utf-8")
        print(f"\n报告已保存: {args.output}")

    # 企微推送
    if args.wecom_webhook:
        send_wecom(args.wecom_webhook, results, base_url)

    # 返回退出码
    failed_count = sum(1 for r in results if not r["passed"])
    sys.exit(1 if failed_count > 0 else 0)


if __name__ == "__main__":
    main()
