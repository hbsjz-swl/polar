import { chromium } from 'playwright';

// 搜索 Google：spring ai community christian tzolov
const query = 'spring ai community christian tzolov';

(async () => {
  // 用有头模式启动（headless: false 显示浏览器窗口）
  const browser = await chromium.launch({
    headless: false,
    args: ['--start-maximized']
  });
  const context = await browser.newContext({ viewport: null });
  const page = await context.newPage();

  console.log('浏览器已启动，正在打开 Google...');
  await page.goto('https://www.google.com', { waitUntil: 'domcontentloaded', timeout: 60000 });

  // 尝试直接搜索
  console.log('正在搜索:', query);
  try {
    await page.goto(`https://www.google.com/search?q=${encodeURIComponent(query)}`, { waitUntil: 'domcontentloaded', timeout: 60000 });
  } catch (e) {
    console.log('直接跳转搜索失败，回退到首页搜索框:', e.message);
    await page.goto('https://www.google.com', { waitUntil: 'domcontentloaded', timeout: 60000 });
    const box = page.locator('textarea#APSZ, input#APSZ, input[name=q]');
    await box.fill(query);
    await box.press('Enter');
  }

  await page.waitForLoadState('domcontentloaded', { timeout: 60000 }).catch(() => {});

  // 给结果一点渲染时间
  await page.waitForTimeout(3000);

  // 抓取结果标题和链接
  const results = await page.evaluate(() => {
    const items = [];
    document.querySelectorAll('a[href]').forEach(a => {
      const text = (a.innerText || '').trim();
      const href = a.href;
      // 过滤：必须是外部链接，且有可见文本
      if (href && href.startsWith('http') && !href.includes('google') && !href.includes('gstatic') && text.length > 5 && text.length < 200) {
        const parent = a.closest('div[style], h3') || a;
        const h3 = a.closest('h3');
        const title = h3 ? h3.innerText : text;
        items.push({ title: title, url: href });
      }
    });
    // 去重
    const seen = new Set();
    return items.filter(i => {
      const key = i.url;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    }).slice(0, 15);
  });

  console.log('\n========== 搜索结果（前15条） ==========');
  results.forEach((r, i) => {
    console.log(`${i + 1}. ${r.title}\n   ${r.url}`);
  });

  // 保留浏览器 30 秒让用户查看，然后关闭
  console.log('\n浏览器将保持打开 30 秒，之后自动关闭。');
  await page.waitForTimeout(30000);
  await browser.close();
})();
