#!/usr/bin/env python3
"""
Page Analyzer - Analyze a web page's structure, layout, and interactive elements.

Usage:
    python analyze_page.py <url>
    python analyze_page.py <url> --screenshot /tmp/page.png
    python analyze_page.py <url> --selector "main" --screenshot /tmp/main.png
"""

from playwright.sync_api import sync_playwright
import argparse
import json
import sys


def analyze(url, screenshot_path=None, selector=None, timeout=30000):
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1920, "height": 1080})

        try:
            page.goto(url, timeout=timeout)
            page.wait_for_load_state("networkidle", timeout=timeout)
        except Exception as e:
            browser.close()
            return {"error": f"Failed to load: {e}", "url": url}

        info = {"url": page.url, "title": page.title()}

        # --- Page structure: headings ---
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

        # --- Navigation links ---
        nav = []
        for a in page.locator("nav a, header a, [role='navigation'] a").all():
            try:
                if a.is_visible():
                    text = a.inner_text().strip()
                    href = a.get_attribute("href") or ""
                    if text:
                        nav.append({"text": text, "href": href})
            except:
                pass
        info["navigation"] = nav[:20]

        # --- Forms ---
        forms = []
        for form in page.locator("form").all():
            try:
                fields = []
                for inp in form.locator("input, textarea, select").all():
                    itype = inp.get_attribute("type") or "text"
                    name = (
                        inp.get_attribute("name")
                        or inp.get_attribute("id")
                        or inp.get_attribute("placeholder")
                        or ""
                    )
                    fields.append({"name": name, "type": itype})
                btns = []
                for b in form.locator("button, input[type='submit']").all():
                    btns.append(
                        b.inner_text().strip()
                        or b.get_attribute("value")
                        or "submit"
                    )
                forms.append({"fields": fields, "buttons": btns})
            except:
                pass
        info["forms"] = forms

        # --- Buttons (outside forms) ---
        buttons = []
        seen = set()
        for el in page.locator(
            "button, [role='button'], a.btn, .btn, input[type='button']"
        ).all():
            try:
                if el.is_visible():
                    text = el.inner_text().strip()
                    if text and len(text) < 80 and text not in seen:
                        tag = el.evaluate("el => el.tagName")
                        cls = el.get_attribute("class") or ""
                        buttons.append({"text": text, "tag": tag, "class": cls[:80]})
                        seen.add(text)
            except:
                pass
        info["buttons"] = buttons[:30]

        # --- All visible links ---
        links = []
        for a in page.locator("a[href]").all():
            try:
                if a.is_visible():
                    text = a.inner_text().strip()
                    href = a.get_attribute("href") or ""
                    if text and len(text) < 120:
                        links.append({"text": text, "href": href})
            except:
                pass
        info["links"] = links[:50]

        # --- Images with alt text ---
        images = []
        for img in page.locator("img[alt]").all():
            try:
                if img.is_visible():
                    alt = img.get_attribute("alt") or ""
                    src = img.get_attribute("src") or ""
                    if alt:
                        images.append({"alt": alt, "src": src[:200]})
            except:
                pass
        info["images"] = images[:20]

        # --- Tables ---
        tables = []
        for table in page.locator("table").all():
            try:
                if table.is_visible():
                    headers = [
                        th.inner_text().strip()
                        for th in table.locator("th").all()
                    ]
                    row_count = len(table.locator("tr").all())
                    tables.append({"headers": headers, "rows": row_count})
            except:
                pass
        info["tables"] = tables[:10]

        # --- Main text content (abbreviated) ---
        try:
            main_el = page.locator(
                "main, article, [role='main'], .content, #content"
            ).first
            if main_el.is_visible():
                info["main_text"] = main_el.inner_text()[:3000]
            else:
                raise Exception()
        except:
            info["main_text"] = page.locator("body").inner_text()[:3000]

        # --- Specific selector analysis ---
        if selector:
            try:
                el = page.locator(selector).first
                info["selector_html"] = el.evaluate(
                    "el => el.outerHTML"
                )[:5000]
                info["selector_text"] = el.inner_text()[:2000]
            except Exception as e:
                info["selector_error"] = f"Selector '{selector}' failed: {e}"

        # --- Screenshot ---
        if screenshot_path:
            page.screenshot(path=screenshot_path, full_page=True)
            info["screenshot"] = screenshot_path

        browser.close()
        return info


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Analyze a web page")
    parser.add_argument("url", help="URL to analyze")
    parser.add_argument("--screenshot", help="Save full-page screenshot")
    parser.add_argument("--selector", help="Also inspect a specific CSS selector")
    parser.add_argument(
        "--timeout", type=int, default=30000, help="Timeout in ms (default: 30000)"
    )
    args = parser.parse_args()

    result = analyze(args.url, args.screenshot, args.selector, args.timeout)
    print(json.dumps(result, ensure_ascii=False, indent=2))
