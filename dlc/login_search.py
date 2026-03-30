from playwright.sync_api import sync_playwright
import time

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page()
    
    # 访问网站
    page.goto('https://www.baidu.com/')
    page.wait_for_load_state('networkidle')
    
    # 等待页面加载完成，截图查看登录页面
    page.screenshot(path='dlc/login_page.png', full_page=True)
    print("登录页面截图已保存")
    
    # 尝试查找登录表单元素
    content = page.content()
    
    # 查找用户名输入框
    try:
        username_input = page.locator('input[type="text"], input[name="username"], input[name="account"], input[placeholder*="账号"], input[placeholder*="用户名"]').first
        if username_input.is_visible():
            username_input.fill('zhaohuixin')
            print("已输入用户名")
    except Exception as e:
        print(f"查找用户名输入框失败: {e}")
    
    # 查找密码输入框
    try:
        password_input = page.locator('input[type="password"], input[name="password"]').first
        if password_input.is_visible():
            password_input.fill('zhaohuixin@321')
            print("已输入密码")
    except Exception as e:
        print(f"查找密码输入框失败: {e}")
    
    # 查找登录按钮
    try:
        login_button = page.locator('button[type="submit"], button:has-text("登录"), button:has-text("login"), input[type="submit"], .login-btn').first
        if login_button.is_visible():
            login_button.click()
            print("已点击登录按钮")
    except Exception as e:
        print(f"查找登录按钮失败: {e}")
    
    # 等待登录完成
    page.wait_for_load_state('networkidle')
    time.sleep(3)
    
    # 登录后的截图
    page.screenshot(path='dlc/after_login.png', full_page=True)
    print("登录后页面截图已保存")
    
    # 查找搜索框
    try:
        search_input = page.locator('input[type="search"], input[type="text"][placeholder*="搜索"], input[name="keyword"], input[placeholder*="商品"]').first
        if search_input.is_visible():
            search_input.fill('电饭锅')
            print("已输入搜索词：电饭锅")
            
            # 查找搜索按钮
            search_button = page.locator('button:has-text("搜索"), button[type="submit"], .search-btn, .search-button').first
            if search_button.is_visible():
                search_button.click()
                print("已点击搜索按钮")
    except Exception as e:
        print(f"查找搜索框失败: {e}")
    
    # 等待搜索结果
    page.wait_for_load_state('networkidle')
    time.sleep(3)
    
    # 搜索结果截图
    page.screenshot(path='dlc/search_result.png', full_page=True)
    print("搜索结果截图已保存")
    
    # 获取页面内容，检查是否有电饭锅商品
    result_content = page.content()
    
    # 检查是否有商品列表
    product_elements = page.locator('.product-item, .goods-item, [class*="product"], [class*="goods"], .item').all()
    print(f"找到的商品元素数量: {len(product_elements)}")
    
    # 检查页面是否包含电饭锅相关文字
    if '电饭锅' in result_content or '电饭煲' in result_content:
        print("✓ 平台上有电饭锅类商品！")
    else:
        print("✗ 未找到电饭锅类商品")
    
    # 打印页面标题
    print(f"页面标题: {page.title()}")
    
    browser.close()
