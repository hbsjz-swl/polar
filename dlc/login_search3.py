from playwright.sync_api import sync_playwright
import time

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page()
    
    # 访问网站
    page.goto('https://platform.dlchm.cn/')
    page.wait_for_load_state('networkidle')
    
    print("=== 步骤 1: 登录 ===")
    
    # 填写用户名
    page.locator('input[placeholder="用户名"]').fill('zhaohuixin')
    print("已输入用户名")
    
    # 填写密码
    page.locator('input[placeholder="密码"]').fill('zhaohuixin@321')
    print("已输入密码")
    
    # 点击登录按钮 (div 元素)
    page.locator('div.default-btn.primary-btn:has-text("登录")').click()
    print("已点击登录按钮")
    
    # 等待登录完成
    page.wait_for_load_state('networkidle')
    time.sleep(3)
    
    print("\n=== 步骤 2: 登录成功 ===")
    page.screenshot(path='dlc/logged_in.png', full_page=True)
    print(f"当前页面标题: {page.title()}")
    print(f"当前 URL: {page.url}")
    
    # 探索页面上的输入框和按钮
    print("\n=== 页面上的输入框 ===")
    inputs = page.locator('input').all()
    for i, inp in enumerate(inputs):
        try:
            if inp.is_visible():
                placeholder = inp.get_attribute('placeholder')
                input_type = inp.get_attribute('type')
                print(f"输入框 {i}: type={input_type}, placeholder={placeholder}")
        except:
            pass
    
    print("\n=== 页面上的按钮 ===")
    buttons = page.locator('button, div[class*="btn"], span[class*="btn"]').all()
    for i, btn in enumerate(buttons):
        try:
            if btn.is_visible():
                btn_text = btn.inner_text()
                btn_class = btn.get_attribute('class')
                print(f"按钮 {i}: class={btn_class}, text='{btn_text}'")
        except:
            pass
    
    # 查找搜索框并搜索
    print("\n=== 步骤 3: 搜索电饭锅 ===")
    
    # 尝试多种搜索框选择器
    search_input = None
    search_selectors = [
        'input[placeholder*="搜索"]',
        'input[placeholder*="商品"]',
        'input[type="search"]',
        'input.el-input__inner'
    ]
    
    for selector in search_selectors:
        try:
            elements = page.locator(selector).all()
            for el in elements:
                if el.is_visible():
                    search_input = el
                    print(f"找到搜索框: {selector}")
                    break
        except:
            pass
        if search_input:
            break
    
    if search_input:
        search_input.fill('电饭锅')
        print("已输入搜索词：电饭锅")
        
        # 查找搜索按钮
        search_btn = page.locator('button:has-text("搜索"), div:has-text("搜索"), span:has-text("搜索"), i.el-icon-search').first
        try:
            if search_btn.is_visible():
                search_btn.click()
                print("已点击搜索按钮")
            else:
                search_input.press('Enter')
                print("已按回车键搜索")
        except:
            search_input.press('Enter')
            print("已按回车键搜索")
    else:
        print("未找到搜索框，尝试在第一个输入框中输入")
        if inputs:
            inputs[0].fill('电饭锅')
            inputs[0].press('Enter')
    
    # 等待搜索结果
    page.wait_for_load_state('networkidle')
    time.sleep(3)
    
    print("\n=== 步骤 4: 搜索结果 ===")
    page.screenshot(path='dlc/search_result.png', full_page=True)
    
    # 获取页面内容
    result_content = page.content()
    page_text = page.locator('body').inner_text()
    
    # 检查是否有电饭锅相关文字
    if '电饭锅' in result_content or '电饭煲' in result_content:
        print("✓ 平台上有电饭锅类商品！")
    else:
        print("✗ 未找到电饭锅类商品")
    
    # 检查是否有商品列表
    product_items = page.locator('[class*="product"], [class*="goods"], [class*="item"], .card, .list-item').all()
    print(f"找到的商品相关元素数量: {len(product_items)}")
    
    # 打印页面文本内容
    print(f"\n页面文本内容 (前 1500 字符):\n{page_text[:1500]}")
    
    browser.close()
