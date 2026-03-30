from playwright.sync_api import sync_playwright
import time

with sync_playwright() as p:
    browser = p.chromium.launch(headless=False)  # 使用非 headless 模式
    page = browser.new_page()
    
    # 访问网站
    page.goto('https://www.baidu.com/')
    page.wait_for_load_state('networkidle')
    
    print("=== 请登录 ===")
    print("浏览器已打开，请手动完成登录和搜索操作")
    print("1. 输入用户名：zhaohuixin")
    print("2. 输入密码：zhaohuixin@321")
    print("3. 完成验证码")
    print("4. 搜索：电饭锅")
    print("\n请在浏览器中操作，操作完成后按回车继续...")
    
    # 填写用户名
    try:
        page.locator('input[placeholder="用户名"]').fill('zhaohuixin')
        print("已自动输入用户名")
    except:
        print("请手动输入用户名")
    
    # 填写密码
    try:
        page.locator('input[placeholder="密码"]').fill('zhaohuixin@321')
        print("已自动输入密码")
    except:
        print("请手动输入密码")
    
    print("\n请手动完成验证码并点击登录，然后搜索'电饭锅'")
    
    # 等待用户操作
    input("操作完成后按回车继续...")
    
    # 截图
    page.screenshot(path='dlc/manual_result.png', full_page=True)
    print("已保存截图：dlc/manual_result.png")
    
    # 检查页面内容
    result_content = page.content()
    if '电饭锅' in result_content or '电饭煲' in result_content:
        print("✓ 平台上有电饭锅类商品！")
    else:
        print("✗ 未找到电饭锅类商品")
    
    browser.close()
