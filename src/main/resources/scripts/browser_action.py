#!/usr/bin/env python3
"""
Browser Action - Perform actions on a web page and return the result.

Usage:
    python browser_action.py <url> --actions '<json_actions>'
    python browser_action.py <url> --actions '[{"action":"click","selector":"text=Login"}]'
    python browser_action.py <url> --actions '[{"action":"fill","selector":"#email","value":"test@test.com"},{"action":"click","selector":"button[type=submit]"}]' --screenshot /tmp/after.png

Supported actions:
    click       - Click an element:        {"action":"click", "selector":"..."}
    fill        - Fill an input:           {"action":"fill", "selector":"...", "value":"..."}
    select      - Select an option:        {"action":"select", "selector":"...", "value":"..."}
    check       - Check a checkbox:        {"action":"check", "selector":"..."}
    uncheck     - Uncheck a checkbox:      {"action":"uncheck", "selector":"..."}
    hover       - Hover over element:      {"action":"hover", "selector":"..."}
    scroll      - Scroll page:             {"action":"scroll", "direction":"down|up", "amount":500}
    wait        - Wait for selector:       {"action":"wait", "selector":"...", "timeout":5000}
    screenshot  - Take screenshot:         {"action":"screenshot", "path":"/tmp/step.png"}
    get_text    - Get element text:        {"action":"get_text", "selector":"..."}
    get_attr    - Get attribute:           {"action":"get_attr", "selector":"...", "attr":"href"}
    evaluate    - Run JS expression:       {"action":"evaluate", "script":"document.title"}
    press       - Press a key:             {"action":"press", "key":"Enter"}

Selector tips:
    text=Login          - By visible text
    #my-id              - By ID
    .my-class           - By class
    button:has-text("Submit")  - Button containing text
    input[name="email"] - By attribute
    role=button         - By ARIA role
"""

from playwright.sync_api import sync_playwright
import argparse
import json
import sys


def execute_actions(url, actions, screenshot_path=None, timeout=30000):
    results = []
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1920, "height": 1080})

        # Capture console logs
        console_logs = []
        page.on("console", lambda msg: console_logs.append(f"[{msg.type}] {msg.text}"))

        try:
            page.goto(url, timeout=timeout)
            page.wait_for_load_state("networkidle", timeout=timeout)
        except Exception as e:
            browser.close()
            return {"error": f"Failed to load: {e}", "url": url}

        for i, act in enumerate(actions):
            action = act.get("action", "")
            selector = act.get("selector", "")
            step = {"step": i + 1, "action": action}

            try:
                if action == "click":
                    page.locator(selector).click(timeout=5000)
                    page.wait_for_load_state("networkidle", timeout=10000)
                    step["result"] = f"Clicked: {selector}"
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
                    path = act.get("path", f"/tmp/step_{i+1}.png")
                    page.screenshot(path=path, full_page=act.get("full_page", True))
                    step["result"] = f"Screenshot saved: {path}"

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
            page.screenshot(path=screenshot_path, full_page=True)
            final["screenshot"] = screenshot_path

        if console_logs:
            final["console_logs"] = console_logs[-20:]

        browser.close()
        return {"actions": results, "final": final}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Perform browser actions")
    parser.add_argument("url", help="URL to visit")
    parser.add_argument(
        "--actions",
        required=True,
        help="JSON array of actions to perform",
    )
    parser.add_argument("--screenshot", help="Save final screenshot")
    parser.add_argument(
        "--timeout", type=int, default=30000, help="Page load timeout in ms"
    )
    args = parser.parse_args()

    try:
        actions = json.loads(args.actions)
    except json.JSONDecodeError as e:
        print(json.dumps({"error": f"Invalid JSON: {e}"}))
        sys.exit(1)

    result = execute_actions(args.url, actions, args.screenshot, args.timeout)
    print(json.dumps(result, ensure_ascii=False, indent=2))
