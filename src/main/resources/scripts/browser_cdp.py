#!/usr/bin/env python3
"""Observe and operate a long-lived Chrome over CDP without launching more windows."""
import argparse
import json
import re
import sys
import time
from pathlib import Path
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright

DEFAULT_CDP_URL = "http://127.0.0.1:9222"
SUPPORTED = {'goto', 'click', 'click_xy', 'fill', 'type', 'select', 'check', 'uncheck',
             'hover', 'press', 'scroll', 'screenshot', 'get_text', 'get_attr', 'evaluate',
             'wait', 'assert', 'back', 'forward', 'reload'}
# Actions that cannot run without an element target. `type` is deliberately absent:
# with no target it types into whatever the page has focused, which is the natural
# "click_xy then type" flow a model reaches for on a search box.
TARGETED = {'click', 'fill', 'select', 'check', 'uncheck', 'hover', 'get_text', 'get_attr'}

# A percent-escape whose byte is >= 0x80 means non-ASCII (almost always Chinese) was
# hand-encoded into the URL instead of typed into the site's own input box. That is
# how a real run produced a garbled city search and a 30s hang.
NON_ASCII_ESCAPE = re.compile(r'%[89A-Fa-f][0-9A-Fa-f]')

# Playwright states the verdict on the LAST call-log line, so a head-only truncation
# throws away the one sentence that explains the failure. A real run spent four calls
# re-picking refs after "click: Timeout 7000ms exceeded" while the removed line said
# the target was covered by a slider-captcha iframe.
KEEP_HEAD = 420
KEEP_TAIL = 260

# A covered target is not a broken selector. Playwright refuses the click because
# something else sits on top, and retrying with a different ref reproduces it exactly.
# Only lines that NAME an overlapping element count here: "element is not stable" and
# "element is outside of the viewport" describe the target itself, so treating them as
# obstructions would report the victim as its own blocker.
INTERCEPT_MARKERS = ('intercepts pointer events', 'subtree intercepts pointer events')

