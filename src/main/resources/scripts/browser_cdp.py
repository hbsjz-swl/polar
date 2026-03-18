#!/usr/bin/env python3
"""
Browser CDP - Connect to an existing Chrome browser via Chrome DevTools Protocol.

Prerequisites:
    Chrome must be running with: --remote-debugging-port=9222

Usage:
    python browser_cdp.py view [--cdp-url URL] [--tab N] [--screenshot PATH]
    python browser_cdp.py action --actions JSON [--cdp-url URL] [--tab N] [--screenshot PATH]
"""

from playwright.sync_api import sync_playwright
import argparse
import json
import sys


DEFAULT_CDP_URL = "http://localhost:9222"


def connect_browser(p, cdp_url):
    """Connect to Chrome via CDP."""
    try:
        browser = p.chromium.connect_over_cdp(cdp_url)
        return browser
    except Exception as e:
        error_msg = str(e)
        if "connect" in error_msg.lower() or "refused" in error_msg.lower():
            print(json.dumps({
                "error": "无法连接到 Chrome。请先调用 browser_start 启动浏览器。",
                "cdp_url": cdp_url,
                "details": error_msg
            }, ensure_ascii=False))
        else:
            print(json.dumps({
                "error": f"CDP connection failed: {error_msg}",
                "cdp_url": cdp_url
            }, ensure_ascii=False))
        sys.exit(1)


def get_page(browser, tab_index=0):
    """Get a page by tab index."""
    pages = []
    for ctx in browser.contexts:
        pages.extend(ctx.pages)

    if not pages:
        if browser.contexts:
            return browser.contexts[0].new_page()
        return None

    if tab_index >= len(pages):
        tab_index = len(pages) - 1

    return pages[tab_index]


def get_selector(el):
    """为元素生成可靠的 CSS 选择器。优先级: #id > [name] > [data-testid] > 文本选择器 > nth-child 路径。"""
    try:
        sel = el.evaluate("""el => {
            // 1. id
            if (el.id) return '#' + CSS.escape(el.id);

            // 2. name 属性
            const name = el.getAttribute('name');
            if (name) {
                const tag = el.tagName.toLowerCase();
                const sel = tag + '[name="' + name + '"]';
                if (document.querySelectorAll(sel).length === 1) return sel;
            }

            // 3. data-testid
            const testId = el.getAttribute('data-testid') || el.getAttribute('data-test');
            if (testId) return '[data-testid="' + testId + '"]';

            // 4. aria-label
            const ariaLabel = el.getAttribute('aria-label');
            if (ariaLabel) {
                const tag = el.tagName.toLowerCase();
                const sel = tag + '[aria-label="' + ariaLabel + '"]';
                if (document.querySelectorAll(sel).length === 1) return sel;
            }

            // 5. placeholder
            const placeholder = el.getAttribute('placeholder');
            if (placeholder) {
                const tag = el.tagName.toLowerCase();
                const sel = tag + '[placeholder="' + placeholder + '"]';
                if (document.querySelectorAll(sel).length === 1) return sel;
            }

            // 6. 唯一 class 组合
            if (el.classList.length > 0) {
                const tag = el.tagName.toLowerCase();
                const cls = '.' + Array.from(el.classList).map(c => CSS.escape(c)).join('.');
                const sel = tag + cls;
                if (document.querySelectorAll(sel).length === 1) return sel;
            }

            // 7. nth-child 路径 (最后手段)
            const parts = [];
            let node = el;
            while (node && node !== document.body && parts.length < 4) {
                const tag = node.tagName.toLowerCase();
                if (node.id) {
                    parts.unshift('#' + CSS.escape(node.id));
                    break;
                }
                const parent = node.parentElement;
                if (parent) {
                    const siblings = Array.from(parent.children).filter(c => c.tagName === node.tagName);
                    if (siblings.length > 1) {
                        const idx = siblings.indexOf(node) + 1;
                        parts.unshift(tag + ':nth-child(' + idx + ')');
                    } else {
                        parts.unshift(tag);
                    }
                } else {
                    parts.unshift(tag);
                }
                node = parent;
            }
            return parts.join(' > ');
        }""")
        return sel
    except:
        return None


