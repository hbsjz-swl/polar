#!/usr/bin/env python3
"""Observe and operate a long-lived Chrome over CDP without launching more windows."""
import argparse
import json
import sys
from pathlib import Path
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright

DEFAULT_CDP_URL = "http://127.0.0.1:9222"
SUPPORTED = {'goto', 'click', 'click_xy', 'fill', 'type', 'select', 'check', 'uncheck',
             'hover', 'press', 'scroll', 'screenshot', 'get_text', 'get_attr', 'evaluate',
             'wait', 'assert', 'back', 'forward', 'reload'}
TARGETED = {'click', 'fill', 'type', 'select', 'check', 'uncheck', 'hover', 'get_text', 'get_attr'}


def connect_browser(p, cdp_url):
    return p.chromium.connect_over_cdp(cdp_url, timeout=10000)


def get_page(browser, tab_index=0):
    pages = [page for ctx in browser.contexts for page in ctx.pages if not page.is_closed()]
    if not pages and tab_index == 0 and browser.contexts:
        return browser.contexts[0].new_page()
    if tab_index < 0 or tab_index >= len(pages):
        raise ValueError(f'Tab {tab_index} does not exist; available tabs: {len(pages)}')
    return pages[tab_index]


# One DOM round trip. Refs refer to the most recent observation only.
SNAPSHOT = r'''() => {
    const visible = el => !!el.getClientRects().length && getComputedStyle(el).visibility !== 'hidden';
    document.querySelectorAll('[data-dlc-ref]').forEach(el => el.removeAttribute('data-dlc-ref'));
    const elements = [];
    let n = 0;
    const nodes = Array.from(document.querySelectorAll('input,textarea,select,button,a[href],[role="button"],[role="textbox"],[contenteditable="true"]'));
    nodes.sort((a,b) => (a.tagName === 'A') - (b.tagName === 'A'));
    for (const el of nodes) {
        if (!visible(el) || el.type === 'hidden') continue;
        const ref = 'e' + (++n);
        el.setAttribute('data-dlc-ref', ref);
        const box = el.getBoundingClientRect();
        const label = el.getAttribute('aria-label') ||
            (el.labels && el.labels.length ? el.labels[0].innerText : '') ||
            el.getAttribute('placeholder') || el.innerText || el.getAttribute('value') || el.name || el.id || '';
        elements.push({ref, selector: '[data-dlc-ref="' + ref + '"]',
            tag: el.tagName.toLowerCase(), type: el.type || el.getAttribute('role') || '',
            label: label.trim().slice(0,80), disabled: !!el.disabled,
            ...(el.tagName === 'A' ? {href: el.href} : {}),
            ...(el.value && el.type !== 'password' ? {value: el.value.slice(0,80)} : {}),
            ...(box.bottom > 0 && box.top < innerHeight && box.right > 0 && box.left < innerWidth
                ? {center: {x: Math.round(box.x + box.width/2), y: Math.round(box.y + box.height/2)}} : {})});
        if (elements.length >= 18) break;
    }
    return {url: location.href, title: document.title, ready_state: document.readyState,
        viewport: {width: innerWidth, height: innerHeight, device_scale_factor: devicePixelRatio},
        interactive_elements: elements,
        headings: Array.from(document.querySelectorAll('h1,h2,h3')).filter(visible).slice(0,8)
            .map(el => el.innerText.trim().slice(0,120)),
        main_text: ((document.querySelector('main,article,[role="main"],#content_left') || document.body).innerText || '').slice(0,1200)};
}'''


def save_screenshot(page, path):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    page.screenshot(path=path, full_page=False, scale='css', timeout=5000)
    return f'{path} [SCREENSHOT:{path}]'


def view_page(page, screenshot_path=None):
    info = {'success': True}
    if screenshot_path:
        try:
            info['screenshot'] = save_screenshot(page, screenshot_path)
        except Exception as e:
            info['screenshot_error'] = str(e)[:300]
    info.update(page.evaluate(SNAPSHOT))
    info['tab'] = [p for p in page.context.pages if not p.is_closed()].index(page)
    info['frames'] = page.evaluate("() => Array.from(document.querySelectorAll('iframe')).slice(0,8).map((el,i) => {el.setAttribute('data-dlc-frame', String(i)); return {selector:'iframe[data-dlc-frame=\"'+i+'\"]',title:el.title,url:el.src};})")
    return info


