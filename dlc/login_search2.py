from playwright.sync_api import sync_playwright
import time

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page()
    
    # 访问网站
    page.goto('https://www.baidu.com/')
    page.wait_for_load_state('networkidle')
    
    print("=== 步骤 1: 访问登录页面 ===")
    page.screenshot(path='dlc/step1_login.png', full_page=True)
    
    # 查找并填写用户名
    print("正在查找用户名输入框...")
    username_input = page.locator('input[type="text"]').first
    username_input.fill('zhaohuixin')
    print("已输入用户名")
    
    # 查找并填写密码
    print("正在查找密码输入框...")
    password_input = page.locator('input[type="password"]').first
    password_input.fill('zhaohuixin@321')
    print("已输入密码")
    
    # 查找登录按钮并点击
    print("正在查找登录按钮...")
    login_button = page.locator('button:has-text("登录"), button[type="submit"]').first
    login_button.click()
    print("已点击登录按钮")
    
    # 等待登录完成
    page.wait_for_load_state('networkidle')
    time.sleep(3)
    
    print("\n=== 步骤 2: 登录成功后 ===")
    page.screenshot(path='dlc/step2_logged_in.png', full_page=True)
    print(f"当前页面标题: {page.title()}")
    print(f"当前 URL: {page.url}")
    
    # 探索页面上的所有输入框和按钮
    print("\n=== 页面上的输入框 ===")
    inputs = page.locator('input').all()
    for i, inp in enumerate(inputs):
        try:
            placeholder = inp.get_attribute('placeholder')
            input_type = inp.get_attribute('type')
            input_name = inp.get_attribute('name')
            print(f"输入框 {i}: type={input_type}, name={input_name}, placeholder={placeholder}")
        except:
            pass
    
    print("\n=== 页面上的按钮 ===")
    buttons = page.locator('button').all()
    for i, btn in enumerate(buttons):
        try:
            btn_text = btn.inner_text()
            print(f"按钮 {i}: {btn_text}")
        except:
            pass
    
    # 查找搜索框
    print("\n=== 步骤 3: 搜索电饭锅 ===")
    search_input = page.locator('input[placeholder*="搜索"], input[placeholder*="商品"], input[type="search"]').first
    if search_input.is_visible():
        search_input.fill('电饭锅')
        print("已输入搜索词：电饭锅")
        
        # 点击搜索按钮或按回车
        search_button = page.locator('button:has-text("搜索"), .search-btn').first
        if search_button.is_visible():
            search_button.click()
            print("已点击搜索按钮")
        else:
            # 按回车键搜索
            search_input.press('Enter')
            print("已按回车键搜索")
    else:
        print("未找到搜索框，尝试其他方式...")
        # 尝试在所有输入框中输入
        for inp in inputs:
            try:
                if inp.is_visible():
                    inp.fill('电饭锅')
                    inp.press('Enter')
                    print("尝试在输入框中搜索")
                    break
            except:
                pass
    
    # 等待搜索结果
    page.wait_for_load_state('networkidle')
    time.sleep(3)
    
    print("\n=== 步骤 4: 搜索结果 ===")
    page.screenshot(path='dlc/step4_search_result.png', full_page=True)
    
    # 获取页面内容
    result_content = page.content()
    
    # 检查是否有电饭锅相关文字
    if '电饭锅' in result_content or '电饭煲' in result_content:
        print("✓ 平台上有电饭锅类商品！")
    else:
        print("✗ 未找到电饭锅类商品")
    
    # 检查是否有商品相关的元素
    product_items = page.locator('[class*="product"], [class*="goods"], [class*="item"], .card').all()
    print(f"找到的商品相关元素数量: {len(product_items)}")
    
    # 打印页面文本内容（前 2000 字符）
    page_text = page.locator('body').inner_text()
    print(f"\n页面文本内容 (前 1000 字符):\n{page_text[:1000]}")
    
    browser.close()
