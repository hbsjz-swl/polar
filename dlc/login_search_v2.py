from playwright.sync_api import sync_playwright
import time

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    context = browser.new_context()
    page = context.new_page()
    
    # 捕获控制台日志
    console_logs = []
    page.on('console', lambda msg: console_logs.append(f"{msg.type}: {msg.text}"))
    
    # 访问网站
    print("正在访问网站...")
    page.goto('https://www.baidu.com/')
    page.wait_for_load_state('networkidle')
    time.sleep(2)
    
    # 截图查看登录页面
    page.screenshot(path='dlc/login_page_v2.png', full_page=True)
    print("登录页面截图已保存到 dlc/login_page_v2.png")
    
    # 获取所有输入框和按钮
    print("\n=== 页面元素分析 ===")
    
    # 查找所有输入框
    inputs = page.locator('input').all()
    print(f"\n找到 {len(inputs)} 个输入框:")
    for i, inp in enumerate(inputs):
        try:
            if inp.is_visible():
                placeholder = inp.get_attribute('placeholder')
                input_type = inp.get_attribute('type')
                input_id = inp.get_attribute('id')
                input_name = inp.get_attribute('name')
                print(f"  [{i}] type={input_type}, placeholder={placeholder}, id={input_id}, name={input_name}")
        except:
            pass
    
    # 查找所有按钮
    buttons = page.locator('button, .default-btn, [role="button"]').all()
    print(f"\n找到 {len(buttons)} 个按钮:")
    for i, btn in enumerate(buttons):
        try:
            if btn.is_visible():
                text = btn.inner_text()
                btn_class = btn.get_attribute('class')
                print(f"  [{i}] text='{text}', class={btn_class}")
        except:
            pass
    
    # 填写用户名和密码
    print("\n=== 填写登录信息 ===")
    
    # 找到用户名输入框 (placeholder="用户名")
    username_input = page.locator('input[placeholder="用户名"]')
    if username_input.is_visible():
        username_input.fill('zhaohuixin')
        print("已填写用户名")
    
    # 找到密码输入框 (placeholder="密码")
    password_input = page.locator('input[placeholder="密码"]')
    if password_input.is_visible():
        password_input.fill('zhaohuixin@321')
        print("已填写密码")
    
    time.sleep(1)
    
    # 点击登录按钮
    print("\n=== 点击登录按钮 ===")
    login_btn = page.locator('.default-btn.primary-btn:has-text("登录"), button:has-text("登录")')
    if login_btn.is_visible():
        print("找到登录按钮，点击中...")
        login_btn.click()
        
        # 等待响应
        page.wait_for_load_state('networkidle')
        time.sleep(3)
        
        # 截图
        page.screenshot(path='dlc/after_click_v2.png', full_page=True)
        print("点击后截图已保存")
    
    # 检查是否出现验证码
    verify_box = page.locator('.verifybox').first
    if verify_box.is_visible():
        print("\n⚠️  出现验证码弹窗，需要人工完成验证")
        page.screenshot(path='dlc/captcha_v2.png', full_page=True)
        print("验证码截图已保存到 dlc/captcha_v2.png")
        
        # 尝试等待更长时间看是否会自动消失或有其他变化
        print("等待 10 秒观察...")
        time.sleep(10)
        page.screenshot(path='dlc/after_wait_v2.png', full_page=True)
    
    # 检查是否登录成功（通过检查 URL 或页面元素）
    current_url = page.url
    print(f"\n当前 URL: {current_url}")
    
    # 检查是否有后台管理页面的特征元素
    is_logged_in = False
    
    # 检查是否有侧边栏菜单
    sidebar = page.locator('.sidebar, .el-menu, .menu-container, [class*="sidebar"]')
    if sidebar.is_visible():
        is_logged_in = True
        print("✓ 检测到侧边栏菜单，可能已登录")
    
    # 检查是否有用户信息
    user_info = page.locator('.user-info, .username, [class*="user"]')
    if user_info.is_visible():
        is_logged_in = True
        print("✓ 检测到用户信息，可能已登录")
    
    # 检查页面标题是否变化
    page_title = page.title()
    print(f"页面标题：{page_title}")
    
    if '登录' in page_title or 'login' in page_title.lower():
        print("⚠️  页面标题仍包含'登录'，可能未登录成功")
    else:
        is_logged_in = True
    
    if is_logged_in:
        print("\n=== 登录成功，开始搜索电饭锅 ===")
        
        # 查找搜索框
        search_input = page.locator('input[placeholder*="搜索"], input[placeholder*="商品"], .search-input, #searchInput')
        if search_input.is_visible():
            search_input.fill('电饭锅')
            print("已填写搜索词：电饭锅")
            
            # 尝试按回车搜索
            page.keyboard.press('Enter')
            page.wait_for_load_state('networkidle')
            time.sleep(3)
            
            page.screenshot(path='dlc/search_result_v2.png', full_page=True)
            print("搜索结果截图已保存")
            
            # 检查搜索结果
            page_text = page.inner_text('body')
            if '电饭锅' in page_text or '电饭煲' in page_text:
                print("\n✓ 找到电饭锅类商品！")
            else:
                print("\n未找到电饭锅类商品")
        else:
            print("未找到搜索框")
    else:
        print("\n❌ 登录失败，需要人工完成验证码")
        print("请查看截图文件：")
        print("  - dlc/login_page_v2.png (登录页面)")
        print("  - dlc/after_click_v2.png (点击登录后)")
        print("  - dlc/captcha_v2.png (验证码)")
    
    # 打印控制台日志
    if console_logs:
        print("\n=== 控制台日志 ===")
        for log in console_logs[-30:]:
            print(log)
    
    browser.close()
    print("\n完成！")