def view_page(page, screenshot_path=None):
    """Analyze current page structure with actionable selectors."""
    info = {"url": page.url, "title": page.title()}

    # ===== 可交互元素（统一收集，带选择器）=====
    interactive = []

    # 输入框、文本域、下拉框
    for el in page.locator("input, textarea, select").all():
        try:
            if not el.is_visible():
                continue
            itype = el.get_attribute("type") or "text"
            if itype in ("hidden",):
                continue
            tag = el.evaluate("el => el.tagName.toLowerCase()")
            selector = get_selector(el)
            if not selector:
                continue
            label = (
                el.get_attribute("placeholder")
                or el.get_attribute("aria-label")
                or el.get_attribute("name")
                or el.get_attribute("id")
                or ""
            )
            value = ""
            try:
                value = el.input_value()
            except:
                pass
            item = {"tag": tag, "type": itype, "label": label, "selector": selector}
            if value:
                item["value"] = value[:100]
            interactive.append(item)
        except:
            pass

    # 按钮
    for el in page.locator("button, [role='button'], input[type='submit'], input[type='button']").all():
        try:
            if not el.is_visible():
                continue
            text = el.inner_text().strip()
            if not text:
                text = el.get_attribute("value") or el.get_attribute("aria-label") or ""
            if not text or len(text) > 80:
                continue
            selector = get_selector(el)
            if not selector:
                continue
            interactive.append({"tag": "button", "text": text, "selector": selector})
        except:
            pass

    info["interactive_elements"] = interactive[:60]

    # ===== 链接 =====
    links = []
    for a in page.locator("a[href]").all():
        try:
            if not a.is_visible():
                continue
            text = a.inner_text().strip()
            href = a.get_attribute("href") or ""
            if not text or len(text) > 120:
                continue
            selector = get_selector(a)
            links.append({"text": text, "href": href, "selector": selector})
        except:
            pass
    info["links"] = links[:50]

    # ===== 标题 =====
    headings = []
    for h in page.locator("h1, h2, h3, h4").all():
        try:
            if h.is_visible():
                tag = h.evaluate("el => el.tagName")
                text = h.inner_text().strip()
                if text:
                    headings.append(f"[{tag}] {text}")
        except:
            pass
    info["headings"] = headings

    # ===== 表单概览 =====
    forms = []
    for form in page.locator("form").all():
        try:
            selector = get_selector(form)
            action = form.get_attribute("action") or ""
            method = form.get_attribute("method") or "get"
            forms.append({"selector": selector, "action": action, "method": method})
        except:
            pass
    info["forms"] = forms[:10]

    # ===== 表格 =====
    tables = []
    for table in page.locator("table").all():
        try:
            if table.is_visible():
                headers = [th.inner_text().strip() for th in table.locator("th").all()]
                row_count = len(table.locator("tr").all())
                selector = get_selector(table)
                tables.append({"headers": headers, "rows": row_count, "selector": selector})
        except:
            pass
    info["tables"] = tables[:10]

    # ===== 主要文本内容 =====
    try:
        main_el = page.locator("main, article, [role='main'], .content, #content").first
        if main_el.is_visible():
            info["main_text"] = main_el.inner_text()[:3000]
        else:
            raise Exception()
    except:
        try:
            info["main_text"] = page.locator("body").inner_text()[:3000]
        except:
            info["main_text"] = ""

    # ===== 截图 =====
    if screenshot_path:
        try:
            page.screenshot(path=screenshot_path, full_page=False)
            info["screenshot"] = f"{screenshot_path} [SCREENSHOT:{screenshot_path}]"
        except Exception as e:
            info["screenshot_error"] = str(e)

    # ===== 使用提示 =====
    info["_hint"] = "使用 selector 字段的值作为 browser_action 的 selector 参数。例如: {\"action\":\"click\",\"selector\":\"#search-btn\"}"

    return info