def has_target(act):
    return any(act.get(k) for k in ('selector', 'ref', 'role', 'label', 'text'))


def locator_for(page, act):
    scope = page.frame_locator(act['frame']) if act.get('frame') else page
    if act.get('ref'):
        ref = act['ref']
        if not isinstance(ref, str) or not ref.startswith('e') or not ref[1:].isdigit():
            raise ValueError('Invalid element ref; observe the page again')
        return scope.locator(f'[data-dlc-ref="{ref}"]')
    if act.get('selector'):
        return scope.locator(act['selector'])
    if act.get('role'):
        kwargs = {'name': act['name'], 'exact': True} if 'name' in act else {}
        return scope.get_by_role(act['role'], **kwargs)
    if act.get('label'):
        return scope.get_by_label(act['label'], exact=True)
    if act.get('text'):
        return scope.get_by_text(act['text'], exact=True)
    raise ValueError('Missing target: use ref, selector, role/name, label or text')


def validate_actions(actions):
    if not isinstance(actions, list) or not 1 <= len(actions) <= 20:
        raise ValueError('actions must be an array with 1..20 actions')
    for act in actions:
        if not isinstance(act, dict) or act.get('action') not in SUPPORTED:
            raise ValueError(f'Unsupported action: {act}')
        action = act['action']
        if action in TARGETED and not has_target(act):
            raise ValueError(f'{action} requires an element target')
        if action == 'goto' and urlparse(act.get('url', '')).scheme not in ('http', 'https', 'about', 'file'):
            raise ValueError('goto requires an absolute http(s), about or file URL')
        if action == 'click_xy' and not all(isinstance(act.get(k), (int, float)) and act[k] >= 0 for k in ('x', 'y')):
            raise ValueError('click_xy requires non-negative x/y viewport coordinates')
        if action in ('fill', 'type', 'select') and 'value' not in act:
            raise ValueError(f'{action} requires value')
        if action == 'assert' and not (has_target(act) or act.get('url')):
            raise ValueError('assert requires a target or URL pattern')


def settle(page):
    # Long polling/analytics prevent networkidle. Callers can wait/assert the
    # element or URL that actually indicates completion.
    try:
        page.wait_for_load_state('domcontentloaded', timeout=5000)
    except Exception:
        pass
    page.wait_for_timeout(150)


