const { chromium } = require('playwright');
(async () => {
  const FPS = 30;
  const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 1080, height: 1920 } });
  await p.goto('file://' + __dirname + '/promo.html'); await p.evaluate(() => document.fonts.ready); await p.waitForTimeout(1000);
  const dur = await p.evaluate(() => window.DUR); const n = Math.round(dur * FPS);
  for (let i = 0; i < n; i++) {
    await p.evaluate(t => render(t), i / FPS);
    await p.screenshot({ path: `frames/${String(i).padStart(5, '0')}.jpg`, type: 'jpeg', quality: 93 });
  }
  await b.close(); console.log('frames', n);
})();
