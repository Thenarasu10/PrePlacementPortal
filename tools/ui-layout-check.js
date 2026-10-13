/* PlaceIQ layout check - real headless Chrome, three viewports.
 *
 *   ./run.sh &                              # server on http://localhost:8080
 *   npm i puppeteer                         # downloads its own Chrome
 *   node tools/ui-layout-check.js
 *
 * jsdom (tools/ui-smoke.js) checks behaviour but has no layout engine, so it cannot catch
 * the bugs this file is for: a grid track forced wider than the screen by a <canvas> or a
 * table, a sidebar that overlaps the content, a top bar that overflows on a phone.
 *
 * It asserts, at desktop / tablet / mobile widths:
 *   - nothing is wider than the viewport (no horizontal scroll)
 *   - the shell lays out as designed (240px sidebar, 76px tablet rail, hidden on mobile)
 *   - the sidebar never overlaps the content column
 *   - nav labels are visible on desktop and collapsed on the rail
 *   - the mobile bottom tab bar appears only on phones
 *   - charts never exceed their card
 *
 * Exits non-zero on the first failing viewport, so it can run in CI.
 */
const puppeteer = require('puppeteer');

const BASE = process.env.PLACEIQ_URL || 'http://localhost:' + (process.env.PORT || '8080');
const sleep = ms => new Promise(r => setTimeout(r, ms));

const VIEWPORTS = [
  { label: 'desktop 1440x900', width: 1440, height: 900, sidebar: 'full', bottomNav: false },
  { label: 'laptop  1280x800', width: 1280, height: 800, sidebar: 'full', bottomNav: false },
  { label: 'tablet  1024x800', width: 1024, height: 800, sidebar: 'rail', bottomNav: false },
  { label: 'mobile   390x844', width: 390, height: 844, sidebar: 'hidden', bottomNav: true }
];

let pass = 0, fail = 0;
const check = (label, ok, detail = '') => {
  console.log((ok ? '  PASS  ' : '  FAIL  ') + label + (ok ? '' : '  ' + detail));
  ok ? pass++ : fail++;
};

async function signIn(page) {
  await page.goto(BASE + '/', { waitUntil: 'networkidle2' });
  await page.evaluate(() => localStorage.clear());
  await page.reload({ waitUntil: 'networkidle2' });
  await page.waitForSelector('#loginForm');
  await page.$eval('#loginForm [name=email]', el => el.value = 'student@placeiq.dev');
  await page.$eval('#loginForm [name=password]', el => el.value = 'student123');
  await page.evaluate(() => document.getElementById('loginForm').requestSubmit());
  await page.waitForFunction(() => {
    const app = !document.getElementById('app').classList.contains('hide');
    const ob = !document.getElementById('onboarding').classList.contains('hide');
    return app || ob;
  }, { timeout: 25000 });
  await sleep(1500);
  // walk past onboarding if this is a first run
  await page.evaluate(() => {
    if (!document.getElementById('onboarding').classList.contains('hide')) {
      for (let i = 0; i < 2; i++) { const n = document.getElementById('obNext'); if (n) n.click(); }
    }
  });
  await sleep(900);
  await page.evaluate(() => { const s = document.getElementById('obSkip'); if (s) s.click(); });
  await sleep(2500);
}

(async () => {
  const browser = await puppeteer.launch({
    headless: 'new',
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage']
  });

  console.log('PlaceIQ layout check against ' + BASE + '\n=========================================');

  for (const vp of VIEWPORTS) {
    console.log('\n-- ' + vp.label);
    const page = await browser.newPage();
    await page.setViewport({ width: vp.width, height: vp.height });
    const pageErrors = [];
    page.on('pageerror', e => pageErrors.push(e.message));
    await signIn(page);

    const m = await page.evaluate(() => {
      const vw = document.documentElement.clientWidth;
      const overflowing = [];
      document.querySelectorAll('body *').forEach(el => {
        const r = el.getBoundingClientRect();
        if (r.width > 0 && r.right > vw + 1) {
          overflowing.push(el.tagName.toLowerCase() + (el.id ? '#' + el.id : ''));
        }
      });
      const sb = document.querySelector('.sidebar');
      const main = document.querySelector('.main');
      const sbStyle = getComputedStyle(sb);
      const label = document.querySelector('.nav-item .nav-label');
      const charts = [...document.querySelectorAll('canvas')].map(c => Math.round(c.getBoundingClientRect().right));
      return {
        viewport: vw,
        scrollWidth: document.documentElement.scrollWidth,
        bodyScrollWidth: document.body.scrollWidth,
        overflowing: overflowing.slice(0, 5),
        overflowingCount: overflowing.length,
        sidebarDisplay: sbStyle.display,
        sidebarWidth: Math.round(sb.getBoundingClientRect().width),
        sidebarRight: Math.round(sb.getBoundingClientRect().right),
        mainLeft: Math.round(main.getBoundingClientRect().left),
        navLabelVisible: label ? getComputedStyle(label).display !== 'none' : false,
        bottomNavVisible: getComputedStyle(document.getElementById('bottomNav')).display !== 'none',
        chartsWithinViewport: charts.every(r => r <= vw + 1),
        maxChartRight: charts.length ? Math.max(...charts) : 0
      };
    });

    check(vp.label + ': no horizontal overflow', m.overflowingCount === 0 && m.scrollWidth <= m.viewport + 1,
      m.overflowingCount + ' elements past the edge (' + m.overflowing.join(', ') + '), scrollWidth ' + m.scrollWidth + ' vs viewport ' + m.viewport);
    check(vp.label + ': sidebar never overlaps the content column', m.mainLeft >= m.sidebarRight - 1,
      'main starts at ' + m.mainLeft + ', sidebar ends at ' + m.sidebarRight);
    if (vp.sidebar === 'full') {
      check(vp.label + ': 240px sidebar with visible labels', m.sidebarWidth === 240 && m.navLabelVisible,
        'width ' + m.sidebarWidth + ', labels ' + m.navLabelVisible);
    } else if (vp.sidebar === 'rail') {
      check(vp.label + ': 76px icon rail with labels hidden', m.sidebarWidth === 76 && !m.navLabelVisible,
        'width ' + m.sidebarWidth + ', labels ' + m.navLabelVisible);
    } else {
      check(vp.label + ': sidebar hidden behind the menu button', m.sidebarDisplay === 'none', 'display ' + m.sidebarDisplay);
    }
    check(vp.label + ': bottom tab bar visibility is correct', m.bottomNavVisible === vp.bottomNav,
      'visible ' + m.bottomNavVisible + ', expected ' + vp.bottomNav);
    check(vp.label + ': charts stay inside the viewport', m.chartsWithinViewport, 'rightmost chart edge ' + m.maxChartRight);
    check(vp.label + ': no page errors', pageErrors.length === 0, pageErrors.slice(0, 2).join(' | '));

    await page.close();
  }

  await browser.close();
  console.log('\n==================\npassed: ' + pass + '   failed: ' + fail);
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('layout check crashed:', e.message); process.exit(1); });