def perform_actions(page, actions, screenshot_path=None):
    validate_actions(actions)
    results = []
    failed_step = None
    for i, act in enumerate(actions):
        action = act['action']
        step = {'step': i + 1, 'action': action, 'success': True}
        try:
            timeout = min(15000, max(100, int(act.get('timeout', 7000))))
            before_pages = list(page.context.pages)
            target = locator_for(page, act) if has_target(act) else None
            if action in TARGETED | {'press'} and target is not None and target.count() == 1 and not target.is_visible():
                raise ValueError('Target is hidden. Choose the visible textarea/input/button ref from final.interactive_elements; do not retry this hidden selector.')
            if action == 'goto':
                response = page.goto(act['url'], timeout=30000, wait_until='domcontentloaded')
                if response is not None and response.status >= 400:
                    raise RuntimeError(f'HTTP {response.status} at {page.url}')
            elif action in ('back', 'forward', 'reload'):
                method = {'back': page.go_back, 'forward': page.go_forward, 'reload': page.reload}[action]
                method(wait_until='domcontentloaded', timeout=30000)
            elif action in ('click', 'check', 'uncheck', 'hover'):
                getattr(target, action)(timeout=timeout)
            elif action == 'click_xy':
                viewport = page.evaluate('() => ({width:innerWidth,height:innerHeight})')
                if act['x'] >= viewport['width'] or act['y'] >= viewport['height']:
                    raise ValueError('Coordinates outside viewport; observe/screenshot again')
                page.mouse.click(act['x'], act['y'])
            elif action == 'fill':
                target.fill(str(act['value']), timeout=timeout)
            elif action == 'type':
                target.press_sequentially(str(act['value']), timeout=timeout)
            elif action == 'select':
                target.select_option(act['value'], timeout=timeout)
            elif action == 'press':
                if target is not None:
                    target.press(act.get('key', 'Enter'), timeout=timeout)
                else:
                    page.keyboard.press(act.get('key', 'Enter'))
            elif action == 'scroll':
                amount = min(3000, max(0, int(act.get('amount', 500))))
                page.mouse.wheel(0, amount if act.get('direction', 'down') == 'down' else -amount)
            elif action == 'screenshot':
                step['result'] = save_screenshot(page, act.get('path') or screenshot_path or '/tmp/dlc-page.png')
            elif action == 'get_text':
                step['result'] = target.inner_text(timeout=timeout)[:1800]
            elif action == 'get_attr':
                step['result'] = target.get_attribute(act.get('attr', 'href'), timeout=timeout)
            elif action == 'evaluate':
                step['result'] = str(page.evaluate(act.get('script', '')))[:1800]
            elif action in ('wait', 'assert'):
                if act.get('url'):
                    page.wait_for_url(act['url'], timeout=timeout, wait_until='domcontentloaded')
                if target is not None:
                    target.wait_for(state=act.get('state', 'visible'), timeout=timeout)
                    if 'contains' in act and act['contains'] not in target.inner_text(timeout=timeout):
                        raise AssertionError(f"Expected text not present: {act['contains']}")
                elif not act.get('url'):
                    page.wait_for_timeout(min(timeout, 2000))
            if action in ('goto', 'click', 'click_xy', 'press', 'back', 'forward', 'reload', 'scroll'):
                settle(page)
                popups = [p for p in page.context.pages if p not in before_pages and not p.is_closed()]
                if popups:
                    page = popups[-1]
                    settle(page)
                    step['opened_tab'] = True
            step['url'] = page.url
        except Exception as e:
            step.update(success=False, error=str(e)[:700])
            failed_step = i + 1
        results.append(step)
        if failed_step is not None:
            break  # Never submit after a failed fill.
    output = {'success': failed_step is None, 'failed_step': failed_step}
    if failed_step is not None:
        output['error'] = results[-1]['error']
        output['skipped_actions'] = len(actions) - failed_step
    try:
        observation = view_page(page, screenshot_path)
        if observation.get('screenshot'):
            output['screenshot'] = observation.pop('screenshot')
        output.update({k: observation[k] for k in ('url','title','tab') if k in observation})
        output['actions'] = results
        output['final'] = observation
    except Exception as e:
        output.update(success=False, actions=results, observation_error=str(e)[:500])
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['view', 'action'])
    parser.add_argument('--cdp-url', default=DEFAULT_CDP_URL)
    parser.add_argument('--tab', type=int, default=0)
    parser.add_argument('--actions')
    parser.add_argument('--actions-file')
    parser.add_argument('--screenshot')
    args = parser.parse_args()
    try:
        actions = None
        if args.mode == 'action':
            raw = Path(args.actions_file).read_text(encoding='utf-8') if args.actions_file else args.actions
            actions = json.loads(raw or 'null')
            validate_actions(actions)
        with sync_playwright() as p:
            browser = connect_browser(p, args.cdp_url)
            page = get_page(browser, args.tab)
            page.set_default_timeout(7000)
            result = view_page(page, args.screenshot) if args.mode == 'view' else perform_actions(page, actions, args.screenshot)
            print(json.dumps(result, ensure_ascii=False, separators=(',', ':')))
            # Stop this local driver; preserve the externally owned Chrome.
            return 0 if result['success'] else 1
    except Exception as e:
        print(json.dumps({'success': False, 'error': str(e)[:1200], 'cdp_url': args.cdp_url}, ensure_ascii=False))
        return 1


if __name__ == '__main__':
    sys.exit(main())
