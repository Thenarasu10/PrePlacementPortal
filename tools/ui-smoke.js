/* PlaceIQ UI smoke test - real DOM, real server.
 *
 *   ./run.sh &                        # server on http://localhost:8080
 *   npm i jsdom                       # once
 *   node tools/ui-smoke.js
 *
 * Loads static/index.html in jsdom the way a browser does (script tags fetched and run as
 * classic scripts), bridges fetch to the running server, stubs canvas/audio, then walks the
 * student, mentor and admin flows: every route, the mock test, the interview, the resume
 * analyser, the mentor drawer and the admin import. Any uncaught error in the SPA fails the run.
 *
 * PORT/PLACEIQ_URL override the default http://localhost:8080. */
const { JSDOM, VirtualConsole } = require('jsdom');
const fs = require('fs');
const path = require('path');

const BASE = process.env.PLACEIQ_URL || 'http://localhost:' + (process.env.PORT || '8080');
const STATIC = path.join(__dirname, '..', 'static');
const errors = [];
const logs = [];

const vc = new VirtualConsole();
vc.on('jsdomError', e => errors.push('jsdomError: ' + e.message));
vc.on('error', (...a) => errors.push('console.error: ' + a.join(' ')));
vc.on('log', (...a) => logs.push(a.join(' ')));

const sleep = ms => new Promise(r => setTimeout(r, ms));