# Third-party widget hosts that mean a human has to act. Detected from the iframe URL
# rather than from the error text, because the error text rarely names the culprit.
CAPTCHA_HOSTS = ('captcha', 'slidervalid', 'verify', 'geetest', 'nc_', 'puzzle',
                 'baxia', 'slider', 'securitycheck')


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
    // getClientRects() only proves the element occupies space, not that a click can
    // reach it. A slider captcha iframe laid over the form satisfies both checks, so
    // the element was offered to the model with a click coordinate that could never
    // work. Probing the topmost node at the element's own centre separates "you can
    // click this" from "something is on top of this", before any click is attempted.
    const blockerAt = (x, y, el) => {
        let hit = null;
        try { hit = document.elementFromPoint(x, y); } catch (e) { return null; }
        if (!hit || hit === el || el.contains(hit) || hit.contains(el)) return null;
        return hit.tagName.toLowerCase() +
            (hit.id ? '#' + hit.id : '') +
            (hit.getAttribute && hit.getAttribute('class') ? '.' + String(hit.getAttribute('class')).trim().split(/\s+/).slice(0, 2).join('.') : '') +
            (hit.tagName === 'IFRAME' && hit.src ? ' src=' + hit.src.slice(0, 120) : '');
    };
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
        const entry = {ref, selector: '[data-dlc-ref="' + ref + '"]',
            tag: el.tagName.toLowerCase(), type: el.type || el.getAttribute('role') || '',
            label: label.trim().slice(0,80), disabled: !!el.disabled,
            ...(el.tagName === 'A' ? {href: el.href} : {}),
            ...(el.value && el.type !== 'password' ? {value: el.value.slice(0,80)} : {})};
        if (box.bottom > 0 && box.top < innerHeight && box.right > 0 && box.left < innerWidth) {
            const x = Math.round(box.x + box.width/2), y = Math.round(box.y + box.height/2);
            entry.center = {x, y};
            const blocker = blockerAt(x, y, el);
            if (blocker) { entry.obscured = true; entry.blocked_by = blocker; }
        }
        elements.push(entry);
        if (elements.length >= 18) break;
    }
    const frames = Array.from(document.querySelectorAll('iframe')).slice(0, 8);
    frames.forEach((el, i) => el.setAttribute('data-dlc-frame', String(i)));
    return {url: location.href, title: document.title, ready_state: document.readyState,
        viewport: {width: innerWidth, height: innerHeight, device_scale_factor: devicePixelRatio},
        interactive_elements: elements,
        headings: Array.from(document.querySelectorAll('h1,h2,h3')).filter(visible).slice(0,8)
            .map(el => el.innerText.trim().slice(0,120)),
        main_text: ((document.querySelector('main,article,[role="main"],#content_left') || document.body).innerText || '').slice(0,1200),
        // A full-viewport iframe is a takeover: even elements this pass did not reach
        // are unusable, so the page itself has to be reported as blocked. Without it a
        // captcha is only discovered by clicking something and reading a timeout.
        page_blocked: (() => {
            for (const f of frames) {
                const b = f.getBoundingClientRect();
                const big = b.width >= innerWidth * 0.6 && b.height >= innerHeight * 0.4;
                if (big && visible(f)) {
                    return {kind: 'overlay', element: 'iframe' + (f.src ? ' src=' + f.src.slice(0, 120) : ''),
                        note: 'A large iframe covers the page. Anything clicked underneath is intercepted; '
                            + 'identify the iframe before choosing a target.'};
                }
            }
            return null;
        })()};
}'''


def classify_frame_url(url):
    """'captcha' when a frame looks like a human-verification widget.

    <p>The URL is the only reliable signal: the blocking error names the iframe but
    not its purpose, and a widget's own markup lives in a third-party document that
    cannot be read from here.</p>
    """
    lowered = (url or '').lower()
    return any(host in lowered for host in CAPTCHA_HOSTS)


FRAMES = r'''() => Array.from(document.querySelectorAll('iframe')).slice(0, 8).map((el, i) => {
    el.setAttribute('data-dlc-frame', String(i));
    const url = el.src || '';
    return {selector: 'iframe[data-dlc-frame="' + i + '"]', title: el.title, url: url,
        captcha: /captcha|slidervalid|verify|geetest|nc_|puzzle|baxia|slider|securitycheck/i.test(url)};
})'''


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
    info['frames'] = page.evaluate(FRAMES)
    # A verification widget cannot be clicked through, so the page has to say so
    # before the model picks a target it can never reach. Overrides the generic
    # "large iframe" note, which does not tell the model what to do about it.
    captcha = next((f['url'] for f in info['frames'] if f.get('captcha')), None)
    if captcha:
        info['page_blocked'] = {
            'kind': 'captcha',
            'element': 'iframe[data-dlc-frame="%d"]' % [f['url'] for f in info['frames']].index(captcha),
            'url': captcha[:160],
            'note': 'A CAPTCHA/verification iframe is present on this page. It intercepts clicks on the '
                    'form underneath and retrying cannot get past it: the user must complete it in the '
                    'visible browser window, then you re-observe with browser_view and continue. Do not '
                    'try other refs, other selectors or force clicks.'}
    return info


def target_hint(action):
    """The exact JSON to write once a target is missing, so the next try succeeds."""
    value = ',"value":"..."' if action in ('fill', 'select') else ''
    return '{"action":"%s","ref":"e3"%s}' % (action, value)


def has_target(act):
    return any(act.get(k) for k in ('selector', 'ref', 'role', 'label', 'text'))


def normalize_action(act):
    """Fold the natural-but-unofficial parameter spellings onto the canonical ones.

    A rejected guess costs a whole tool round trip, and one run spent four calls
    re-typing the same intent with `text`, `keys` and `ms`. Each alias below is
    unambiguous, so it is accepted rather than errored:

    - `text` on fill/type/select is always the *value*: a text box is never located
      by its own content, so the two meanings of `text` cannot collide.
    - `keys` on press is `key` (e.g. "Control+A").
    """
    action = act.get('action')
    if action in ('fill', 'type', 'select') and 'value' not in act and 'text' in act:
        act = dict(act)
        act['value'] = act.pop('text')
    if action == 'press' and 'key' not in act and 'keys' in act:
        act = dict(act)
        act['key'] = act.pop('keys')
    return act


def wait_millis(act):
    """Sleep duration for a bare `wait`, accepting the spellings models actually use.

    `ms` and `timeout` are milliseconds. A bare `time` is read as seconds when it is
    small ("time": 6 meaning six seconds) and as milliseconds otherwise.
    """
    for key in ('ms', 'timeout'):
        if act.get(key) is not None:
            return max(0, min(10000, int(act[key])))
    if act.get('time') is not None:
        value = float(act['time'])
        millis = value * 1000 if value <= 30 else value
        return max(0, min(10000, int(millis)))
    return 2000


def target_candidates(page, act):
    """Element locators, best first. Later entries exist so that a selector matching
    several elements degrades into a usable match instead of a strict-mode failure."""
    scope = page.frame_locator(act['frame']) if act.get('frame') else page
    if act.get('ref'):
        ref = act['ref']
        if not isinstance(ref, str) or not ref.startswith('e') or not ref[1:].isdigit():
            raise ValueError('Invalid element ref; observe the page again')
        return [scope.locator(f'[data-dlc-ref="{ref}"]')]
    if act.get('selector'):
        selector = act['selector']
        return [scope.locator(selector), scope.locator(selector + ':visible'),
                scope.locator(selector).first]
    if act.get('role'):
        kwargs = {'name': act['name'], 'exact': True} if 'name' in act else {}
        locator = scope.get_by_role(act['role'], **kwargs)
        return [locator, locator.first]
    if act.get('label'):
        locator = scope.get_by_label(act['label'], exact=True)
        return [locator, locator.first]
    if act.get('text'):
        locator = scope.get_by_text(act['text'], exact=True)
        return [locator, locator.first]
    raise ValueError('Missing target: use ref, selector, role/name, label or text')


def pick_target(page, act):
    """Resolve a target as (locator, narrowed). Prefer a unique match; when several
    elements collide, take the first one and flag that the selector was narrowed."""
    candidates = target_candidates(page, act)
    collision = None
    for index, locator in enumerate(candidates):
        try:
            count = locator.count()
        except Exception:
            continue
        if count == 1:
            return locator, index > 0
        if count > 1 and collision is None:
            collision = locator
    if collision is not None:
        return collision.first, True
    return candidates[-1], False


def validate_actions(actions):
    """Batch shape only: normalising every action up front keeps field checks simple.

    Field-level checks live in validate_step so a typo in one action reports against
    that step and leaves the actions before it executed. A single misspelled
    parameter used to discard the whole batch before anything ran.
    """
    if not isinstance(actions, list) or not 1 <= len(actions) <= 20:
        raise ValueError('actions must be an array with 1..20 actions')
    for index, act in enumerate(actions):
        if not isinstance(act, dict) or act.get('action') not in SUPPORTED:
            raise ValueError(f'Unsupported action {act}; supported: {sorted(SUPPORTED)}')
        actions[index] = normalize_action(act)


def validate_step(act):
    """Field checks for one action. Every message names the accepted spelling, so the
    next attempt is correct instead of another guess (each guess costs a round trip)."""
    action = act['action']
    if action in TARGETED and not has_target(act):
        raise ValueError(f'{action} requires an element target: pass "ref" from the latest '
                         f'observation, or selector / role+name / label. Example: {target_hint(action)}')
    if action == 'goto':
        url = act.get('url', '')
        if urlparse(url).scheme not in ('http', 'https', 'about', 'file'):
            raise ValueError('goto requires an absolute URL: {"action":"goto","url":"https://example.com"}')
        if NON_ASCII_ESCAPE.search(url):
            raise ValueError(
                'Refusing a URL with hand-encoded non-ASCII (e.g. %E7%9F%B3). Do not hand-write '
                'Chinese into a URL: type it into the site\'s own input box and pick its suggestion, '
                'or reuse a result-page URL that already loaded. For a link already on the page, '
                'click it instead of using goto.')
    if action == 'click_xy' and not all(isinstance(act.get(k), (int, float)) and act[k] >= 0 for k in ('x', 'y')):
        raise ValueError('click_xy requires non-negative x/y viewport coordinates: '
                         '{"action":"click_xy","x":120,"y":340}')
    if action in ('fill', 'type', 'select') and 'value' not in act:
        raise ValueError('"%s" requires "value" (the text to enter), not "text". Use '
                         '{"action":"%s","ref":"e3","value":"..."}; with no target, "type" types '
                         'into whatever the page has focused.' % (action, action))
    if action == 'press' and not act.get('key'):
        raise ValueError('press requires "key", e.g. {"action":"press","key":"Control+A"} '
                         'or {"action":"press","key":"Enter"}')
    if action == 'assert' and not (has_target(act) or act.get('url') or 'contains' in act):
        raise ValueError('assert requires a target, a "url" pattern, or "contains": '
                         '{"action":"assert","contains":"¥"}')
    if isinstance(act.get('text'), str) and '\n' in act['text']:
        raise ValueError('"text" targets must be a single line — an exact multi-line match never '
                         'resolves. Use "ref" from the latest observation, or a short stable substring.')


def stop_loading(page):
    """Best-effort abort of a stuck navigation so the next call is not blocked.

    An unfinished navigation keeps the renderer busy; without this the follow-up
    CDP commands time out and the session looks dead for the rest of the turn.
    """
    try:
        page.evaluate('() => window.stop()')
    except Exception:
        pass


def clip_error(text, head=KEEP_HEAD, tail=KEEP_TAIL):
    """Keep both ends of a Playwright error.

    Its call log is chronological and the verdict lands on the last line
    ("… intercepts pointer events"), so a head-only cut reports the symptom and drops
    the cause. The tail is what turns "click timed out" into "a captcha is on top of
    this element", which is the difference between a fixable retry and a dead end.
    """
    text = text or ''
    if len(text) <= head + tail:
        return text
    return text[:head] + '\n...[call log trimmed]...\n' + text[-tail:]


def snapshot_obstruction(page, target):
    """Re-read the live page for an element matching `target` that is covered.

    <p>Used when the error text itself did not name a blocker (an unstable or
    off-screen target produces the same refusal with no element in the log). One DOM
    round trip, and only on the failure path.</p>
    """
    if not target:
        return None
    selector = target if target.startswith(('[', '#', '.', 'text=', ':')) \
        else '[data-dlc-ref="%s"]' % target
    try:
        found = page.evaluate(OCCLUDED_PROBE, selector)
    except Exception:
        return None
    return found if isinstance(found, str) and found else None


OCCLUDED_PROBE = r'''selector => {
    const el = document.querySelector(selector);
    if (!el || !el.getClientRects().length) return null;
    const box = el.getBoundingClientRect();
    const x = Math.round(box.x + box.width / 2), y = Math.round(box.y + box.height / 2);
    let hit = null;
    try { hit = document.elementFromPoint(x, y); } catch (e) { return null; }
    if (!hit || hit === el || el.contains(hit) || hit.contains(el)) return null;
    return hit.tagName.toLowerCase() +
        (hit.id ? '#' + hit.id : '') +
        (hit.getAttribute && hit.getAttribute('class') ? '.' + String(hit.getAttribute('class')).trim().split(/\s+/).slice(0, 2).join('.') : '') +
        (hit.tagName === 'IFRAME' && hit.src ? ' src=' + hit.src.slice(0, 120) : '');
}'''


def interception_reason(error_text):
    """The element Playwright named as the obstruction, when it named one.

    <p>Parsed out of the error instead of re-queried, because the offender is on top
    of the target by definition: re-locating it after the fact usually finds nothing.
    The last such line wins — Playwright logs the blocker again on every retry, and
    the final one is the element still covering the target when it gave up.</p>
    """
    if not error_text:
        return None
    lines = [line.strip().lstrip('-').strip() for line in error_text.splitlines()]
    for line in reversed(lines):
        if not any(marker in line.lower() for marker in INTERCEPT_MARKERS):
            continue
        # "…</iframe> intercepts pointer events" -> keep the element, drop the verdict.
        element = line[:line.lower().rindex('intercepts')] if 'intercepts' in line.lower() else line
        element = element.rsplit('</', 1)[0].strip() if '</' in element else element.strip()
        return re.sub(r'\s+', ' ', element)[:200] or 'an overlapping element'
    return None


class BlockedTarget(Exception):
    """A refused interaction whose cause is a covering element, not a bad target.

    Carries the obstruction separately from the message so the caller can label the
    failure (captcha vs ordinary overlay) without re-parsing English prose.
    """

    def __init__(self, message, blocker, kind):
        super().__init__(message)
        self.blocker = blocker
        self.kind = kind


CAPTCHA_ADVICE = ('This is a CAPTCHA/verification overlay. Retrying, switching refs and forcing the click '
                  'all fail identically, and force-clicking would hit the widget instead of the form. Stop '
                  'here and tell the user: a verification step on this page needs them to complete it in the '
                  'visible browser window. Keep the window open, state which step is blocked and what has '
                  'already been collected, then stop and wait — do not present the remaining data as "not '
                  'available" when it is only blocked.')

OBSCURED_ADVICE = ('The element exists and is enabled; only the click is blocked. Do not change the ref or '
                   'the selector — the same obstruction will reject them too. Identify what is on top from '
                   'the latest screenshot, then either dismiss it (close button / Escape) or work around it '
                   '(use the frame selector for a widget in an iframe, or reach the same value through a '
                   'different page).')


def obstruction_advice(kind):
    return CAPTCHA_ADVICE if kind == 'captcha' else OBSCURED_ADVICE


def describe_obstruction(page, error_text, act):
    """Turn a refused interaction into an actionable verdict.

    <p>Without this the raw timeout reaches the model, which reads it as "the page is
    slow" or "the selector is wrong" and retries. Both are wrong when something is
    covering the target: the element resolved and is visible, stable and enabled —
    exactly what the error says — and only the hit test fails. Naming the blocker, and
    saying that the ref is fine, is what stops the retry loop.</p>
    """
    blocker = interception_reason(error_text)
    target = act.get('ref') or act.get('selector') or act.get('text') or act.get('label') or 'the target'
    if blocker is None:
        # No interception in the log, but the element may still be covered — the
        # snapshot recorded that at observation time and the page may not have moved.
        blocker = snapshot_obstruction(page, target)
    if blocker is None:
        return None
    kind = 'captcha' if classify_frame_url(blocker) else 'obscured'
    return {'blocked_by': blocker, 'blocked_kind': kind, 'advice': obstruction_advice(kind)}


def settle(page):
    # Long polling/analytics prevent networkidle. Callers can wait/assert the
    # element or URL that actually indicates completion.
    try:
        page.wait_for_load_state('domcontentloaded', timeout=5000)
    except Exception:
        pass
    page.wait_for_timeout(150)


def settle_dynamic(page, budget_ms=2000, step_ms=350):
    """Give an async-rendered page a bounded chance to finish before it is read.

    A modern results page commits its DOM long before the content arrives: the
    flight list renders its price rows from a later XHR, so `domcontentloaded` is
    not evidence that the prices are on the page. Reading at that moment yields a
    skeleton, and the model then either reports nothing or reuses a value it saw on
    another page. Waiting for the visible text to STOP GROWING catches that gap
    without coupling to any site's selector, element id or price format.

    Bounded on both ends: a page already stable costs one extra probe, and a page
    that never settles costs at most `budget_ms` so this cannot become the next
    hung navigation.
    """
    deadline = time.monotonic() + budget_ms / 1000.0
    previous = None
    while True:
        try:
            length = page.evaluate('() => (document.body ? document.body.innerText.length : 0)')
        except Exception:
            return
        # Two identical non-empty readings mean rendering has paused long enough
        # to be worth reading; an empty body keeps polling until the budget ends.
        if previous is not None and length == previous and length > 0:
            return
        previous = length
        if time.monotonic() >= deadline:
            return
        try:
            page.wait_for_timeout(step_ms)
        except Exception:
            return


def state_fingerprint(page):
    """Cheap URL + text-length signature, used to spot a click that did nothing."""
    try:
        return (page.url, page.evaluate('() => (document.body ? document.body.innerText.length : 0)'))
    except Exception:
        return None


def perform_actions(page, actions, screenshot_path=None):
    validate_actions(actions)
    results = []
    failed_step = None
    blocked_step = None
    for i, act in enumerate(actions):
        action = act['action']
        step = {'step': i + 1, 'action': action, 'success': True}
        try:
            validate_step(act)
            timeout = min(15000, max(100, int(act.get('timeout', 7000))))
            before_pages = list(page.context.pages)
            target = None
            if has_target(act):
                target, narrowed = pick_target(page, act)
                if narrowed:
                    step['narrowed'] = True
            # wait/assert may legitimately target an element that has not appeared
            # yet, so the "nothing there" verdict only applies to actions that need
            # the target right now. A stale ref otherwise surfaces as a raw
            # Playwright timeout, which reads like a page problem instead of "your
            # ref is from before the page changed — observe again".
            if target is not None and action not in ('wait', 'assert'):
                try:
                    count = target.count()
                except Exception:
                    count = None
                if count == 0:
                    if act.get('ref'):
                        where = 'ref "%s" is stale (the page changed after the observation)' % act['ref']
                    elif act.get('selector'):
                        where = 'selector %r matched nothing' % act['selector']
                    else:
                        where = 'the target did not resolve'
                    raise ValueError('Target not found: %s. Call browser_view and use a fresh "ref" '
                                     'from final.interactive_elements instead of retrying this one.' % where)
                if action in TARGETED | {'press', 'type'} and count == 1 and not target.is_visible():
                    raise ValueError('Target is hidden. Choose the visible textarea/input/button ref from final.interactive_elements; do not retry this hidden selector.')
            before_state = state_fingerprint(page) if action in ('click', 'click_xy', 'press') else None
            if action == 'goto':
                # 'commit' returns as soon as the navigation commits. Waiting for
                # domcontentloaded on a host that never answers blocks for 30s and can
                # leave the whole browser unresponsive; the explicit wait/assert steps
                # (and settle below) are what confirm the page actually arrived.
                try:
                    response = page.goto(act['url'], timeout=20000, wait_until='commit')
                except Exception:
                    stop_loading(page)
                    raise
                if response is not None and response.status >= 400:
                    raise RuntimeError(f'HTTP {response.status} at {page.url}')
            elif action in ('back', 'forward', 'reload'):
                method = {'back': page.go_back, 'forward': page.go_forward, 'reload': page.reload}[action]
                try:
                    method(wait_until='commit', timeout=20000)
                except Exception:
                    stop_loading(page)
                    raise
            elif action in ('click', 'check', 'uncheck', 'hover'):
                # Ask the page who is on top before spending the click budget on a
                # target that cannot be reached. The refusal costs 7s and produces a
                # timeout the model reads as "slow page" — this costs one round trip
                # and names the obstruction outright.
                blocker = snapshot_obstruction(page, act.get('ref') or act.get('selector')
                                               or act.get('label') or act.get('text') or '')
                if blocker:
                    step.update(success=False, blocked_by=blocker[:200],
                                blocked_kind='captcha' if classify_frame_url(blocker) else 'obscured',
                                error='%s is covered by %s, so the %s was refused. The element itself '
                                      'resolved and is visible/enabled — this is an obstruction, not a '
                                      'bad selector.' % (
                                          act.get('ref') or act.get('selector') or 'the target',
                                          blocker[:160], action))
                    raise BlockedTarget(step['error'], blocker[:200], step['blocked_kind'])
                getattr(target, action)(timeout=timeout)
            elif action == 'click_xy':
                viewport = page.evaluate('() => ({width:innerWidth,height:innerHeight})')
                if act['x'] >= viewport['width'] or act['y'] >= viewport['height']:
                    raise ValueError('Coordinates outside viewport; observe/screenshot again')
                page.mouse.click(act['x'], act['y'])
            elif action == 'fill':
                target.fill(str(act['value']), timeout=timeout)
            elif action == 'type':
                if target is not None:
                    target.press_sequentially(str(act['value']), timeout=timeout)
                else:
                    # No target: type into whatever the page has focused. This is the
                    # "click_xy on the search box, then type" flow, and Playwright's
                    # keyboard.type does exactly that — the click already set focus.
                    page.keyboard.type(str(act['value']), delay=0)
            elif action == 'select':
                target.select_option(act['value'], timeout=timeout)
            elif action == 'press':
                if target is not None:
                    target.press(act['key'], timeout=timeout)
                else:
                    page.keyboard.press(act['key'])
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
                elif 'contains' in act:
                    # Assertion against the whole page, so `{"action":"assert","contains":"¥"}`
                    # works without naming an element first.
                    body = page.inner_text('body', timeout=timeout)
                    if act['contains'] not in body:
                        raise AssertionError(f"Expected text not present on page: {act['contains']}")
                elif action == 'wait' and not act.get('url'):
                    page.wait_for_timeout(wait_millis(act))
            if action in ('goto', 'click', 'click_xy', 'press', 'back', 'forward', 'reload', 'scroll'):
                settle(page)
                popups = [p for p in page.context.pages if p not in before_pages and not p.is_closed()]
                if popups:
                    page = popups[-1]
                    settle(page)
                    step['opened_tab'] = True
                # Only a page that actually navigated needs the extra settle; a
                # same-page click or a scroll leaves the rendered text in place.
                navigated = action in ('goto', 'back', 'forward', 'reload') or (
                    before_state is not None and before_state[0] != page.url)
                if navigated:
                    settle_dynamic(page)
            if before_state is not None:
                after_state = state_fingerprint(page)
                if after_state is not None and after_state == before_state:
                    step['no_op'] = True
                    step['note'] = ('URL and page text did not change after this action, so it probably '
                                    'did not take effect. Re-observe the page before assuming success.')
            step['url'] = page.url
        except Exception as e:
            message = clip_error(str(e))
            if isinstance(e, BlockedTarget):
                # Refused by the pre-flight probe, so no call log to parse; the
                # guidance is the same advice a parsed interception would produce.
                verdict = {'blocked_by': e.blocker, 'blocked_kind': e.kind,
                           'advice': obstruction_advice(e.kind)}
            else:
                # The element may still have been covered when Playwright refused for a
                # reason it did not spell out (unstable, off-screen, animating). Reading
                # it here keeps those failures out of the "bad selector" bucket, which is
                # where a retry loop starts.
                verdict = describe_obstruction(page, message, act) or {}
            step.update(success=False, error=message)
            step.update(verdict)
            failed_step = i + 1
            blocked_step = verdict or None
        results.append(step)
        if failed_step is not None:
            break  # Never submit after a failed fill.
    output = {'success': failed_step is None, 'failed_step': failed_step}
    if failed_step is not None:
        output['error'] = results[-1]['error']
        output['skipped_actions'] = len(actions) - failed_step
    # Hoisted to the top level: the compaction path keeps a short field list, and the
    # loop's verdict on "this is not retryable" must survive that without being read
    # out of a nested actions[] entry.
    if blocked_step:
        output['blocked_by'] = blocked_step['blocked_by']
        output['blocked_kind'] = blocked_step['blocked_kind']
        output['advice'] = blocked_step['advice']
    try:
        observation = view_page(page, screenshot_path)
        if observation.get('screenshot'):
            output['screenshot'] = observation.pop('screenshot')
        output.update({k: observation[k] for k in ('url','title','tab') if k in observation})
        if blocked_step and not output.get('blocked_kind'):
            # The overlay disappeared while we were reporting it; keep what we saw.
            output['blocked_by'] = blocked_step['blocked_by']
            output['blocked_kind'] = blocked_step['blocked_kind']
        output['actions'] = results
        output['final'] = observation
        # Only a failed step may be attributed to a page-level blocker. Doing this
        # unconditionally labelled a successful click on an uncovered element as
        # "captcha" just because a widget happened to sit elsewhere on the page.
        if blocked_step and observation.get('page_blocked') and not output.get('blocked_kind'):
            output['blocked_by'] = observation['page_blocked'].get('element', blocked_step['blocked_by'])
            output['blocked_kind'] = observation['page_blocked']['kind']
    except Exception as e:
        output.update(success=False, actions=results, observation_error=clip_error(str(e), 240, 240))
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
        print(json.dumps({'success': False, 'error': clip_error(str(e)), 'cdp_url': args.cdp_url}, ensure_ascii=False))
        return 1


if __name__ == '__main__':
    sys.exit(main())