def perform_actions(page, actions, screenshot_path=None):
    """Perform actions on the page."""
    results = []
    console_logs = []
    page.on("console", lambda msg: console_logs.append(f"[{msg.type}] {msg.text}"))

    for i, act in enumerate(actions):
        action = act.get("action", "")
        selector = act.get("selector", "")
        step = {"step": i + 1, "action": action}

        try:
            if action == "goto":
                url = act.get("url", selector)
                page.goto(url, timeout=30000)
                page.wait_for_load_state("networkidle", timeout=15000)
                step["result"] = f"Navigated to: {page.url}"

            elif action == "click":
                page.locator(selector).click(timeout=5000)
                try:
                    page.wait_for_load_state("networkidle", timeout=10000)
                except:
                    pass
                step["result"] = f"Clicked: {selector}"
                step["new_url"] = page.url

            elif action == "click_xy":
                x = act.get("x", 0)
                y = act.get("y", 0)
                page.mouse.click(x, y)
                try:
                    page.wait_for_load_state("networkidle", timeout=5000)
                except:
                    pass
                step["result"] = f"Clicked at ({x}, {y})"
                step["new_url"] = page.url

            elif action == "fill":
                page.locator(selector).fill(act.get("value", ""))
                step["result"] = f"Filled: {selector}"

            elif action == "select":
                page.locator(selector).select_option(act.get("value", ""))
                step["result"] = f"Selected: {act.get('value', '')}"

            elif action == "check":
                page.locator(selector).check()
                step["result"] = f"Checked: {selector}"

            elif action == "uncheck":
                page.locator(selector).uncheck()
                step["result"] = f"Unchecked: {selector}"

            elif action == "hover":
                page.locator(selector).hover()
                step["result"] = f"Hovered: {selector}"

            elif action == "scroll":
                direction = act.get("direction", "down")
                amount = act.get("amount", 500)
                delta = amount if direction == "down" else -amount
                page.mouse.wheel(0, delta)
                page.wait_for_timeout(500)
                step["result"] = f"Scrolled {direction} by {amount}px"

            elif action == "wait":
                t = act.get("timeout", 5000)
                page.locator(selector).wait_for(timeout=t)
                step["result"] = f"Found: {selector}"

            elif action == "screenshot":
                path = act.get("path", f"/tmp/step_{i + 1}.png")
                page.screenshot(path=path, full_page=act.get("full_page", False))
                step["result"] = f"Screenshot saved: {path} [SCREENSHOT:{path}]"

            elif action == "get_text":
                text = page.locator(selector).inner_text()
                step["result"] = text[:3000]

            elif action == "get_attr":
                attr = act.get("attr", "href")
                val = page.locator(selector).get_attribute(attr)
                step["result"] = val

            elif action == "evaluate":
                val = page.evaluate(act.get("script", ""))
                step["result"] = str(val)[:3000]

            elif action == "press":
                key = act.get("key", "Enter")
                if selector:
                    page.locator(selector).press(key)
                else:
                    page.keyboard.press(key)
                step["result"] = f"Pressed: {key}"

            else:
                step["error"] = f"Unknown action: {action}"

        except Exception as e:
            step["error"] = str(e)

        results.append(step)

    # Final state
    final = {
        "final_url": page.url,
        "final_title": page.title(),
    }

    if screenshot_path:
        try:
            page.screenshot(path=screenshot_path, full_page=False)
            final["screenshot"] = f"{screenshot_path} [SCREENSHOT:{screenshot_path}]"
        except Exception as e:
            final["screenshot_error"] = str(e)

    if console_logs:
        final["console_logs"] = console_logs[-20:]

    return {"actions": results, "final": final}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Browser CDP operations")
    parser.add_argument("mode", choices=["view", "action"],
                        help="Mode: view (analyze page) or action (perform actions)")
    parser.add_argument("--cdp-url", default=DEFAULT_CDP_URL,
                        help=f"CDP URL (default: {DEFAULT_CDP_URL})")
    parser.add_argument("--tab", type=int, default=0,
                        help="Tab index to operate on (default: 0)")
    parser.add_argument("--actions", help="JSON array of actions (for action mode)")
    parser.add_argument("--actions-file", help="Path to JSON file containing actions (alternative to --actions)")
    parser.add_argument("--screenshot", help="Save screenshot to path")
    args = parser.parse_args()

    with sync_playwright() as p:
        browser = connect_browser(p, args.cdp_url)

        try:
            page = get_page(browser, args.tab)
            if page is None:
                print(json.dumps({"error": "No pages available in browser"}))
                sys.exit(1)

            if args.mode == "view":
                result = view_page(page, args.screenshot)

            elif args.mode == "action":
                # 优先从文件读取 actions，解决 Windows 命令行 JSON 转义问题
                actions_json = None
                if args.actions_file:
                    try:
                        with open(args.actions_file, "r", encoding="utf-8") as f:
                            actions_json = f.read()
                    except Exception as e:
                        print(json.dumps({"error": f"Failed to read actions file: {e}"}))
                        sys.exit(1)
                elif args.actions:
                    actions_json = args.actions
                else:
                    print(json.dumps({"error": "--actions or --actions-file is required for action mode"}))
                    sys.exit(1)
                try:
                    actions = json.loads(actions_json)
                except json.JSONDecodeError as e:
                    print(json.dumps({"error": f"Invalid JSON: {e}"}))
                    sys.exit(1)
                result = perform_actions(page, actions, args.screenshot)

            print(json.dumps(result, ensure_ascii=False, indent=2))

        finally:
            browser.close()
