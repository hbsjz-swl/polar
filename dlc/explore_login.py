from playwright.sync_api import sync_playwright
import time

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page()
    
    # 访问网站
    page.goto('https://www.baidu.com/')
    page.wait_for_load_state('networkidle')
    
    print("=== 登录页面元素探索 ===")
    page.screenshot(path='dlc/explore_login.png', full_page=True)
    
    # 查找所有输入框
    print("\n=== 输入框 ===")
    inputs = page.locator('input').all()
    for i, inp in enumerate(inputs):
        try:
            if inp.is_visible():
                placeholder = inp.get_attribute('placeholder')
                input_type = inp.get_attribute('type')
                input_name = inp.get_attribute('name')
                input_id = inp.get_attribute('id')
                print(f"输入框 {i}: type={input_type}, id={input_id}, name={input_name}, placeholder={placeholder}")
        except:
            pass
    
    # 查找所有按钮
    print("\n=== 按钮 ===")
    buttons = page.locator('button').all()
    for i, btn in enumerate(buttons):
        try:
            if btn.is_visible():
                btn_text = btn.inner_text()
                btn_class = btn.get_attribute('class')
                btn_id = btn.get_attribute('id')
                print(f"按钮 {i}: id={btn_id}, class={btn_class}, text='{btn_text}'")
        except:
            pass
    
    # 查找所有可点击元素
    print("\n=== 可点击元素 (a 标签) ===")
    links = page.locator('a').all()
    for i, link in enumerate(links):
        try:
            if link.is_visible():
                link_text = link.inner_text()
                link_href = link.get_attribute('href')
                print(f"链接 {i}: href={link_href}, text='{link_text}'")
        except:
            pass
    
    # 获取页面 HTML 结构
    print("\n=== 页面 HTML (body 标签) ===")
    body_html = page.locator('body').inner_html()
    print(body_html[:3000])
    
    browser.close()
