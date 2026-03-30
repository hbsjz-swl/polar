from playwright.sync_api import sync_playwright
import time

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page()
    
    # 访问网站
    page.goto('https://www.baidu.com/')
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
    
    # 等待验证码出现
    time.sleep(2)
    page.screenshot(path='dlc/captcha.png', full_page=True)
    
    # 检查是否有验证码
    captcha_text = page.locator('.verifybox-top').inner_text() if page.locator('.verifybox-top').count() > 0 else ""
    print(f"验证码提示: {captcha_text}")
    
    # 尝试查找验证码图片
    captcha_images = page.locator('.verifybox img, img[class*="verify"], img[src*="verify"]').all()
    print(f"找到验证码图片数量: {len(captcha_images)}")
    
    # 尝试点击确定按钮（可能不需要完成验证码）
    try:
        confirm_btn = page.locator('button.verify-btn.confirm-btn, div:has-text("确定")').first
        if confirm_btn.is_visible():
            print("尝试点击确定按钮...")
            # 先尝试不选择直接点击确定
            # confirm_btn.click()
            # time.sleep(2)
    except Exception as e:
        print(f"查找确定按钮失败: {e}")
    
    # 获取页面 HTML 来了解验证码结构
    captcha_html = page.locator('.verifybox').inner_html() if page.locator('.verifybox').count() > 0 else "无验证码框"
    print(f"\n验证码 HTML:\n{captcha_html[:2000]}")
    
    # 探索所有可点击的元素
    print("\n=== 所有可点击元素 ===")
    clickable = page.locator('button, div[class*="btn"], span[class*="btn"], a, [role="button"]').all()
    for i, el in enumerate(clickable):
        try:
            if el.is_visible():
                text = el.inner_text()[:50]
                cls = el.get_attribute('class')
                print(f"{i}: class={cls}, text='{text}'")
        except:
            pass
    
    browser.close()