(async () => {
  const html = fs.readFileSync(path.join(STATIC, 'index.html'), 'utf8');
  const newPage = () => new JSDOM(html, {
    url: BASE + '/', runScripts: 'dangerously', resources: 'usable', pretendToBeVisual: true, virtualConsole: vc,
    beforeParse(window) { installStubs(window); }
  });
  /* Load the page exactly like a browser: script tags fetched from the running server and
     executed as classic scripts (so the const bindings in sound.js/motion.js/charts.js are
     visible to app.js, just like in a real browser). Stubs are installed before parsing. */
  let window;                       // rebound to a fresh page for each role
  const dom = newPage();
  window = dom.window;
  let dom2, dom3;
  function installStubs(window) {
  window.matchMedia = window.matchMedia || (q => ({ matches: false, media: q, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} }));
  window.AudioContext = class { constructor(){ this.state='running'; this.currentTime=0; this.destination={}; }
    createOscillator(){ return { frequency:{setValueAtTime(){}}, connect:o=>o, start(){}, stop(){}, type:'' }; }
    createGain(){ return { gain:{value:1,setValueAtTime(){},linearRampToValueAtTime(){},exponentialRampToValueAtTime(){}}, connect:o=>o }; }
    resume(){} };
  const ctx2d = new Proxy({}, { get: (t, k) => {
      if (k === 'canvas') return { width: 600, height: 230, style: {} };
      if (k === 'measureText') return () => ({ width: 20 });
      if (k === 'createLinearGradient') return () => ({ addColorStop() {} });
      return () => {};
  }, set: () => true });
  window.HTMLCanvasElement.prototype.getContext = () => ctx2d;
  window.HTMLElement.prototype.scrollIntoView = () => {};
  window.requestAnimationFrame = cb => setTimeout(() => { try { cb(window.performance.now()); } catch (e) { /* page closed mid-frame */ } }, 8);
  window.cancelAnimationFrame = id => clearTimeout(id);
  window.fetch = (url, opts) => fetch(url.startsWith('http') ? url : BASE + url, opts);   // real server
  }

  window.addEventListener('error', e => errors.push('window.onerror: ' + (e.error ? e.error.message : e.message)));
  window.addEventListener('unhandledrejection', e => errors.push('unhandled rejection: ' + ((e.reason && (e.reason.stack || e.reason.message)) || e.reason)));
  const waitBoot = async () => {
    const t0 = Date.now();
    while (Date.now() - t0 < 15000) {
      if (window.eval('typeof Sound') === 'object'
          && window.document.querySelectorAll('#demoLogins .demo-btn').length > 0) return true;
      await sleep(100);
    }
    return false;
  };
  const $ = sel => window.document.querySelector(sel);
  const text = () => (window.document.getElementById('view') || {}).textContent || '';
  const view = () => window.document.getElementById('view');
  const waitFor = async (predicate, ms = 6000) => {
    const t0 = Date.now();
    while (Date.now() - t0 < ms) {
      try { if (predicate()) return true; } catch (e) { /* keep polling */ }
      await sleep(120);
    }
    return false;
  };
  const check = (label, cond, extra = '') => {
    console.log((cond ? '  PASS  ' : '  FAIL  ') + label + (cond ? '' : '  ' + extra));
    if (!cond) errors.push('assert: ' + label + ' ' + extra);
  };

  await waitBoot();
  const bindings = window.eval('typeof Sound + "," + typeof M + "," + typeof Charts + "," + typeof api');
  check('page booted: global modules bound (Sound, M, Charts, api)', bindings === 'object,object,object,function', bindings);

  console.log('PlaceIQ headless smoke test\n===========================');
  check('login screen rendered', !!$('#loginForm'));
  check('demo login buttons from API', window.document.querySelectorAll('#demoLogins .demo-btn').length === 3,
    'found ' + window.document.querySelectorAll('#demoLogins .demo-btn').length);
  check('domain options populated from config', window.document.querySelectorAll('#registerForm select[name=domain] option').length >= 4);

  /* ---------- student flow ---------- */
  async function login(email, password) {
    const form = $('#loginForm');
    form.querySelector('[name=email]').value = email;
    form.querySelector('[name=password]').value = password;
    form.dispatchEvent(new window.Event('submit', { bubbles: true, cancelable: true }));
    await sleep(1400);
  }
  await login('student@placeiq.dev', 'student123');
  // onboarding appears for a first-time browser profile: walk all three steps
  if (!$('#onboarding').classList.contains('hide')) {
    check('onboarding step 1 shown with stepper', $('#obStep').textContent.includes('Welcome'));
    const s1 = $('#obStep').querySelector('#obNext'); s1.click(); await sleep(400);
    check('onboarding step 2 (target role/company)', $('#obStep').textContent.includes('aiming for'));
    const s2 = $('#obStep').querySelector('#obNext'); s2.click(); await sleep(400);
    check('onboarding step 3 (start diagnostic)', $('#obStep').textContent.includes('diagnostic'));
    $('#obStep').querySelector('#obSkip').click(); await sleep(1600);
  }
  check('app shell visible after login', !$('#app').classList.contains('hide'));
  check('bottom tab bar has 5 mobile tabs', window.document.querySelectorAll('#bottomNav button').length === 5);
  check('sidebar nav rendered', window.document.querySelectorAll('#nav .nav-item').length >= 8);

  /* dashboard */
  check('dashboard: next best action card', text().includes('Next best action'), text().slice(0, 80));
  check('dashboard: readiness ring present', !!view().querySelector('.ring .val'));
  check('dashboard: five section cards', view().querySelectorAll('#sectionCards .card').length === 5,
    'found ' + view().querySelectorAll('#sectionCards .card').length);
  check('dashboard: upcoming tasks listed', view().querySelectorAll('#upcoming li').length > 0);
  check('dashboard: charts drawn (line/radar/scatter)', !!$('#lineChart') && !!$('#radar') && !!$('#scatter'));
  check('dashboard: heatmap cells', view().querySelectorAll('#heat > div').length > 0);
  check('dashboard: attempt history rows', view().querySelectorAll('#attemptRows tr').length > 0);
  check('dashboard: text summary under chart', !!view().querySelector('.summary'));

  /* every student route renders without throwing */
  const routes = ['assessment', 'results', 'gaps', 'planner', 'mock', 'interview', 'resume', 'companies', 'profile'];
  for (const r of routes) {
    const btn = [...window.document.querySelectorAll('#nav .nav-item')].find(b => b.dataset.go === r);
    if (!btn) { check('nav item ' + r, false); continue; }
    btn.click();
    await sleep(r === 'resume' || r === 'companies' ? 900 : 800);
    const t = text();
    check('route renders: ' + r, t.length > 60 && !t.includes('Something went wrong'), t.slice(0, 100));
  }

  /* planner interaction: tick a task, expect it to strike through + reschedule render */
  [...window.document.querySelectorAll('#nav .nav-item')].find(b => b.dataset.go === 'planner').click();
  await sleep(900);
  const taskBox = view().querySelector('[data-task]');
  if (taskBox) {
    taskBox.checked = true;
    taskBox.dispatchEvent(new window.Event('change', { bubbles: true }));
    await sleep(1800);
    check('planner: weekly completion ring present', !!$('#weeklyRing') || view().querySelectorAll('.ring').length > 0);
  } else check('planner has tasks', false);

  /* mock test: start, answer one, submit */
  [...window.document.querySelectorAll('#nav .nav-item')].find(b => b.dataset.go === 'mock').click();
  await sleep(900);
  const startBtn = view().querySelector('[data-mock]');
  if (startBtn) {
    startBtn.click(); await sleep(1200);
    check('mock test: questions rendered', view().querySelectorAll('[data-qwrap]').length > 5,
      'found ' + view().querySelectorAll('[data-qwrap]').length);
    check('mock test: countdown shown', !!$('#mockTimer'));
    const firstOpt = view().querySelector('[data-qwrap] input');
    if (firstOpt) { firstOpt.checked = true; firstOpt.dispatchEvent(new window.Event('click', { bubbles: true })); }
    $('#submitMock').click(); await sleep(1600);
    check('mock test: result + review rendered', text().includes('Post-test review') || text().includes('cut-off'), text().slice(0, 80));
  } else check('mock patterns rendered', false);

  /* interview: answer and score */
  [...window.document.querySelectorAll('#nav .nav-item')].find(b => b.dataset.go === 'interview').click();
  await sleep(900);
  $('#startInterview').click(); await sleep(1400);
  const ivAnswer = $('#ivAnswer');
  if (ivAnswer) {
    ivAnswer.value = 'Situation: our demo moved up. Task: I owned the backend. Action: I split work into daily checkpoints and paired on the bug. Result: on-time delivery, top score of 86%.';
    $('#evaluate').click(); await sleep(1600);
    check('interview: rubric bars filled', view().querySelectorAll('.rubric-bar').length === 4,
      'found ' + view().querySelectorAll('.rubric-bar').length);
    $('#finishIv').click(); await sleep(1200);
    check('interview: overall score rendered', text().includes('XP earned'), text().slice(0, 80));
  } else check('interview question rendered', false);

  /* company detail + fit ring */
  [...window.document.querySelectorAll('#nav .nav-item')].find(b => b.dataset.go === 'companies').click();
  await sleep(900);
  const fitBtn = view().querySelector('[data-company]');
  if (fitBtn) {
    fitBtn.click(); await sleep(1200);
    check('company fit: ring + summary in modal', !!window.document.querySelector('.modal-card .ring') && !!window.document.querySelector('.modal-card .summary'));
    window.document.querySelector('.modal-card .close').click(); await sleep(300);
  } else check('company grid rendered', false);

  /* ---------- mentor (fresh page = real page reload) ---------- */
  dom.window.close();                       // free the previous page before "reloading"
  dom2 = newPage(); window = dom2.window;
  await waitBoot();
  await login('mentor@placeiq.dev', 'mentor123');
  await waitFor(() => view() && view().querySelectorAll('#mentorTable tbody tr').length > 0);
  check('mentor: student table rendered', view().querySelectorAll('#mentorTable tbody tr').length > 0);
  const sortTh = view().querySelector('#mentorTable th[data-sort=prs]');
  if (sortTh) {
    sortTh.click(); await sleep(600);
    check('mentor: sorting re-renders rows', view().querySelectorAll('#mentorTable tbody tr').length > 0);
    check('mentor: sort indicator moved to PRS column', /[\u25B2\u25BC]/.test(view().querySelector('#mentorTable').textContent));
  }
  const prsCol = [...view().querySelectorAll('#mentorTable thead th')].findIndex(th => th.dataset.sort === 'prs');
  const order = [...view().querySelectorAll('#mentorTable tbody tr')]
    .map(tr => Number((tr.cells[prsCol] || {}).textContent.replace(/[^0-9.]/g, '')));
  check('mentor: rows are actually ordered by PRS after sorting',
    order.length > 1 && order.every(n => Number.isFinite(n))
      && (order.every((v, i) => i === 0 || order[i - 1] <= v) || order.every((v, i) => i === 0 || order[i - 1] >= v)),
    'PRS column ' + prsCol + ' → ' + order.join(','));
  const openBtn = view().querySelector('[data-student]');
  if (openBtn) {
    openBtn.click();
    await waitFor(() => !!window.document.querySelector('.drawer #newNote'));
    check('mentor: detail drawer opened', !!window.document.querySelector('.drawer'));
    if (!$('#newNote')) {
      const dr = window.document.querySelector('.drawer');
      console.log('  (diagnostic) drawer present:', !!dr, '| drawer html:', dr ? dr.innerHTML.slice(0, 220).replace(/\s+/g, ' ') : 'n/a');
      console.log('  (diagnostic) errors so far:', JSON.stringify(errors.slice(-4)));
      console.log('  (diagnostic) view is:', (window.document.getElementById('viewTitle') || {}).textContent);
    }
    check('mentor: drawer has notes + assign controls', !!$('#newNote') && !!$('#assignTask'));
    window.document.querySelector('.drawer .close').click(); await sleep(300);
    check('mentor: drawer closed', !window.document.querySelector('.drawer'));
  } else check('mentor: open button', false);

  /* ---------- admin (fresh page) ---------- */
  dom2.window.close();
  dom3 = newPage(); window = dom3.window;
  await waitBoot();
  await login('admin@placeiq.dev', 'admin123');
  await waitFor(() => !!window.document.getElementById('kAdm1') && !!window.document.getElementById('adminSec'));
  await sleep(1200);   // let the KPI count-ups land
  check('admin: cohort KPIs rendered', !!$('#kAdm1') && !!$('#kAdm2'));
  check('admin: KPI numbers counted up to real values (never negative)',
    /^[0-9]+$/.test($('#kAdm1').textContent.trim()) && Number($('#kAdm1').textContent) > 0
      && !$('#kAdm2').textContent.includes('-'), 'size=' + $('#kAdm1').textContent + ' prs=' + $('#kAdm2').textContent);
  check('admin: section chart', !!$('#adminSec'));
  [...window.document.querySelectorAll('#nav .nav-item')].find(b => b.dataset.go === 'questions').click();
  await sleep(900);
  check('admin: question bank table', view().querySelectorAll('table tbody tr').length > 50,
    'rows ' + view().querySelectorAll('table tbody tr').length);
  [...window.document.querySelectorAll('#nav .nav-item')].find(b => b.dataset.go === 'import').click();
  await sleep(700);
  $('#qTemplate').click();
  check('admin: import template loaded', $('#impBody').value.includes('Q200'));
  $('#impBtn').click(); await sleep(2000);
  check('admin: import result reported', text().includes('imported'), text().slice(0, 80));

  /* no sound should ever have been enabled by default */
  check('sound stays off until opted in', window.localStorage.getItem('placeiq_sound') === null || window.localStorage.getItem('placeiq_sound') === 'off');
  check('no screen shows the error fallback', !window.document.body.textContent.includes('Something went wrong'));
  check('no raw undefined/null leaked into the UI', !/>\s*(undefined|NaN)\s*</.test(window.document.getElementById('view').innerHTML));

  console.log('\nuncaught errors: ' + errors.length);
  errors.slice(0, 12).forEach(e => console.log('  ! ' + e));
  process.exit(errors.length ? 1 : 0);
})().catch(e => { console.error('smoke failure:', e); process.exit(1); });
