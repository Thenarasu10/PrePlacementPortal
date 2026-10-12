/* PlaceIQ single-page app: auth, student flow, tools, mentor and admin views. Vanilla JS. */
const API = '/api';
const state = {
  user: null, token: localStorage.getItem('placeiq_token'), meta: null,
  view: 'dashboard', attempt: null, timer: null, violations: 0, mock: null, interview: null
};

/* ---------------- api helper ---------------- */
async function api(path, { method = 'GET', body, raw } = {}) {
  const res = await fetch(API + path, {
    method,
    headers: Object.assign({ 'Content-Type': 'application/json' }, state.token ? { Authorization: 'Bearer ' + state.token } : {}),
    body: body ? JSON.stringify(body) : undefined
  });
  if (raw) return res.text();
  const text = await res.text();
  let data = {};
  try { data = text ? JSON.parse(text) : {}; } catch (e) { data = { error: text }; }
  if (!res.ok) throw new Error(data.error || ('Request failed (' + res.status + ')'));
  return data;
}
const el = (id) => document.getElementById(id);
const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const pct = (v) => Math.round(Number(v) || 0) + '%';
function toast(msg) {
  const t = el('toast');
  t.textContent = msg;
  t.classList.remove('hide');
  M.announce(msg);                       // visual + screen-reader equivalent of the sound
  setTimeout(() => t.classList.add('hide'), 3200);
}
function levelTag(level) { return `<span class="tag ${String(level || '').toLowerCase()}">${esc(level || '-')}</span>`; }

/* ---------------- auth ---------------- */
async function boot() {
  const savedTheme = localStorage.getItem('placeiq_theme');
  if (savedTheme) {
    document.documentElement.dataset.theme = savedTheme;
    el('themeBtn').textContent = savedTheme === 'dark' ? 'Light theme' : 'Dark theme';
  }
  state.meta = await api('/meta/config').catch(() => null);
  renderDemoLogins();
  document.querySelectorAll('select[name=domain]').forEach(s => {
    s.innerHTML = (state.meta?.domains || ['CS/IT']).map(d => `<option>${esc(d)}</option>`).join('');
  });
  if (state.token) {
    try { state.user = await api('/me'); startApp(); return; } catch (e) { state.token = null; localStorage.removeItem('placeiq_token'); }
  }
  el('auth').classList.remove('hide');
}
const ROLE_INFO = {
  student: { label: 'Student', note: 'Student view: onboarding, adaptive assessment, skill gaps, a weekly planner, ' +
      'mock tests, mock interview, resume ATS scan and the free learning library.' },
  mentor: { label: 'Mentor', note: 'Mentor view: your student roster with at-risk flags, sortable progress table, ' +
      'student drawers with notes and task assignment, plus the question bank and the learning library.' },
  admin: { label: 'Admin', note: 'Admin view: cohort analytics and KPIs, readiness distribution, the question bank, ' +
      'validated CSV/JSON import, CSV export and the sign-in security panel.' }
};
let pickedRole = 'student';

function pickRole(role) {
  pickedRole = role;
  document.querySelectorAll('[data-role-pick]').forEach(b => {
    const on = b.dataset.rolePick === role;
    b.classList.toggle('active', on);
    b.setAttribute('aria-selected', on ? 'true' : 'false');
  });
  const note = el('roleNote');
  if (note) note.textContent = ROLE_INFO[role].note;
  const demo = (state.meta?.demoLogins || []).find(l => l.role === role);
  if (demo) {                       // prefill the matching demo account, never auto-submit
    const f = el('loginForm');
    f.querySelector('[name=email]').value = demo.email;
    f.querySelector('[name=password]').value = demo.password;
  }
  if (el('loginError')) el('loginError').textContent = '';
}

function renderDemoLogins() {
  const box = el('demoLogins');
  const logins = state.meta?.demoLogins || [];
  box.innerHTML = logins.map(l => `<button class="demo-btn" data-email="${esc(l.email)}" data-pass="${esc(l.password)}" data-role="${esc(l.role)}">
      <b>${esc(l.role)}</b><span class="muted small">${esc(l.email)}</span></button>`).join('');
  box.querySelectorAll('.demo-btn').forEach(b => b.onclick = () => {
    pickRole(b.dataset.role);
    el('loginForm').querySelector('[name=password]').value = b.dataset.pass;
    el('loginForm').requestSubmit();
  });
  document.querySelectorAll('[data-role-pick]').forEach(b => b.onclick = () => pickRole(b.dataset.rolePick));
  pickRole(pickedRole);
}

/** Local strength hint only - the server enforces the real policy. */
function paintStrength(pw) {
  const met = document.getElementById('pwMeter'), label = document.getElementById('pwStrength');
  if (!met || !label) return;
  let score = 0;
  if (pw.length >= 8) score++;
  if (/[a-z]/.test(pw) && /[A-Z]/.test(pw)) score++;
  if (/[0-9]/.test(pw)) score++;
  if (/[^A-Za-z0-9]/.test(pw) && pw.length >= 12) score++;
  met.className = 'pw-meter' + (pw ? ' s' + score : '');
  label.textContent = 'Strength: ' + (pw ? ['too weak', 'weak', 'fair', 'good', 'strong'][score] : '-')
    + ' · the server requires 8+ characters with upper, lower and a digit';
}
const pwInput = el('registerForm').querySelector('[name=password]');
if (pwInput) pwInput.oninput = e => paintStrength(e.target.value);
document.querySelectorAll('.tab').forEach(t => t.onclick = () => {
  document.querySelectorAll('.tab').forEach(x => x.classList.remove('active'));
  t.classList.add('active');
  el('loginForm').classList.toggle('hide', t.dataset.auth !== 'login');
  el('registerForm').classList.toggle('hide', t.dataset.auth !== 'register');
});
el('loginForm').onsubmit = async (e) => {
  e.preventDefault();
  const f = e.target;
  try {
    const out = await api('/auth/login', { method: 'POST', body: {
      email: f.querySelector('[name=email]').value, password: f.querySelector('[name=password]').value } });
    state.token = out.token; state.user = out.user; localStorage.setItem('placeiq_token', out.token);
    startApp();
  } catch (err) {
    el('loginError').textContent = err.message;
    el('loginError').classList.add('shake-once');
    setTimeout(() => el('loginError').classList.remove('shake-once'), 500);
    Sound.play('incorrect');
  }
};
el('registerForm').onsubmit = async (e) => {
  e.preventDefault();
  const f = e.target;
  try {
    const out = await api('/auth/register', { method: 'POST', body: Object.fromEntries(new FormData(f)) });
    state.token = out.token; state.user = out.user; localStorage.setItem('placeiq_token', out.token);
    startApp();
  } catch (err) { el('registerError').textContent = err.message; }
};
el('logoutBtn').onclick = () => { localStorage.removeItem('placeiq_token'); location.reload(); };
el('themeBtn').onclick = () => {
  const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
  document.documentElement.dataset.theme = next;
  el('themeBtn').textContent = next === 'dark' ? 'Light theme' : 'Dark theme';
  localStorage.setItem('placeiq_theme', next);
  if (['dashboard', 'results', 'mentor', 'admin'].includes(state.view)) go(state.view);   // redraw charts in new palette
};
el('menuBtn').onclick = () => document.querySelector('.sidebar').classList.toggle('open');
el('bellBtn').onclick = () => el('notifPanel').classList.toggle('hide');

/* ---------------- navigation ---------------- */
/* ---------------- onboarding: 3 steps with a stepper ---------------- */
function startOnboarding() {
  el('app').classList.add('hide');
  el('onboarding').classList.remove('hide');
  const draft = Object.assign({}, state.user);
  let step = 1, dir = 1;
  const dot = (n) => el('stepper').querySelector(`.dot[data-step="${n}"]`);

  function paintStepper() {
    [1, 2, 3].forEach(n => {
      const d = dot(n);
      d.classList.toggle('done', n < step);
      d.classList.toggle('current', n === step);
      d.textContent = n < step ? '✓' : n;
    });
    el('stepper').querySelectorAll('.line').forEach((l, i) => { l.querySelector('i').style.width = (step > i + 1 ? 100 : 0) + '%'; });
  }

  function render() {
    paintStepper();
    const companies = (state.meta?.companies || []).map(c => `<option ${c.id === (draft.targetCompany || '').toLowerCase() ? 'selected' : ''}>${esc(c.name)}</option>`).join('');
    if (step === 1) el('obStep').innerHTML = `
      <div class="slide-step ${dir < 0 ? 'back' : ''}">
        <h2>Welcome to PlaceIQ 👋</h2>
        <p class="muted">Let's set up your profile. It takes 30 seconds and shapes everything you see next.</p>
        <div class="grid g2 mt">
          <label>Full name<input id="obName" value="${esc(draft.name || '')}" required></label>
          <label>College<input id="obCollege" value="${esc(draft.college || '')}" placeholder="College name"></label>
          <label>Branch<input id="obBranch" value="${esc(draft.branch || '')}" placeholder="CSE / ECE / MECH"></label>
          <label>Graduation year<input id="obGrad" type="number" value="${draft.graduationYear || 2026}"></label>
          <label>Domain<select id="obDomain">${(state.meta?.domains || ['CS/IT']).map(d => `<option ${d === draft.domain ? 'selected' : ''}>${esc(d)}</option>`).join('')}</select></label>
          <label>Current CGPA<input id="obCgpa" type="number" step="0.1" value="${draft.cgpa || 7.5}"></label>
        </div>
        <div class="card tight mt" style="background:var(--surface-2)">
          <div class="between">
            <div><b>Enable sounds</b><p class="small muted">Soft, optional chimes for feedback. Off by default - change it any time in Profile.</p></div>
            <div class="row"><button class="btn small" id="obPreview" type="button">Preview</button>
              <button class="btn small ${Sound.isEnabled() ? 'primary' : ''}" id="obSound" type="button">${Sound.isEnabled() ? 'Sounds on' : 'Sounds off'}</button></div>
          </div>
        </div>
        <div class="row mt"><button class="btn primary" id="obNext">Continue</button><span class="small muted">Step 1 of 3</span></div>
      </div>`;
    if (step === 2) el('obStep').innerHTML = `
      <div class="slide-step ${dir < 0 ? 'back' : ''}">
        <h2>What are you aiming for?</h2>
        <p class="muted">Your target company and role drive the planner, the mock tests and the company fit analysis.</p>
        <div class="grid g2 mt">
          <label>Target role<input id="obRole" value="${esc(draft.targetRole || 'Software Engineer')}" list="roleList">
            <datalist id="roleList"><option>Software Engineer</option><option>Data Analyst</option><option>VLSI Engineer</option><option>Design Engineer</option><option>Business Analyst</option></datalist></label>
          <label>Target company<select id="obCompany">${companies || '<option>TCS</option>'}</select></label>
        </div>
        <p class="small muted mt">Not sure yet? Pick a likely one - you can change it any time.</p>
        <div class="row mt"><button class="btn ghost" id="obBack">Back</button><button class="btn primary" id="obNext">Continue</button></div>
      </div>`;
    if (step === 3) el('obStep').innerHTML = `
      <div class="slide-step ${dir < 0 ? 'back' : ''}">
        <h2>Ready for your diagnostic?</h2>
        <p class="muted">20 adaptive questions across five sections, about 30 minutes. It sets your baseline, ranks your skill gaps and builds your first weekly plan.</p>
        <div class="grid g3 mt">${(state.meta?.sections || []).map(x => `<div class="card tight"><b>${esc(x.name)}</b><div class="small muted">weight ${Math.round(x.weight * 100)}%</div></div>`).join('')}</div>
        <div class="row mt"><button class="btn ghost" id="obBack">Back</button>
          <button class="btn primary" id="obStart">Start diagnostic test</button>
          <button class="btn ghost" id="obSkip">Skip for now</button></div>
      </div>`;

    const next = el('obNext');
    if (next) next.onclick = () => {
      if (step === 1) {
        draft.name = el('obName').value.trim() || draft.name;
        draft.college = el('obCollege').value; draft.branch = el('obBranch').value;
        draft.graduationYear = Number(el('obGrad').value || 2026); draft.domain = el('obDomain').value;
        draft.cgpa = Number(el('obCgpa').value || 0);
      } else if (step === 2) { draft.targetRole = el('obRole').value; draft.targetCompany = el('obCompany').value; }
      Sound.play('stepComplete'); M.announce('Step ' + step + ' complete');
      dir = 1; step = Math.min(3, step + 1); render();
    };
    const back = el('obBack');
    if (back) back.onclick = () => { dir = -1; step = Math.max(1, step - 1); render(); };
    const soundBtn = el('obSound');
    if (soundBtn) soundBtn.onclick = () => { Sound.setEnabled(!Sound.isEnabled()); syncSoundUi(); Sound.play('stepComplete'); render(); };
    const prev = el('obPreview');
    if (prev) prev.onclick = () => { Sound.preview('correct'); M.announce('Sound preview played'); };
    const start = el('obStart');
    if (start) start.onclick = async () => { await finishOnboarding(draft); startAssessment('diagnostic'); };
    const skip = el('obSkip');
    if (skip) skip.onclick = () => finishOnboarding(draft);
  }
  render();

  async function finishOnboarding(draft) {
    localStorage.setItem('placeiq_onboarded_' + state.user.id, '1');
    try { state.user = await api('/me', { method: 'PUT', body: draft }); } catch (e) { /* offline-safe */ }
    el('onboarding').classList.add('hide');
    startApp();
  }
}

/* ---------------- profile & settings (sound, volume, theme) ---------------- */
async function renderProfile() {
  const me = state.user;
  el('view').innerHTML = `
    <div class="grid g2">
      <div class="card">
        <h2>Your profile</h2>
        <p class="muted small">Mentors see this alongside your readiness score.</p>
        <div class="grid g2 mt">
          <label>Full name<input id="pfName" value="${esc(me.name || '')}"></label>
          <label>Email<input value="${esc(me.email || '')}" disabled></label>
          <label>College<input id="pfCollege" value="${esc(me.college || '')}"></label>
          <label>Branch<input id="pfBranch" value="${esc(me.branch || '')}"></label>
          <label>Domain<select id="pfDomain">${(state.meta?.domains || ['CS/IT']).map(d => `<option ${d === me.domain ? 'selected' : ''}>${esc(d)}</option>`).join('')}</select></label>
          <label>Target role<input id="pfRole" value="${esc(me.targetRole || '')}"></label>
          <label>Target company<input id="pfCompany" value="${esc(me.targetCompany || '')}"></label>
          <label>CGPA<input id="pfCgpa" type="number" step="0.1" value="${me.cgpa || 0}"></label>
        </div>
        <button class="btn primary mt" id="pfSave">Save profile</button>
      </div>
      <div class="card">
        <h2>Sign-in security</h2>
        <p class="muted small">Your account uses a session token that expires on its own. Revoke everything
          here if you signed in on a shared or lab computer.</p>
        <div class="sec-row"><span class="muted small">Active sessions</span><b id="secSessions">-</b></div>
        <div class="sec-row"><span class="muted small">Session lifetime</span><b>${me.sessionHours || 24} hours</b></div>
        <div class="sec-row"><span class="muted small">Password storage</span><b>salted PBKDF2-SHA256</b></div>
        <div class="sec-row"><span class="muted small">Failed-attempt lockout</span><b>5 tries, then 15 min</b></div>
        <button class="btn mt" id="signOutAll">Sign out of all devices</button>
        <div class="card tight mt" style="background:var(--surface-2)">
          <b class="small">Change password</b>
          <label class="mt">Current password<input id="pwOld" type="password" autocomplete="current-password"></label>
          <label class="mt">New password<input id="pwNew" type="password" autocomplete="new-password"
            placeholder="8+ chars, upper + lower + digit"></label>
          <div class="mt"><button class="btn primary" id="pwChange">Update password</button></div>
          <p class="tiny muted mt">Changing your password signs out every other device.</p>
        </div>
      </div>
      <div class="card">
        <h2>Sound</h2>
        <p class="muted small">Sounds are optional and off until you enable them. Nothing plays automatically, and during timed tests only the timer warnings and the submit confirmation are allowed - never right/wrong feedback.</p>
        <div class="between mt"><b>Enable sounds</b>
          <button class="btn ${Sound.isEnabled() ? 'primary' : ''}" id="soundToggle">${Sound.isEnabled() ? 'On' : 'Off'}</button></div>
        <label class="mt">Volume <span class="muted small" id="volOut">${Math.round(Sound.getVolume() * 100)}%</span>
          <input id="volSlider" type="range" min="0" max="100" value="${Math.round(Sound.getVolume() * 100)}"></label>
        <div class="card tight mt" style="background:var(--surface-2)">
          <b class="small">Preview each sound</b>
          <div class="row mt">${Sound.EVENTS.map(e => `<button class="btn small" data-preview="${e}">${e}</button>`).join('')}</div>
        </div>
        <p class="small muted mt">Every sound has a visible equivalent on screen, so nothing is conveyed by sound alone.</p>
      </div>
      <div class="card">
        <h2>Appearance</h2>
        <div class="between mt"><b>Theme</b><button class="btn" id="pfTheme">${document.documentElement.dataset.theme === 'dark' ? 'Dark' : 'Light'}</button></div>
        <div class="between mt"><b>Reduced motion</b><span class="tag">${M.reduced() ? 'Requested by your system' : 'Not requested'}</span></div>
      </div>
      <div class="card">
        <h2>Account</h2>
        <p class="muted small">Signed in as ${esc(me.email || '')} · role ${esc(me.role || '')}</p>
        <div class="row mt"><button class="btn danger" id="pfLogout">Sign out</button></div>
      </div>
    </div>`;
  el('pfSave').onclick = async () => {
    const body = {
      name: el('pfName').value, college: el('pfCollege').value, branch: el('pfBranch').value,
      domain: el('pfDomain').value, targetRole: el('pfRole').value, targetCompany: el('pfCompany').value,
      cgpa: Number(el('pfCgpa').value || 0), graduationYear: me.graduationYear
    };
    state.user = await api('/me', { method: 'PUT', body });
    Sound.play('taskCheck');
    toast('Profile saved');
    el('targetChip').textContent = state.user.targetCompany ? 'Target: ' + state.user.targetCompany : 'No target company yet';
  };
  el('soundToggle').onclick = () => { Sound.setEnabled(!Sound.isEnabled()); syncSoundUi(); if (Sound.isEnabled()) Sound.play('stepComplete'); renderProfile(); };
  el('volSlider').oninput = (e) => { Sound.setVolume(e.target.value / 100); el('volOut').textContent = e.target.value + '%'; };
  el('volSlider').onchange = () => Sound.preview('tap');
  el('signOutAll').onclick = async () => {
    const out = await api('/auth/logout-all', { method: 'POST' });
    localStorage.removeItem('placeiq_token');
    toast('Signed out of ' + out.signedOut + ' session' + (out.signedOut === 1 ? '' : 's'));
    setTimeout(() => location.reload(), 900);
  };
  el('pwChange').onclick = async () => {
    try {
      const out = await api('/auth/change-password', { method: 'POST', body: { current: el('pwOld').value, next: el('pwNew').value } });
      if (out && out.token) { state.token = out.token; localStorage.setItem('placeiq_token', out.token); }
      Sound.play('stepComplete');
      toast('Password updated - other devices were signed out');
      el('pwOld').value = ''; el('pwNew').value = '';
    } catch (e) { M.shake(el('pwNew')); toast(e.message); }
  };
  api('/auth/session').then(sess => {
    const b = el('secSessions');
    if (b) b.textContent = sess.activeSessions + (sess.activeSessions === 1 ? ' (this one)' : ' (including this one)');
  }).catch(() => { });

  el('view').querySelectorAll('[data-preview]').forEach(b => b.onclick = () => Sound.preview(b.dataset.preview));
  el('pfTheme').onclick = () => el('themeBtn').click();
  el('pfLogout').onclick = () => el('logoutBtn').click();
}

const NAV = {
  student: [['dashboard', '🏠', 'Dashboard'], ['assessment', '📝', 'Assessment'], ['mock', '⏱️', 'Tests'], ['learn', '📚', 'Learn'],
    ['gaps', '🎯', 'Skill gaps'], ['planner', '📅', 'Plan'], ['companies', '🏢', 'Companies'], ['results', '📈', 'Progress'],
    ['interview', '🎤', 'Interview'], ['resume', '📄', 'Resume'], ['profile', '👤', 'Profile']],
  mentor: [['mentor', '👥', 'My students'], ['questions', '🏦', 'Question bank'], ['learn', '📚', 'Learning library'],
    ['companies', '🏢', 'Companies'], ['profile', '👤', 'Profile']],
  admin: [['admin', '📊', 'Cohort analytics'], ['mentor', '👥', 'Student monitor'], ['questions', '🏦', 'Question bank'],
    ['import', '⬆️', 'Data import'], ['companies', '🏢', 'Companies'], ['profile', '👤', 'Profile']]
};
/* Mobile: bottom tab bar keeps the five student essentials (spec) */
const BOTTOM_TABS = ['dashboard', 'mock', 'planner', 'companies', 'profile'];
const TITLES = { dashboard: 'Dashboard', assessment: 'Assessment', results: 'Progress & history', learn: 'Learn - courses & free resources', gaps: 'Skill gaps',
  planner: 'Preparation planner', mock: 'Timed mock tests', interview: 'Mock interview', resume: 'Resume analyzer',
  companies: 'Company explorer', mentor: 'Student monitor', admin: 'Cohort analytics', questions: 'Question bank',
  import: 'Data import', profile: 'Profile & settings' };
const ROLE_BADGE = { student: '🎓 Student', mentor: '🧑‍🏫 Mentor', admin: '🛡️ Admin' };

function startApp() {
  el('auth').classList.add('hide');
  el('onboarding').classList.add('hide');
  el('app').classList.remove('hide');
  const role = state.user.role;
  const items = NAV[role] || NAV.student;
  el('nav').innerHTML = items.map(([id, ico, label]) =>
    `<button class="nav-item" data-go="${id}" title="${esc(label)}"><span class="nav-ico" aria-hidden="true">${ico}</span><span class="nav-label">${esc(label)}</span></button>`).join('');
  el('nav').querySelectorAll('[data-go]').forEach(b => b.onclick = () => go(b.dataset.go));
  el('bottomNav').innerHTML = items.filter(([id]) => BOTTOM_TABS.includes(id)).map(([id, ico, label]) =>
    `<button data-go="${id}"><span class="ico" aria-hidden="true">${ico}</span><span>${esc(label.split(' ')[0])}</span></button>`).join('');
  el('bottomNav').querySelectorAll('[data-go]').forEach(b => b.onclick = () => go(b.dataset.go));
  el('whoami').innerHTML = `<b>${esc(state.user.name)}</b>${esc(state.user.role)} · ${esc(state.user.college || 'no college set')}`;
  const chip = el('roleChip');
  if (chip) { chip.textContent = ROLE_BADGE[role] || role; chip.className = 'role-chip ' + role; }
  el('targetChip').classList.remove('hide');
  el('targetChip').textContent = state.user.targetCompany ? 'Target: ' + state.user.targetCompany : 'No target company yet';
  syncSoundUi();
  const start = role === 'student' ? 'dashboard' : role === 'mentor' ? 'mentor' : 'admin';
  if (role === 'student' && !localStorage.getItem('placeiq_onboarded_' + state.user.id)) return startOnboarding();
  go(start);
  loadNotifications();
}
/* Sidebar collapse (desktop) and the mobile menu button */
function syncSoundUi() {
  const b = el('soundQuick');
  if (b) b.textContent = 'Sound: ' + (Sound.isEnabled() ? 'on' : 'off');
}
el('collapseBtn').onclick = () => {
  el('sidebar').classList.toggle('collapsed');
  el('collapseBtn').title = el('sidebar').classList.contains('collapsed') ? 'Expand sidebar' : 'Collapse sidebar';
};
el('menuBtn').onclick = () => el('sidebar').classList.toggle('mobile-open');
el('profileBtn').onclick = () => go('profile');
el('soundQuick').onclick = () => {
  Sound.setEnabled(!Sound.isEnabled());
  syncSoundUi();
  if (Sound.isEnabled()) Sound.play('stepComplete');
  toast('Sounds ' + (Sound.isEnabled() ? 'enabled' : 'muted'));
};
el('globalSearch').addEventListener('keydown', (e) => {
  if (e.key !== 'Enter') return;
  const q = e.target.value.trim();
  if (!q) return;
  state.searchQuery = q;
  go(state.user.role === 'student' ? 'companies' : 'questions');
});
async function go(view) {
  state.view = view;
  clearInterval(state.timer);
  document.querySelectorAll('.nav-item').forEach(b => b.classList.toggle('active', b.dataset.go === view));
  el('viewTitle').textContent = TITLES[view] || 'PlaceIQ';
  M.skeleton(el('view'), view === 'dashboard' ? 6 : 4);      // skeleton shimmer, never a spinner, for page loads
  document.querySelectorAll('.nav-item,.bottom-nav button').forEach(b => b.classList.toggle('active', b.dataset.go === view));
  const view_ = el('view');
  view_.style.animation = 'none';
  requestAnimationFrame(() => { view_.style.animation = 'fade-up var(--dur-page) var(--ease-out) both'; });
  // every render is awaited so a failed API call lands in this catch and shows the error state
  // instead of silently leaving the skeleton on screen
  try {
    if (view === 'dashboard') return await renderDashboard();
    if (view === 'assessment') return await renderAssessmentHome();
    if (view === 'results') return await renderResults();
    if (view === 'learn') return await renderLearn();
    if (view === 'gaps') return await renderGaps();
    if (view === 'planner') return await renderPlanner();
    if (view === 'mock') return await renderMockHome();
    if (view === 'interview') return await renderInterviewHome();
    if (view === 'resume') return await renderResume();
    if (view === 'companies') return await renderCompanies();
    if (view === 'profile') return await renderProfile();
    if (view === 'mentor') return await renderMentor();
    if (view === 'admin') return await renderAdmin();
    if (view === 'questions') return await renderQuestions();
    if (view === 'import') return await renderImport();
  } catch (e) {
    const v = el('view');
    v.innerHTML = `<div class="card enter"><h3>Something went wrong</h3>
      <p class="muted">${esc(e && e.message ? e.message : String(e))}</p>
      <button class="btn mt" id="retryView">Try again</button></div>`;
    const rt = v.querySelector('#retryView');
    if (rt) rt.onclick = () => go(view);
    M.announce('This page could not load. ' + (e && e.message ? e.message : ''));
  }
}

/* ---------------- notifications ---------------- */
async function loadNotifications() {
  try {
    const list = await api('/student/notifications');
    const unread = list.filter(n => !n.read).length;
    const bell = el('bellCount');
    bell.textContent = unread; bell.classList.toggle('hide', unread === 0);
    el('notifPanel').innerHTML = list.length
      ? list.map(n => `<div class="item"><b>${esc(n.title)}</b><p class="small muted">${esc(n.body || '')}</p>
          <span class="small muted">${esc((n.createdAt || '').slice(0, 10))}${n.dueDate ? ' · due ' + esc(n.dueDate) : ''}</span></div>`).join('')
      : '<p class="muted small">No notifications yet.</p>';
  } catch (e) { /* silent */ }
}
el('bellBtn').addEventListener('click', () => api('/student/notifications/read', { method: 'POST' }).then(() => {
  el('bellCount').classList.add('hide');
}).catch(() => { }));

/* ---------------- dashboard ---------------- */
async function renderDashboard() {
  const d = await api('/student/dashboard');
  const nba = d.nextBestAction || {};
  const prs = d.prs || { parts: [] };
  const overall = d.improvement?.overall || {};
  const imp = overall.deltaPoints || 0;
  const attempts = d.attempts || [];
  const labels = attempts.map((a, i) => 'A' + (i + 1));
  const sections = (d.improvement?.sections || []).filter(x => x.name);
  el('view').innerHTML = `
    <div class="card primary-card" id="nbaCard">
      <div class="between">
        <div>
          <span class="tag">Next best action</span>
          <h2 class="mt">${esc(nba.title || 'Take the diagnostic assessment')}</h2>
          <p class="muted small">${esc(nba.reason || '')}</p>
        </div>
        <div class="row">
          <button class="btn primary" id="nbaBtn">Do it now</button>
          <button class="btn ghost" data-go="planner">Open planner</button>
        </div>
      </div>
      ${(d.dueReviews || []).length ? `<div class="between mt"><span class="small muted">${d.dueReviews.length} revision topic(s) are due today</span>
        <button class="btn" id="reviewBtn">Start revision quiz</button></div>` : ''}
    </div>

    <div class="grid g2">
      <div class="card">
        <div class="ring-wrap">
          <svg class="ring" viewBox="0 0 132 132" role="img" aria-label="Placement readiness score">
            <circle class="track"></circle>
            <circle class="val" transform="rotate(-90 66 66)"></circle>
            <text class="ring-value" x="66" y="66" text-anchor="middle" dominant-baseline="middle"><tspan id="ringNum">0</tspan></text>
            <text class="ring-sub" x="66" y="86" text-anchor="middle">out of 100</text>
          </svg>
          <div>
            <span class="label muted tiny">Placement readiness</span>
            <h3>${levelTag(prs.readiness)}</h3>
            <p class="small muted" id="whatMoved">${esc((prs.parts || [])[0] ? (prs.parts[0].component + ': ' + Math.round(prs.parts[0].value) + '% · ' + (prs.parts[0].detail || '')) : 'Take an assessment to build your score.')}</p>
          </div>
        </div>
      </div>
      <div class="grid g3">
        <div class="card kpi"><span class="label">Latest</span><span class="value" id="latestVal">${pct(overall.latest)}</span>
          <span class="small ${imp >= 0 ? '' : 'muted'}">${imp >= 0 ? '▲' : '▼'} ${Math.abs(imp)} pts vs first attempt</span></div>
        <div class="card kpi"><span class="label">Improvement</span><span class="value" id="impVal">${overall.percentChange == null ? '—' : overall.percentChange}<span class="small">${overall.percentChange == null ? '' : '%'}</span></span>
          <span class="small muted">${esc(overall.trend || 'no-data')} · ${overall.attempts || 0} assessment(s)</span></div>
        <div class="card kpi"><span class="label">XP · streak</span><span class="value">${d.xp?.total || 0}</span>
          <span class="small muted">${d.xp?.streak || 0}-day streak · ${(d.xp?.badges || []).length} badge(s)</span></div>
      </div>
    </div>

    <div class="grid g4" id="sectionCards">
      ${sections.map(sc => {
        const data = attempts.map(a => { const x = (a.sections || []).find(y => y.section === sc.section); return x ? x.percentage : 0; });
        const low = Math.min(...data, 100), high = Math.max(...data, 1);
        const pts = data.map((v, i) => `${(i / Math.max(1, data.length - 1)) * 100},${28 - ((v - low) / Math.max(1, high - low)) * 24}`).join(' ');
        const level = sc.latest >= 75 ? 'Advanced' : sc.latest >= 45 ? 'Intermediate' : 'Beginner';
        return `<div class="card tight lift">
          <div class="between"><span class="label muted tiny">${esc(sc.name)}</span>${levelTag(level)}</div>
          <div class="kpi"><span class="value">${Math.round(sc.latest || 0)}%</span></div>
          <svg class="spark" viewBox="0 0 100 30" preserveAspectRatio="none" aria-hidden="true">
            <polyline points="${pts}" fill="none" stroke="${Charts.lvl(sc.latest || 0)}" stroke-width="2" stroke-linecap="round"></polyline>
          </svg>
          <span class="tiny muted">${sc.deltaPoints >= 0 ? '+' : ''}${sc.deltaPoints} pts since first attempt</span>
        </div>`;
      }).join('') || '<div class="card empty">Section cards appear after your first assessment.</div>'}
    </div>

    <div class="card">
      <div class="card-h"><h3>Upcoming tasks</h3><button class="btn small ghost" data-go="planner">Open planner</button></div>
      <ul class="clean" id="upcoming"></ul>
    </div>

    <div class="split">
      <div class="card">
        <h3>Score progression</h3>
        <p class="muted small">Overall and per-section percentage across your submitted attempts.</p>
        <canvas id="lineChart"></canvas>
        <div class="legend" id="lineLegend"></div>
      </div>
      <div class="card">
        <h3>Readiness breakdown</h3>
        <p class="muted small">How each component contributes to your Placement Readiness Score.</p>
        <ul class="clean" id="prsParts"></ul>
      </div>
    </div>

    <div class="split">
      <div class="card"><h3>Skill profile</h3><canvas id="radar"></canvas></div>
      <div class="card"><h3>Accuracy vs speed</h3><canvas id="scatter"></canvas></div>
    </div>

    <div class="card">
      <h3>Topic heatmap</h3>
      <p class="muted small">Accuracy by topic across all your attempts and mock tests.</p>
      <div class="heat" id="heat"></div>
    </div>

    <div class="split">
      <div class="card"><h3>Top skill gaps</h3><ul class="clean" id="gapList"></ul></div>
      <div class="card"><h3>Recommended next</h3><ul class="clean" id="recList"></ul></div>
    </div>

    <div class="card">
      <h3>Assessment history</h3>
      <div class="scroll-x"><table>
        <thead><tr><th class="static">#</th><th class="static">Attempt</th><th class="static">Date</th><th class="static">Score</th>
        <th class="static">Readiness</th><th class="static">Accuracy</th><th class="static">Flags</th><th class="static"></th></tr></thead>
        <tbody id="attemptRows"></tbody></table></div>
    </div>`;

  if (attempts.length > 1) state.lastLevel = attempts[attempts.length - 2].readiness;

  /* ---- motion: ring fills 0 -> value, numbers count up, cards stagger in ---- */
  M.ring(el('view').querySelector('.ring'), prs.score || 0);
  M.countUp(el('ringNum'), Math.round(prs.score || 0), { dur: 1100 });
  const latestEl = el('latestVal'), impEl = el('impVal');
  if (latestEl) M.countUp(latestEl, overall.latest || 0, { suffix: '%', dur: 800 });
  if (impEl && overall.percentChange != null) M.countUp(impEl, overall.percentChange, { decimals: 1, suffix: '%', dur: 800 });
  M.stagger(el('view'), '.card, .week', { step: 50 });
  if (M.firstViewToday('ring')) setTimeout(() => Sound.play('pageComplete'), 1150);   // once per day, after the ring lands
  const upcoming = el('upcoming');
  if (upcoming) {
    const tasks = [];
    for (const w of ((d.plan || {}).weeks || [])) for (const t of (w.tasks || []))
      if (t.status !== 'done' && tasks.length < 4) tasks.push({ ...t, week: w.label });
    upcoming.innerHTML = tasks.map(t => `<li class="check"><span aria-hidden="true">${t.carriedOver ? '⏳' : '•'}</span>
      <div><b>${esc(t.title)}</b><div class="small muted">${esc(t.type)} · due ${esc(t.dueDate)} · ${t.hours} h ${t.overdue ? '<span class="overdue">overdue</span>' : ''}</div></div></li>`).join('')
      || '<li class="empty small">Nothing scheduled - your planner will fill this as your gaps change.</li>';
  }

  const colours = ['#4f7cff', '#22d3a6', '#ffb020', '#ff5f6d', '#a78bfa'];
  const series = [{ name: 'Overall', color: '#4f7cff', data: attempts.map(a => a.overallPct) }];
  (d.improvement?.sections || []).forEach((s, i) => {
    const data = attempts.map((a, idx) => {
      const sec = (a.sections || []).find(x => x.section === s.section);
      return sec ? sec.percentage : null;
    });
    if (data.some(v => v != null)) series.push({ name: s.name, color: colours[(i + 1) % colours.length], data: data.map(v => v == null ? 0 : v) });
  });
  Charts.line(el('lineChart'), labels, series);
  el('lineLegend').innerHTML = series.map(s => `<span><i style="background:${s.color}"></i>${esc(s.name)}</span>`).join('');
  el('lineChart').parentElement.insertAdjacentHTML('beforeend', `<p class="summary">${esc(Charts.summarize(series.slice(0, 2), labels))}</p>`);

  el('prsParts').innerHTML = (prs.parts || []).map(p => `
    <li><div class="between"><strong>${esc(p.component)}</strong><span>${Math.round(p.value)}% ${p.weight ? `<span class="muted small">(weight ${Math.round(p.weight * 100)}%)</span>` : ''}</span></div>
    <div class="bar"><i style="width:${Math.min(100, p.value)}%"></i></div>
    <span class="small muted">${esc(p.detail || '')}</span></li>`).join('') || '<li class="muted small">Take an assessment to build your readiness score.</li>';

  Charts.radar(el('radar'), d.charts?.radar || []);
  Charts.scatter(el('scatter'), d.charts?.scatter || []);
  Charts.heat(el('heat'), d.charts?.heatmap || []);

  el('gapList').innerHTML = (d.gaps || []).slice(0, 6).map(g => `
    <li><div class="between"><strong>${esc(g.skill)}</strong><span class="tag ${g.accuracy >= 75 ? 'good' : g.accuracy >= 50 ? 'warn' : 'bad'}">${Math.round(g.accuracy)}%</span></div>
    <span class="small muted">${esc(g.section)} · impact ${g.impact} · severity ${g.severity}</span></li>`).join('') || '<li class="muted small">No gaps - strong profile. Keep practising to maintain speed.</li>';

  el('recList').innerHTML = (d.recommendations || []).map(r => `
    <li class="card tight"><div class="between"><strong>${esc(r.title)}</strong><span class="tag">${esc(r.level)}</span></div>
      <p class="small muted">${esc(r.whyThis)}</p>
      <div class="row"><span class="tag">${r.hours} h</span><span class="tag">match ${esc(String(r.difficultyMatch))}</span><span class="tag">${esc(r.status)}</span>
      <button class="btn small" data-course="${esc(r.courseId)}">Mark in progress</button>
      <button class="btn small ghost" data-course-done="${esc(r.courseId)}">Mark completed</button></div></li>`).join('')
    || '<li class="muted small">Recommendations appear after your first assessment.</li>';

  el('attemptRows').innerHTML = attempts.slice().reverse().map((a, i) => `
    <tr><td>${attempts.length - i}</td><td>${esc(a.label)}</td><td>${esc((a.submittedAt || '').slice(0, 10))}</td>
      <td><b>${pct(a.overallPct)}</b></td><td>${levelTag(a.readiness)}</td><td>${pct(a.accuracy * 100)}</td>
      <td>${a.violations ? `<span class="tag bad">${a.violations} tab switch</span>` : '<span class="muted small">clean</span>'}</td>
      <td><button class="btn small" data-attempt="${a.id}">Review</button></td></tr>`).join('')
    || '<tr><td colspan="8" class="muted">No attempts yet.</td></tr>';

  el('view').querySelectorAll('[data-go]').forEach(b => b.onclick = () => go(b.dataset.go));
  el('view').querySelectorAll('[data-attempt]').forEach(b => b.onclick = () => showAttempt(b.dataset.attempt));
  el('view').querySelectorAll('[data-course]').forEach(b => b.onclick = () => setCourse(b.dataset.course, 'in_progress', 30));
  el('view').querySelectorAll('[data-course-done]').forEach(b => b.onclick = () => setCourse(b.dataset.course, 'completed', 100));
  el('nbaBtn').onclick = () => {
    if (nba.type === 'plan-task') go('planner');
    else if (nba.type === 'course') go('gaps');
    else go('assessment');
  };
  const rb = el('reviewBtn');
  if (rb) rb.onclick = () => startReviewQuiz(d.dueReviews);
}
async function setCourse(id, status, p) {
  await api('/student/courses/' + id + '/status', { method: 'POST', body: { status, progressPct: p } });
  const chip = document.querySelector(`[data-course-chip="${id}"]`);
  if (chip) { chip.className = 'tag ' + (status === 'completed' ? 'good' : 'warn'); chip.textContent = status.replace('_', ' '); }
  const wrap = document.querySelector(`[data-progress="${id}"]`);   // live feedback on the Learn screen
  if (wrap) {
    const bar = wrap.querySelector('.bar > i');
    if (bar) { bar.style.width = p + '%'; if (status === 'completed') bar.parentElement.classList.add('success'); }
    const note = wrap.querySelector('[data-course-note]');
    if (note) note.textContent = status === 'completed' ? 'Completed' : 'In progress - ' + p + '%';
  }
  if (status === 'completed') { M.milestone(chip, 'Course completed'); toast('Course completed - re-assess to confirm the gap closed'); }
  else { Sound.play('taskCheck'); toast('Course marked ' + status.replace('_', ' ')); }
  if (status === 'completed') setTimeout(() => go(state.view), 900);
}

/* ---------------- assessment ---------------- */
async function renderAssessmentHome() {
  const meta = state.meta || {};
  const sections = meta.sections || [];
  el('view').innerHTML = `
    <div class="card">
      <h2>Start an assessment</h2>
      <p class="muted">Questions adapt to your performance: correct answers unlock harder questions, mistakes bring easier ones.
      Anti-cheating is on - switching tabs is logged against the attempt.</p>
      <div class="grid g2 mt">
        <div>
          <label>Question count<input type="number" id="qCount" value="20" min="4" max="40"></label>
          <label class="mt">Domain<select id="domain">${(meta.domains || []).map(d => `<option ${d === state.user.domain ? 'selected' : ''}>${esc(d)}</option>`).join('')}</select></label>
          <label class="mt">Time limit (minutes, 0 = untimed)<input type="number" id="timeLimit" value="30" min="0" max="180"></label>
        </div>
        <div>
          <p class="small muted">Sections</p>
          <div class="stack" id="sectionPick">
            ${sections.map(s => `<label class="opt sel"><input type="checkbox" value="${esc(s.id)}" checked><span>${esc(s.name)} <span class="muted small">weight ${Math.round(s.weight * 100)}%</span></span></label>`).join('')}
          </div>
        </div>
      </div>
      <div class="row mt">
        <button class="btn primary" id="startBtn">Start diagnostic</button>
        <button class="btn" id="companyBtn">Company-pattern practice</button>
      </div>
    </div>
    <div class="card">
      <h3>How scoring works</h3>
      <ul class="clean small muted">
        <li>Every question is worth 1 mark; code and written answers score partial credit against a rubric.</li>
        <li>Readiness bands: Beginner under 45%, Intermediate 45-75%, Advanced above 75% (configurable in <code>data/config.json</code>).</li>
        <li>Your answers feed the skill-gap engine, which drives the courses and planner tasks you see.</li>
      </ul>
    </div>`;
  el('sectionPick').querySelectorAll('input').forEach(i => i.onchange = () => i.closest('.opt').classList.toggle('sel', i.checked));
  el('startBtn').onclick = () => startAssessment('diagnostic');
  el('companyBtn').onclick = () => startAssessment('company', prompt('Company id (tcs, infosys, zoho, amazon, cognizant)', (state.user.targetCompany || 'tcs').toLowerCase()) || 'tcs');
}
async function startAssessment(kind, company) {
  const sections = [...el('view').querySelectorAll('#sectionPick input:checked')].map(i => i.value);
  Sound.setTestMode(true);          // test mode rule: no right/wrong sounds until the attempt ends
  if (!sections.length) return toast('Pick at least one section');
  const body = {
    kind, sections, domain: el('domain')?.value || state.user.domain,
    questionCount: Number(el('qCount')?.value || 20), timeLimitMin: Number(el('timeLimit')?.value || 0),
    company: company || ''
  };
  const out = await api('/student/assessment/start', { method: 'POST', body });
  state.attempt = { id: out.attemptId, asked: 0, total: out.questionCount, violations: 0, startedAt: Date.now(),
                    timeLimitSec: out.timeLimitSec || 0, answered: [] };
  state.violations = 0;
  document.addEventListener('visibilitychange', onVisibility);
  if (state.attempt.timeLimitSec) startOverallTimer(state.attempt.timeLimitSec);
  if (out.question) showQuestion(out.question, 1, out.questionCount);
  else finishAssessment();
}
async function startReviewQuiz(due) {
  const out = await api('/student/assessment/start', {
    method: 'POST', body: { kind: 'review', questionCount: due.length, sections: [...new Set(due.map(d => d.section || 'aptitude'))], questionIds: due.map(d => d.questionId) }
  });
  state.attempt = { id: out.attemptId, asked: 0, total: out.questionCount, violations: 0, startedAt: Date.now() };
  document.addEventListener('visibilitychange', onVisibility);
  if (out.question) showQuestion(out.question, 1, out.questionCount); else finishAssessment();
}
/* Overall countdown with amber/red states and the two gentle warnings (5 min, 1 min). */
function startOverallTimer(totalSec) {
  clearInterval(state.overallTimer);
  const started = Date.now();
  let warned5 = false, warned1 = false;
  const paint = () => {
    const left = Math.max(0, totalSec - Math.round((Date.now() - started) / 1000));
    const t = el('overallTimer');
    if (t) {
      t.textContent = `${String(Math.floor(left / 60)).padStart(2, '0')}:${String(left % 60).padStart(2, '0')} left`;
      t.classList.toggle('warn', left <= 300 && left > 60);
      t.classList.toggle('low', left <= 60);
    }
    if (left <= 300 && !warned5) { warned5 = true; Sound.play('timerWarn'); toast('5 minutes left'); }
    if (left <= 60 && !warned1) { warned1 = true; Sound.play('timerWarn'); toast('1 minute left'); }
    if (left === 0) { clearInterval(state.overallTimer); finishAssessment(true); }
  };
  paint();
  state.overallTimer = setInterval(paint, 1000);
}

/* Calm, non-punitive banner if the tab is switched (spec). */
function showCalmBanner() {
  if (el('calmBanner')) return;
  const b = document.createElement('div');
  b.id = 'calmBanner';
  b.className = 'calm-banner mb';
  b.innerHTML = `<span aria-hidden="true">ℹ️</span><span>You switched tabs. That is fine - it is logged for fairness and shown to your mentor as a flag.</span>`;
  el('view').prepend(b);
  M.announce('Tab switch noted');
}

function onVisibility() { if (document.hidden && state.attempt) { state.violations++; showCalmBanner(); api('/student/events', { method: 'POST', body: { type: 'tab_switch', attemptId: state.attempt.id, detail: 'student left the tab' } }).catch(() => { }); } }

let currentQuestion = null, questionStart = 0;
function showQuestion(q, index, total) {
  currentQuestion = q; questionStart = Date.now();
  M.announce(`Question ${index} of ${total}, ${q.section}, ${q.difficulty} difficulty`);
  const type = q.type || 'mcq';
  const options = q.options || [];
  const body = type === 'coding' ? `<textarea id="codeBox" rows="12" spellcheck="false">${esc(q.starter || '')}</textarea>
      <p class="muted small">Static analysis only in this build - the checks below are what a real sandboxed runner would verify.</p>`
    : type === 'written' ? `<textarea id="answerBox" rows="8" placeholder="Write your answer..."></textarea>`
    : type === 'short' ? `<input id="answerBox" placeholder="Type your answer" />`
    : options.map((o, i) => `<label class="opt" data-opt>
        <input type="${type === 'multi' ? 'checkbox' : 'radio'}" name="opt" value="${String.fromCharCode(65 + i)}">
        <span><b>${String.fromCharCode(65 + i)}</b> ${esc(o)}</span></label>`).join('');

  const answeredCount = (state.attempt?.answered || []).length;
  el('view').innerHTML = `
    <div class="split" style="grid-template-columns:1fr 54px">
    <div class="card qcard slide-in">
      <div class="qmeta">
        <span class="chip">Q${index} / ${total}</span>
        ${state.attempt?.timeLimitSec ? `<span class="chip timer" id="overallTimer">--:-- left</span>` : ''}
        <span class="tag">${esc(q.section)}</span><span class="tag">${esc(q.topic || '')}</span>
        <span class="tag ${q.difficulty === 'hard' ? 'bad' : q.difficulty === 'easy' ? 'good' : 'warn'}">${esc(q.difficulty)}</span>
        ${type === 'coding' ? '<span class="tag">coding</span>' : ''}
        <span class="timer" id="qTimer">--:--</span>
      </div>
      <h2 class="mt">${esc(q.prompt)}</h2>
      <div class="stack mt" id="answerArea">${body}</div>
      <div class="row mt">
        <button class="btn primary" id="submitAnswer">Submit answer</button>
        <button class="btn ghost" id="skipAnswer">Skip</button>
        ${index >= total ? '<span class="muted small">Last question</span>' : ''}
      </div>
      <div id="feedback" class="mt"></div>
    </div>
    <div class="nav-strip" aria-label="Question navigator">
      ${Array.from({ length: total }).map((_, i) => `<button disabled class="${i < answeredCount ? 'answered' : i === index - 1 ? 'current' : ''}"
        title="Question ${i + 1}">${i < answeredCount ? '✓' : i + 1}</button>`).join('')}
    </div>
    </div>`;

  el('answerArea').querySelectorAll('.opt').forEach(l => l.onclick = () => {
    const input = l.querySelector('input');
    if (input.type === 'radio') el('answerArea').querySelectorAll('.opt').forEach(x => x.classList.remove('sel'));
    Sound.play('tap');                                   // soft tap only - never right/wrong mid-test
    setTimeout(() => l.classList.toggle('sel', input.checked), 0);
  });

  let remaining = q.timeLimit || 90;
  clearInterval(state.timer);
  const tick = () => {
    const m = Math.floor(remaining / 60), s = remaining % 60;
    const t = el('qTimer');
    if (!t) return;
    t.textContent = `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
    t.classList.toggle('low', remaining <= 15);
    if (remaining-- <= 0) { clearInterval(state.timer); submitOne(true); }
  };
  tick(); state.timer = setInterval(tick, 1000);

  el('submitAnswer').onclick = () => submitOne(false);
  el('skipAnswer').onclick = () => submitOne(false, true);
}
async function submitOne(auto, skip) {
  clearInterval(state.timer);
  const q = currentQuestion;
  const type = q.type || 'mcq';
  let answer = [];
  if (!skip) {
    if (type === 'coding') answer = [el('codeBox')?.value || ''];
    else if (type === 'written' || type === 'short') answer = [el('answerBox')?.value || ''];
    else answer = [...el('answerArea').querySelectorAll('input:checked')].map(i => i.value);
  }
  const timeSec = Math.round((Date.now() - questionStart) / 1000);
  const res = await api(`/student/assessment/${state.attempt.id}/answer`, { method: 'POST', body: { questionId: q.id, answer, timeSec } });
  const cls = res.correct ? 'ok' : 'bad';
  el('feedback').innerHTML = `<div class="feedback ${cls}">
      <strong>${res.correct ? 'Correct' : skip ? 'Skipped' : 'Not quite'}</strong> · ${res.awarded} mark(s)
      <p class="small">${esc(res.explanation || '')}</p>
      ${res.modelAnswer ? `<details><summary class="small">Model answer</summary><pre class="small">${esc(res.modelAnswer)}</pre></details>` : ''}
      ${res.runner && res.runner.checks ? `<div class="small muted mt">${res.runner.checks.map(c => `${c.passed ? '✅' : '❌'} ${esc(c.label)}`).join('<br>')}
        <p class="small">${esc(res.runner.note || '')}</p></div>` : ''}
      <div class="row mt"><button class="btn primary small" id="nextQ">Next question</button></div></div>`;
  el('submitAnswer').disabled = true;
  el('skipAnswer').disabled = true;
  el('nextQ').onclick = nextQuestion;
  el('nextQ').focus();
}
async function nextQuestion() {
  const nxt = await api(`/student/assessment/${state.attempt.id}/next`);
  if (nxt.done || !nxt.question) return finishAssessment();
  const card = el('view').querySelector('.qcard');
  if (card) {
    card.classList.remove('slide-in');
    card.classList.add('slide-out');
    setTimeout(() => showQuestion(nxt.question, nxt.asked, nxt.total), 190);   // card slides out left, next slides in
  } else showQuestion(nxt.question, nxt.asked, nxt.total);
}
async function finishAssessment(auto) {
  document.removeEventListener('visibilitychange', onVisibility);
  clearInterval(state.overallTimer);
  clearInterval(state.timer);
  const payload = { violations: state.violations, timeSec: Math.round((Date.now() - state.attempt.startedAt) / 1000) };
  const attemptId = state.attempt.id;
  const result = await api(`/student/assessment/${attemptId}/submit`, { method: 'POST', body: payload });
  state.attempt = null;
  Sound.setTestMode(false);                        // test mode ends with the test
  Sound.play(auto ? 'submitConfirm' : 'pageComplete');
  if (auto) toast('Time up - your answers were submitted');
  const firstEver = ((result.improvement || {}).overall || {}).attempts === 1;
  if (firstEver && !localStorage.getItem('placeiq_first_diag_' + state.user.id)) {
    localStorage.setItem('placeiq_first_diag_' + state.user.id, '1');
    setTimeout(() => M.milestone(document.querySelector('#resOverall')?.closest('.card'), 'First diagnostic completed'), 1100);
  }
  renderResult(result);
}
function renderResult(r) {
  el('viewTitle').textContent = 'Your result';
  const prevLevel = state.lastLevel;
  state.lastLevel = r.readiness;
  const levelledUp = prevLevel && prevLevel !== r.readiness && r.readiness !== 'Beginner';
  el('view').innerHTML = `
    <div class="card">
      <div class="between"><h2>${esc(r.label || 'Assessment result')}</h2>${levelTag(r.readiness)}</div>
      <div class="grid g4 mt">
        <div class="kpi"><span class="label">Overall</span><span class="value" id="resOverall">0%</span></div>
        <div class="kpi"><span class="label">Accuracy</span><span class="value">${pct((r.accuracy || 0) * 100)}</span></div>
        <div class="kpi"><span class="label">Marks</span><span class="value">${r.totalScore}/${r.maxScore}</span></div>
        <div class="kpi"><span class="label">Proctor flags</span><span class="value">${r.violations || 0}</span></div>
      </div>
      ${r.violations ? '<p class="tag bad mt">Tab switching was logged on this attempt - mentors see this flag.</p>' : ''}
    </div>
    <div class="split">
      <div class="card"><h3>Section breakdown</h3><div class="scroll-x"><table>
        <thead><tr><th>Section</th><th>Score</th><th>Accuracy</th><th>Avg time</th><th>Readiness</th></tr></thead>
        <tbody>${(r.sections || []).map(s => `<tr><td>${esc(s.name || s.section)}</td><td>${pct(s.percentage)}</td>
          <td>${pct((s.accuracy || 0) * 100)}</td><td>${Math.round(s.avgTimeSec || 0)}s</td><td>${levelTag(s.readiness)}</td></tr>`).join('')}</tbody></table></div></div>
      <div class="card"><h3>Readiness after this attempt</h3>
        <p class="muted small">Composite score and its components.</p>
        <div class="kpi"><span class="value">${Math.round(r.prs?.score || 0)}<span class="muted" style="font-size:1rem">/100</span></span>
        <span class="small muted">${esc(r.prs?.readiness || '')}</span></div>
        <ul class="clean" id="resultParts"></ul></div>
    </div>
    <div class="card">
      <h3>Recommended next steps</h3>
      <p class="muted small">Each course below closes a specific gap found in this attempt.</p>
      <ul class="clean">${(r.recommendations || []).map(x => `<li class="card tight">
        <div class="between"><strong>${esc(x.title)}</strong><span class="tag">${esc(x.level)}</span></div>
        <p class="small muted">${esc(x.whyThis)}</p>
        <div class="row"><button class="btn small" data-course="${esc(x.courseId)}">Start this course</button></div></li>`).join('')
        || '<li class="muted small">No gaps found - excellent.</li>'}</ul>
    </div>
    <div class="row"><button class="btn primary" data-go="dashboard">Back to dashboard</button>
      <button class="btn" data-go="results">Full history</button>
      <button class="btn ghost" data-go="mock">Try a timed mock test</button></div>`;
  /* motion: score counts up, gap cards slide up in sequence, milestone only when earned */
  M.countUp(el('resOverall'), r.overallPct || 0, { decimals: 1, suffix: '%', dur: 900 });
  M.stagger(el('view'), '.card.tight', { step: 55 });
  const resultCard = el('view').querySelector('.card');
  if (levelledUp) M.milestone(resultCard, 'New readiness level: ' + r.readiness);
  el('resultParts').innerHTML = (r.prs?.parts || []).map(p => `<li><div class="between"><span>${esc(p.component)}</span><b>${Math.round(p.value)}%</b></div>
    <div class="bar"><i style="width:${Math.min(100, p.value)}%"></i></div><span class="small muted">${esc(p.detail || '')}</span></li>`).join('');
  el('view').querySelectorAll('[data-go]').forEach(b => b.onclick = () => go(b.dataset.go));
  el('view').querySelectorAll('[data-course]').forEach(b => b.onclick = () => setCourse(b.dataset.course, 'in_progress', 30));
}

/* ---------------- results & history ---------------- */
async function renderResults() {
  const [attempts, analytics] = await Promise.all([api('/student/attempts'), api('/student/analytics')]);
  const imp = analytics.improvement?.overall || {};
  el('view').innerHTML = `
    <div class="grid g4">
      <div class="card kpi"><span class="label">Attempts</span><span class="value">${imp.attempts || 0}</span></div>
      <div class="card kpi"><span class="label">First</span><span class="value">${pct(imp.first)}</span></div>
      <div class="card kpi"><span class="label">Latest</span><span class="value">${pct(imp.latest)}</span></div>
      <div class="card kpi"><span class="label">Improvement</span><span class="value" id="progImp">0%</span>
        <span class="small muted">${esc(imp.trend || '')} · ${imp.deltaPoints || 0} pts
        <span class="tag ${imp.trend === 'improving' ? 'good' : imp.trend === 'declining' ? 'bad' : 'warn'}">${esc(imp.trend || 'no-data')}</span></span></div>
    </div>
    <div class="card">
      <div class="card-h"><h3>Overall vs sections over time</h3>
        <div class="row" id="progToggle">
          <button class="btn small primary" data-series="overall">Overall</button>
          ${(analytics.improvement?.sections || []).map(s => `<button class="btn small" data-series="${esc(s.section)}">${esc(s.name)}</button>`).join('')}
        </div>
      </div>
      <canvas id="histChart"></canvas>
      <div class="legend" id="histLegend"></div>
    </div>
    <div class="split">
      <div class="card"><h3>Readiness history</h3><div class="scroll-x"><table>
        <thead><tr><th>Attempt</th><th>Date</th><th>Score</th><th>PRS</th></tr></thead>
        <tbody>${(analytics.readinessHistory || []).map(h => `<tr><td>${esc(h.label || '')}</td><td>${esc((h.createdAt || '').slice(0, 10))}</td>
          <td>${pct(h.overallPct)}</td><td>${Math.round(h.prs || 0)}</td></tr>`).join('') || '<tr><td colspan="4" class="muted">No data</td></tr>'}</tbody></table></div></div>
      <div class="card"><h3>Spaced-repetition queue</h3><p class="muted small">Topics you missed resurface at 1, 3, 7, 16 and 35-day intervals.</p>
        <div id="srsList"></div></div>
    </div>
    <div class="card"><h3>All attempts</h3><div class="scroll-x"><table>
      <thead><tr><th>Attempt</th><th>Type</th><th>Date</th><th>Score</th><th>Readiness</th><th></th></tr></thead>
      <tbody>${(attempts || []).slice().reverse().map(a => `<tr><td>${esc(a.label)}</td><td class="muted small">${esc(a.kind)}</td>
        <td>${esc((a.submittedAt || '').slice(0, 10))}</td><td><b>${pct(a.overallPct)}</b></td><td>${levelTag(a.readiness)}</td>
        <td><button class="btn small" data-attempt="${a.id}">Review</button></td></tr>`).join('') || '<tr><td colspan="6" class="muted">Nothing yet</td></tr>'}</tbody></table></div></div>`;

  const labels = (attempts || []).map((a, i) => 'A' + (i + 1));
  const colours = ['#4f7cff', '#22d3a6', '#ffb020', '#ff5f6d', '#a78bfa', '#ff8ad8'];
  const series = [{ name: 'Overall', color: '#4f7cff', data: (attempts || []).map(a => a.overallPct) }];
  (analytics.improvement?.sections || []).forEach((s, i) => {
    const data = (attempts || []).map(a => { const x = (a.sections || []).find(y => y.section === s.section); return x ? x.percentage : 0; });
    if (data.some(v => v)) series.push({ name: s.name, color: colours[(i + 1) % colours.length], data });
  });
  const allSeries = series.slice();
  const drawSeries = (key) => {
    const picked = key === 'overall' ? [allSeries[0]] : allSeries.filter(x => x.name === key || x.section === key);
    Charts.line(el('histChart'), labels, picked);
    el('histLegend').innerHTML = picked.map(s => `<span><i style="background:${s.color}"></i>${esc(s.name)}</span>`).join('')
      + `<span class="summary" style="border:0;padding:0">${esc(Charts.summarize(picked, labels))}</span>`;
  };
  if ((attempts || []).length) {
    drawSeries('overall');
    el('progToggle').querySelectorAll('[data-series]').forEach(b => b.onclick = () => {
      el('progToggle').querySelectorAll('button').forEach(x => x.classList.toggle('primary', x === b));
      Sound.play('tap');
      M.crossFade(el('histChart').parentElement, () => drawSeries(b.dataset.series));   // cross-fade the lines
    });
    M.countUp(el('progImp'), Math.max(0, imp.percentChange || 0), { decimals: 1, suffix: '%', dur: 900 });
    M.stagger(el('view'), '.card', { step: 50 });
  }
  const due = await api('/student/reviews');
  el('srsList').innerHTML = due.length ? due.map(d => `<div class="check"><span class="tag warn">due ${esc(d.dueDate)}</span>
      <div><b>${esc(d.topic)}</b><p class="small muted">${esc((d.prompt || '').slice(0, 120))}</p></div></div>`).join('')
    + `<button class="btn primary mt" id="reviseNow">Take revision quiz (${due.length})</button>`
    : '<p class="muted small">Nothing due - you are on top of your weak topics.</p>';
  const rb = el('reviseNow');
  if (rb) rb.onclick = () => startReviewQuiz(due);
  el('view').querySelectorAll('[data-attempt]').forEach(b => b.onclick = () => showAttempt(b.dataset.attempt));
}
async function showAttempt(id) {
  const a = await api('/student/attempts/' + id);
  const rows = (a.answers || []).map(x => `<tr><td>${esc(x.section)}</td><td>${esc(x.topic || '')}</td>
      <td>${x.correct ? '✅' : (x.awarded > 0 ? '◐' : '❌')}</td><td>${x.awarded}</td><td>${Math.round(x.timeSec)}s</td>
      <td class="small muted">${esc((x.prompt || '').slice(0, 70))}</td></tr>`).join('');
  openModal(`<h2>${esc(a.label)}</h2>
    <p class="muted small">${esc((a.submittedAt || '').slice(0, 16))} · ${pct(a.overallPct)} · ${esc(a.readiness)} · ${a.violations || 0} proctor flag(s)</p>
    <div class="scroll-x mt"><table><thead><tr><th>Section</th><th>Topic</th><th></th><th>Marks</th><th>Time</th><th>Question</th></tr></thead><tbody>${rows}</tbody></table></div>`);
}

/* ---------------- learn: course catalogue + free external resources ---------------- */
const RES_TYPE = { course: 'Course', video: 'Video', practice: 'Practice', reference: 'Reference', tool: 'Timed test' };
const SECTION_IDS = ['aptitude', 'logical', 'coding', 'communication', 'domain'];

async function renderLearn() {
  const d = await api('/student/learn');
  const isStudent = state.user.role === 'student';
  const courses = d.courses || [], resources = d.resources || [], sections = d.sections || [];
  const weakest = d.weakestSection;
  const secName = id => (sections.find(x => x.id === id) || {}).name || id;
  const recommended = courses.filter(c => c.recommended);
  const others = courses.filter(c => !c.recommended);
  const target = d.targetSection || 75;

  const courseCard = c => {
    const pct = c.progressPct || 0;
    const started = c.status === 'in_progress' || pct > 0;
    return `<div class="card tight learn-card">
      <div class="between"><strong>${esc(c.title)}</strong>
        <span class="tag ${c.recommended ? 'good' : ''}">${c.recommended ? 'matches your gaps' : esc(c.level)}</span></div>
      <p class="small muted">${esc(c.description || '')}</p>
      <div class="row wrap tight-row"><span class="chip">${esc(c.provider)}</span><span class="chip">${c.hours} h</span>
        <span class="chip">${esc(c.level)}</span>${c.free ? '<span class="chip good">free</span>' : ''}</div>
      <div class="mt" data-progress="${esc(c.id)}">
        <div class="between"><span class="tag ${c.status === 'completed' ? 'good' : started ? 'warn' : ''}"
          data-course-chip="${esc(c.id)}">${c.status === 'completed' ? 'completed' : started ? 'in_progress' : 'not started'}</span>
          <span class="small muted" data-course-note>${c.status === 'completed' ? 'Completed' : started ? 'In progress - ' + pct + '%' : 'Not started'}</span></div>
        <div class="bar${c.status === 'completed' ? ' success' : ''}" title="${pct}% complete"><i style="width:${pct}%"></i></div>
      </div>
      <div class="row mt">
        <a class="btn small primary" href="${esc(c.url)}" target="_blank" rel="noopener noreferrer"
           title="${esc(c.linkLabel || c.url)}">Open free resource ↗</a>
        ${isStudent ? `<button class="btn small" data-course="${esc(c.id)}">${started ? 'Restart' : 'Start'}</button>
        <button class="btn small ghost" data-course-done="${esc(c.id)}">Mark complete</button>` : ''}
      </div>
      <p class="tiny muted mt">${esc(c.linkLabel || '')}</p>
    </div>`;
  };

  const resItem = r => `<div class="res-item">
    <div class="between"><a href="${esc(r.url)}" target="_blank" rel="noopener noreferrer"><b>${esc(r.title)}</b> ↗</a>
      <span class="tag">${esc(RES_TYPE[r.type] || r.type)}</span></div>
    <p class="small muted">${esc(r.blurb || '')}</p>
    <div class="row wrap tight-row"><span class="chip">${esc(r.provider)}</span><span class="chip">~${r.hours} h</span>
      ${r.free ? '<span class="chip good">free</span>' : ''}${r.matchesWeakest ? '<span class="chip warn">start here</span>' : ''}</div>
  </div>`;

  const bySection = id => resources.filter(r => r.section === id);
  const crossCut = resources.filter(r => !SECTION_IDS.includes(r.section));

  el('view').innerHTML = `
    <div class="card primary-card">
      <div class="between">
        <div>
          <h2>${isStudent ? 'Learn - close your gaps with free resources' : 'Learning library'}</h2>
          <p class="muted">${isStudent
            ? `Every course below is free and hosted outside PlaceIQ - links open in a new tab. Your weakest section right now is
               <b>${esc(secName(weakest))}</b>; the accuracy target is ${target}%.`
            : 'Free, external resources you can assign to a student. Use the question bank to follow up on what they learn here.'}</p>
        </div>
        <div class="ring-wrap small">
          <svg class="ring" viewBox="0 0 132 132" role="img" aria-label="Courses completed">
            <circle class="track" cx="66" cy="66" r="58" fill="none" stroke-width="10"></circle>
            <circle class="val" cx="66" cy="66" r="58" fill="none" stroke-width="10" stroke-linecap="round"
              transform="rotate(-90 66 66)"></circle>
            <text class="ring-value" x="66" y="66" text-anchor="middle" dominant-baseline="middle"><tspan id="learnRingNum">0</tspan></text>
            <text class="ring-sub" x="66" y="86" text-anchor="middle">courses done</text>
          </svg>
        </div>
      </div>
      <div class="row wrap tight-row mt">
        ${sections.map((x, i) => `<span class="chip ${x.id === weakest ? 'warn' : ''}" title="${x.accuracy == null ? 'not measured yet' : x.accuracy + '% average accuracy'}">
          ${i === 0 && x.accuracy != null ? '1. ' : ''}${esc(x.name)} ${x.accuracy == null ? '—' : x.accuracy + '%'}</span>`).join('')}
      </div>
      <p class="tiny muted mt">Weakest section first. Every free resource below is grouped the same way.</p>
    </div>

    <div class="card">
      <h3>Recommended for you</h3>
      <p class="muted small">Picked because these courses map to the skills where you are losing the most marks.</p>
      <div class="grid g2 mt">${recommended.map(courseCard).join('') || '<p class="muted">Take an assessment and your recommendations appear here.</p>'}</div>
    </div>

    ${weakest && bySection(weakest).length ? `<div class="card">
      <h3>Start here - ${esc(secName(weakest))}</h3>
      <p class="muted small">Free material for your weakest section, easiest first.</p>
      <div class="grid g2 mt">${bySection(weakest).map(resItem).join('')}</div>
    </div>` : ''}

    <div class="card">
      <h3>Free learning library</h3>
      <p class="muted small">Free courses, videos, practice sets and timed tests - no paid links, nothing hosted by PlaceIQ.</p>
      ${SECTION_IDS.map(id => {
        const list = bySection(id);
        if (!list.length || id === weakest) return '';
        return `<h4 class="mt">${esc(secName(id))}${sections.find(x => x.id === id) && sections.find(x => x.id === id).accuracy != null
          ? ` <span class="muted small">· ${sections.find(x => x.id === id).accuracy}% accuracy</span>` : ''}</h4>
          <div class="grid g2 mt">${list.map(resItem).join('')}</div>`;
      }).join('')}
      ${crossCut.length ? `<h4 class="mt">Interview, core CS and company prep</h4>
        <div class="grid g2 mt">${crossCut.map(resItem).join('')}</div>` : ''}
    </div>

    <div class="card">
      <h3>Full catalogue</h3>
      <p class="muted small">All ${courses.length} courses in the catalogue, including the ones outside your current gap list.</p>
      <div class="grid g2 mt">${others.map(courseCard).join('')}</div>
    </div>`;

  if (isStudent) {
    el('view').querySelectorAll('[data-course]').forEach(b => b.onclick = () => setCourse(b.dataset.course, 'in_progress', 30));
    el('view').querySelectorAll('[data-course-done]').forEach(b => b.onclick = () => setCourse(b.dataset.course, 'completed', 100));
  }
  const ring = el('view').querySelector('.ring');
  if (ring) M.ring(ring, courses.length ? (d.coursesCompleted / courses.length) * 100 : 0, { dur: 700 });
  const ringNum = document.getElementById('learnRingNum');
  if (ringNum) ringNum.textContent = d.coursesCompleted + '/' + courses.length;
  M.stagger(el('view'), '.card', { step: 50 });
}

/* ---------------- gaps & courses ---------------- */
async function renderGaps() {
  const data = await api('/student/gaps');
  const recs = data.recommendations || [];
  el('view').innerHTML = `
    <div class="card"><h2>Skill-gap analysis</h2>
      <p class="muted">Gaps are ranked by impact x deficit. Accuracy target is 75% (configurable).</p>
      <div class="scroll-x mt"><table><thead><tr><th>Skill</th><th>Section</th><th>Accuracy</th><th>Deficit</th><th>Impact</th><th>Severity</th></tr></thead>
      <tbody>${(data.gaps || []).map(g => `<tr><td><b>${esc(g.skill)}</b></td><td class="muted small">${esc(g.section)}</td>
        <td>${Math.round(g.accuracy)}%</td><td>${g.deficit}%</td><td>${g.impact}</td>
        <td><span class="tag ${g.severity > 30 ? 'bad' : 'warn'}">${g.severity}</span></td></tr>`).join('') || '<tr><td colspan="6" class="muted">No gaps yet</td></tr>'}</tbody></table></div></div>
    <div class="card"><h3>Recommended courses and learning paths</h3>
      <p class="muted small">Each recommendation names the gap it closes. Mark progress to re-check the skill afterwards.</p>
      <div class="grid g2 mt">${recs.map(r => `<div class="card tight">
        <div class="between"><strong>${esc(r.title)}</strong><span class="tag">#${r.rank} · ${esc(r.level)}</span></div>
        <p class="small muted">${esc(r.description || '')}</p>
        <p class="small"><b>Why this:</b> ${esc(r.whyThis)}</p>
        <div class="row"><span class="tag">${r.hours} h</span><span class="tag">match ${esc(String(r.difficultyMatch))}</span><span class="tag">${esc(r.status)}</span></div>
        <div class="row mt"><button class="btn small" data-inprogress="${esc(r.courseId)}">In progress</button>
          <button class="btn small primary" data-complete="${esc(r.courseId)}">Completed</button>
          <a class="btn small ghost" href="${esc(r.url)}" target="_blank" rel="noopener">Open</a></div>
        <ul class="clean small muted mt">${(r.closesGaps || []).map(g => `<li>closes: ${esc(g.skill)} (accuracy ${Math.round(g.accuracy)}%)</li>`).join('')}</ul>
      </div>`).join('') || '<p class="muted">No recommendations yet - take an assessment.</p>'}</div></div>`;
  el('view').querySelectorAll('[data-inprogress]').forEach(b => b.onclick = () => setCourse(b.dataset.inprogress, 'in_progress', 40));
  el('view').querySelectorAll('[data-complete]').forEach(b => b.onclick = () => setCourse(b.dataset.complete, 'completed', 100));
}

/* ---------------- planner ---------------- */
async function renderPlanner() {
  let plan = await api('/student/planner');
  if (!plan || plan.error) {
    el('view').innerHTML = `<div class="card"><h2>Create your preparation planner</h2>
      <p class="muted">The planner breaks your readiness goal into weekly tasks, each mapped to a gap, a company skill or a milestone mock.</p>
      <div class="grid g2 mt">
        <label>Target company<input id="pCompany" value="${esc(state.user.targetCompany || 'TCS')}"></label>
        <label>Target role<input id="pRole" value="${esc(state.user.targetRole || 'Software Engineer')}"></label>
        <label>Target date<input type="date" id="pDate" value="${new Date(Date.now() + 56 * 86400000).toISOString().slice(0, 10)}"></label>
      </div>
      <button class="btn primary mt" id="makePlan">Generate plan</button></div>`;
    el('makePlan').onclick = async () => {
      await api('/student/planner', { method: 'POST', body: { targetCompany: el('pCompany').value, targetRole: el('pRole').value, targetDate: el('pDate').value } });
      toast('Plan created'); go('planner');
    };
    return;
  }
  el('view').innerHTML = `
    <div class="card"><div class="between">
        <div><h2>${esc(plan.title)}</h2><p class="muted small">${esc(plan.targetRole)} · target date ${esc(plan.targetDate)} · ${plan.daysToTarget} days to go</p></div>
        <div class="kpi"><span class="value">${plan.completionPct}%</span><span class="label">${plan.completedTasks}/${plan.totalTasks} tasks done</span></div>
      </div>
      <p class="muted small">The plan re-plans itself: overdue tasks are carried into the current week and new assessment gaps add fresh tasks automatically.</p>
      ${(plan.weeks || []).some(w => (w.tasks || []).some(t => t.overdue)) ? `<div class="replan-banner mt">
        <span aria-hidden="true">⚠️</span>
        <div><b>You are behind on ${(plan.weeks || []).flatMap(w => w.tasks || []).filter(t => t.overdue).length} task(s).</b>
        <div class="small">Opening the planner pushes them into this week and adds any brand-new gap from your last assessment.</div></div>
        <button class="btn small right" id="replanBtn">Re-plan now</button></div>` : ''}
    </div>
    <div class="grid g2">${(plan.weeks || []).map(w => `
      <div class="week ${w.current ? 'current' : ''}">
        <div class="between">
          <div class="ring-wrap">
            <svg class="ring" ${w.current ? 'id="weeklyRing"' : ''} data-value="${w.completionPct}" style="width:76px;height:76px" viewBox="0 0 132 132" role="img"
              aria-label="Week ${w.weekNo + 1} completion ${w.completionPct} percent">
              <circle class="track" style="stroke-width:13"></circle><circle class="val" style="stroke-width:13" transform="rotate(-90 66 66)"></circle>
              <text class="ring-value" x="66" y="72" text-anchor="middle" font-size="34">${w.completionPct}%</text>
            </svg>
            <div><b>${esc(w.label)}</b><div class="small muted">${w.completed}/${w.total} tasks · ${w.plannedHours} h planned</div></div>
          </div>
        </div>
        ${(w.tasks || []).map(t => `<div class="task ${t.status === 'done' ? 'done' : ''} ${t.carriedOver ? 'moved' : ''}" data-due="${esc(t.dueDate)}">
          <input type="checkbox" data-task="${t.id}" ${t.status === 'done' ? 'checked' : ''}>
          <div><span class="t-title">${esc(t.title)}</span>
            <div class="small muted">${esc(t.type)} · due ${esc(t.dueDate)} · ${t.hours} h
              ${t.carriedOver ? '<span class="tag warn">carried over</span>' : ''}${t.overdue ? '<span class="overdue"> · overdue</span>' : ''}</div></div>
          <span class="tag">${esc(t.skillId || t.refId || '')}</span></div>`).join('')}
      </div>`).join('')}</div>`;
  el('view').querySelectorAll('.week .ring').forEach(r => { if (r.dataset.value !== undefined) M.ring(r, Number(r.dataset.value)); });
  el('view').querySelectorAll('[data-task]').forEach(c => c.onchange = async () => {
    const row = c.closest('.task');
    await api('/student/planner/task/' + c.dataset.task, { method: 'POST', body: { status: c.checked ? 'done' : 'pending' } });
    if (c.checked) {
      row.classList.add('done');                                  // tick + strike-through animate in CSS
      const ring = el('weeklyRing');
      if (ring) { M.nudge(ring); }
      const day = row.closest('.day-group');
      const allDone = day ? [...day.querySelectorAll('input[type=checkbox]')].every(x => x.checked) : false;
      Sound.play(allDone ? 'dayComplete' : 'taskCheck');
      toast(allDone ? 'Whole day cleared - nice work' : 'Task completed (+15 XP)');
      if (allDone && day) M.celebrate(row, { count: 12 });
      setTimeout(() => renderPlanner(), 1000);
    } else { row.classList.remove('done'); toast('Task reopened'); setTimeout(() => renderPlanner(), 500); }
  });
  const rp = el('replanBtn');
  if (rp) rp.onclick = () => { Sound.play('taskCheck'); toast('Re-planning: overdue tasks moved into this week'); go('planner'); };
}

/* ---------------- timed mock tests ---------------- */
async function renderMockHome() {
  const [patterns, history] = await Promise.all([api('/student/mock/patterns'), api('/student/mock/history')]);
  el('view').innerHTML = `
    <div class="card"><h2>Timed mock tests</h2>
      <p class="muted">Company-pattern papers with section timings, negative marking and post-test review with per-question time analysis.
      The test auto-submits when the timer ends.</p></div>
    <div class="grid g2">${patterns.map(p => `<div class="card tight">
      <div class="between"><strong>${esc(p.name)}</strong><span class="tag">${esc(p.difficulty)}</span></div>
      <p class="small muted">${esc(p.instructions)}</p>
      <div class="row small"><span class="tag">${p.durationMin} min</span><span class="tag">cut-off ${p.cutoffPct}%</span>
        <span class="tag">-${p.negativeMarking} per wrong</span>
        ${p.sections.map(s => `<span class="tag">${esc(s.section)}: ${s.questions}q/${s.minutes}m</span>`).join('')}</div>
      <button class="btn primary mt" data-mock="${esc(p.company)}">Start ${esc(p.name)} test</button></div>`).join('')}</div>
    <div class="card"><h3>Past mock tests</h3><div class="scroll-x"><table>
      <thead><tr><th>Company</th><th>Taken</th><th>Score</th><th>Cut-off</th><th></th></tr></thead>
      <tbody>${(history || []).map(m => `<tr><td>${esc(m.company)}</td><td>${esc((m.submittedAt || m.createdAt || '').slice(0, 10))}</td>
        <td><b>${pct(m.scorePct)}</b></td><td>${m.cutoffCleared ? '<span class="tag good">cleared</span>' : '<span class="tag bad">below</span>'}</td>
        <td><button class="btn small" data-review="${m.id}">Review</button></td></tr>`).join('') || '<tr><td colspan="5" class="muted">No mock tests yet</td></tr>'}</tbody></table></div></div>`;
  el('view').querySelectorAll('[data-mock]').forEach(b => b.onclick = () => startMock(b.dataset.mock));
  el('view').querySelectorAll('[data-review]').forEach(b => b.onclick = async () => {
    const rows = await api('/student/mock/' + b.dataset.review + '/review');
    openModal(`<h2>Mock test review</h2><p class="muted small">Per-question time analysis helps you spot where the clock went.</p>
      <div class="scroll-x"><table><thead><tr><th>Section</th><th>Topic</th><th>Result</th><th>Time</th><th>Question</th></tr></thead>
      <tbody>${rows.map(r => `<tr><td>${esc(r.section)}</td><td class="small">${esc(r.topic || '')}</td>
        <td>${r.correct ? '✅' : '❌'}</td><td>${Math.round(r.timeSec)}s</td><td class="small">${esc((r.prompt || '').slice(0, 60))}</td></tr>`).join('')}</tbody></table></div>`);
  });
}
async function startMock(company) {
  Sound.setTestMode(true);          // no right/wrong sounds anywhere inside a timed test
  const out = await api('/student/mock/start', { method: 'POST', body: { company } });
  if (out.error) return toast(out.error);
  state.mock = { id: out.mockId, pattern: out.pattern, questions: out.questions || [], startedAt: Date.now(), deadline: Date.now() + out.timeLimitSec * 1000, answers: {}, times: {} };
  renderMockTest();
}
function renderMockTest() {
  const m = state.mock;
  const qs = m.questions;
  const body = qs.map((q, i) => {
    const type = q.type;
    const opts = (q.options || []).map((o, j) => `<label class="opt" data-mock-opt>
        <input type="${type === 'multi' ? 'checkbox' : 'radio'}" name="q${i}" value="${String.fromCharCode(65 + j)}"><span><b>${String.fromCharCode(65 + j)}</b> ${esc(o)}</span></label>`).join('');
    return `<div class="card tight" data-qwrap="${i}">
      <div class="qmeta"><span class="chip">Q${i + 1}</span><span class="tag">${esc(q.section)}</span><span class="tag">${esc(q.difficulty)}</span>
        <span class="small muted">${esc(q.topic || '')}</span></div>
      <p class="mt"><b>${esc(q.prompt)}</b></p>
      ${opts || (type === 'short' ? `<input data-mock-text="${i}" placeholder="Answer">` : `<textarea data-mock-text="${i}" rows="4" placeholder="Answer"></textarea>`)}
    </div>`;
  }).join('');
  el('view').innerHTML = `
    <div class="card"><div class="between">
      <div><h2>${esc(m.pattern.name)} timed test</h2><p class="muted small">${qs.length} questions · negative marking ${m.pattern.negativeMarking} · cut-off ${m.pattern.cutoffPct}%</p></div>
      <div class="timer" id="mockTimer">--:--</div></div>
      <p class="tag warn mt">Anti-cheating: leaving this tab is logged. The test auto-submits at 00:00.</p></div>
    <div class="grid g2">${body}</div>
    <div class="row"><button class="btn primary" id="submitMock">Submit test</button></div>`;
  let warned5 = false, warned1 = false;
  const tick = () => {
    const left = Math.max(0, Math.round((m.deadline - Date.now()) / 1000));
    if (left <= 300 && !warned5) { warned5 = true; Sound.play('timerWarn'); toast('5 minutes left'); }
    if (left <= 60 && !warned1) { warned1 = true; Sound.play('timerWarn'); toast('1 minute left'); }
    const t = el('mockTimer');
    if (t) { t.textContent = `${String(Math.floor(left / 60)).padStart(2, '0')}:${String(left % 60).padStart(2, '0')}`; t.classList.toggle('low', left < 60); }
    if (left <= 0) { clearInterval(state.timer); submitMock(true); }
  };
  tick(); clearInterval(state.timer); state.timer = setInterval(tick, 1000);
  el('view').querySelectorAll('[data-mock-opt]').forEach(l => l.onclick = () => {
    const input = l.querySelector('input');
    if (input.type === 'radio') {
      const wrap = l.closest('[data-qwrap]');
      wrap.querySelectorAll('.opt').forEach(x => x.classList.remove('sel'));
    }
    setTimeout(() => l.classList.toggle('sel', input.checked), 0);
  });
  el('submitMock').onclick = () => submitMock(false);
}
async function submitMock(auto) {
  clearInterval(state.timer);
  const m = state.mock;
  const answers = m.questions.map((q, i) => {
    const wrap = el('view').querySelector(`[data-qwrap="${i}"]`);
    const checked = wrap ? [...wrap.querySelectorAll('input:checked')].map(x => x.value) : [];
    const text = wrap?.querySelector('[data-mock-text]')?.value;
    return { questionId: q.id, answer: checked.length ? checked : (text ? [text] : []), timeSec: Math.round((Date.now() - m.startedAt) / 1000 / m.questions.length) };
  });
  const res = await api('/student/mock/' + m.id + '/submit', { method: 'POST', body: { answers } });
  state.mock = null;
  Sound.setTestMode(false);
  Sound.play('submitConfirm');
  if (auto) toast('Time up - the test was submitted automatically');
  el('viewTitle').textContent = 'Mock test result';
  el('view').innerHTML = `
    <div class="card"><div class="between"><h2>${pct(res.scorePct)}</h2>
      <div>${res.cleared ? '<span class="tag good">cleared the ' + res.cutoffPct + '% cut-off</span>' : '<span class="tag bad">below the ' + res.cutoffPct + '% cut-off</span>'}</div></div>
      <p class="muted small">Accuracy ${pct((res.accuracy || 0) * 100)} across ${res.review.length} questions. Readiness is now ${Math.round(res.prs?.score || 0)}/100.</p>
      <div class="grid g3 mt">${(res.sections || []).map(s => `<div class="card tight"><span class="label small muted">${esc(s.name || s.section)}</span>
        <div class="kpi"><span class="value">${pct(s.percentage)}</span><span class="small muted">${Math.round(s.avgTimeSec || 0)}s avg</span></div></div>`).join('')}</div></div>
    <div class="card"><h3>Post-test review</h3>
      <p class="small muted">Rows enter in sequence; questions you spent the most time on are highlighted, because time sinks cost marks in real tests.</p>
      <div class="scroll-x"><table id="mockReview">
      <thead><tr><th>Section</th><th>Topic</th><th>Result</th><th>Marks</th><th>Time</th><th>Explanation</th></tr></thead>
      <tbody>${res.review.map(r => `<tr><td>${esc(r.section)}</td><td class="small">${esc(r.topic || '')}</td>
        <td>${r.correct ? '✅' : '❌'}</td><td>${r.awarded}</td><td>${Math.round(r.timeSec)}s</td>
        <td class="small muted">${esc((r.explanation || '').slice(0, 90))}</td></tr>`).join('')}</tbody></table></div></div>
    <div class="row"><button class="btn primary" data-go="dashboard">Back to dashboard</button>
      <button class="btn" data-go="mock">Another test</button></div>`;
  /* review rows stagger in; the slowest questions are highlighted (time sinks cost marks) */
  const rows = [...el('view').querySelectorAll('#mockReview tbody tr')];
  const times = rows.map(r => Number(((r.querySelector('td:nth-child(5)') || {}).textContent || '0').replace('s', '')));
  const sorted = times.slice().sort((a, b) => b - a);
  const cut = sorted[2] || 0;
  rows.forEach((r, i) => {
    r.classList.add('enter');
    r.style.animationDelay = Math.min(i, 8) * 50 + 'ms';
    requestAnimationFrame(() => r.classList.add('in'));
    if (cut > 0 && times[i] >= cut) r.style.background = 'var(--warning-50)';
  });
  el('view').querySelectorAll('[data-go]').forEach(b => b.onclick = () => go(b.dataset.go));
}

/* ---------------- mock interview ---------------- */
async function renderInterviewHome() {
  const history = await api('/student/interviews');
  el('view').innerHTML = `
    <div class="card"><h2>Mock interview practice</h2>
      <p class="muted">Questions come from the company bank; answers are scored on relevance, structure, clarity and depth with feedback and a model answer.
      Works fully offline with a deterministic rubric; set <code>PLACEIQ_AI_KEY</code> to use a hosted model instead.</p>
      <div class="row mt">
        <select id="mode"><option value="hr">HR round</option><option value="technical">Technical round</option><option value="behavioral">Behavioural (STAR)</option></select>
        <input id="iCompany" value="${esc(state.user.targetCompany || 'TCS')}" placeholder="Company">
        <button class="btn primary" id="startInterview">Start interview</button>
      </div></div>
    <div class="card"><h3>Past interviews</h3>
      ${(history || []).map(h => `<div class="check"><span class="tag">${esc(h.mode)}</span>
        <div><b>${esc(h.company)} · ${Math.round(h.overallPct || 0)}%</b>
        <p class="small muted">${esc(h.summary || '')}</p>
        <button class="btn small ghost" data-interview="${h.id}">View answers</button></div></div>`).join('') || '<p class="muted small">No interviews yet.</p>'}</div>`;
  el('startInterview').onclick = async () => {
    const out = await api('/student/interview/start', { method: 'POST', body: { mode: el('mode').value, company: el('iCompany').value } });
    state.interview = { id: out.interviewId, round: 0, max: 3, mode: el('mode').value, company: out.company, question: out.question, provider: out.provider };
    renderInterviewQuestion();
  };
  el('view').querySelectorAll('[data-interview]').forEach(b => b.onclick = () => {
    const h = history.find(x => String(x.id) === b.dataset.interview);
    openModal(`<h2>${esc(h.mode)} interview at ${esc(h.company)}</h2><p class="muted small">${esc(h.summary || '')}</p>
      ${(h.answers || []).map(a => `<div class="card tight mt"><b>${esc(a.question)}</b>
        <p class="small"><b>Your answer:</b> ${esc(a.answer)}</p>
        <div class="row"><span class="tag">relevance ${Math.round(a.relevance)}</span><span class="tag">structure ${Math.round(a.structure)}</span>
          <span class="tag">clarity ${Math.round(a.clarity)}</span><span class="tag">depth ${Math.round(a.depth)}</span></div>
        <ul class="clean small mt">${(a.feedback || []).map(f => `<li>• ${esc(f)}</li>`).join('')}</ul>
        <details class="small"><summary>Model answer</summary>${esc(a.modelAnswer || '')}</details></div>`).join('')}`);
  });
}
function renderInterviewQuestion() {
  const s = state.interview;
  el('view').innerHTML = `
    <div class="card">
      <div class="qmeta"><span class="chip">Question ${s.round + 1} of ${s.max}</span>
        <span class="tag">${esc(s.mode)}</span><span class="tag">${esc(s.provider || 'offline-rubric')}</span></div>
      <h2 class="mt" id="ivQuestion" aria-live="polite"></h2>
      <textarea id="ivAnswer" rows="10" placeholder="Answer out loud, then type the same answer here. Aim for 60-200 words with one concrete example."></textarea>
      <p class="muted small">Scored on relevance (does it answer), structure (STAR/PREP), clarity and depth (numbers, trade-offs).</p>
      <div class="row"><button class="btn primary" id="evaluate">Evaluate answer</button>
        <button class="btn ghost" id="finishIv">Finish interview</button></div>
      <div id="ivFeedback" class="mt"></div>
    </div>`;
  M.typing(el('ivQuestion'), s.question.prompt, { cps: 55 });      // question types in line by line
  el('evaluate').onclick = async () => {
    const fb = el('ivFeedback');
    fb.innerHTML = `<div class="card tight"><span class="muted small">Scoring your answer</span>
      <span class="dots" aria-hidden="true"><i></i><i></i><i></i></span></div>`;   // soft "thinking" indicator
    const [res] = await Promise.all([
      api('/student/interview/answer', { method: 'POST', body: {
        interviewId: s.id, question: s.question.prompt, answer: el('ivAnswer').value, mode: s.mode, rubric: s.question.rubric } }),
      new Promise(r => setTimeout(r, M.reduced() ? 0 : 600))
    ]);
    Sound.play('notify');                                        // feedback-ready ping
    const bars = [['Relevance', res.relevance], ['Structure', res.structure], ['Clarity', res.clarity], ['Depth', res.depth]];
    fb.innerHTML = `<div class="card tight">
      ${bars.map(([name, val], i) => `<div class="rubric-bar">
        <div class="between"><span class="small">${name}</span><b class="small">${Math.round(val)}%</b></div>
        <div class="bar"><i data-w="${Math.round(val)}" style="transition-delay:${i * 120}ms"></i></div></div>`).join('')}
      <ul class="clean small mt">${(res.feedback || []).map(f => `<li>• ${esc(f)}</li>`).join('')}</ul>
      <p class="summary">Overall ${Math.round((res.relevance + res.structure + res.clarity + res.depth) / 4)}% across the four rubric dimensions, ${res.words} words.</p>
      ${res.modelAnswer ? `<details class="small"><summary>Model answer</summary>${esc(res.modelAnswer)}</details>` : ''}</div>`;
    requestAnimationFrame(() => fb.querySelectorAll('.bar > i').forEach(i => { i.style.width = i.dataset.w + '%'; }));
  };
  el('finishIv').onclick = async () => {
    const out = await api('/student/interview/' + s.id + '/finish', { method: 'POST' });
    state.interview = null;
    el('viewTitle').textContent = 'Interview feedback';
    el('view').innerHTML = `<div class="card"><h2>Overall <span id="ivOverall">0</span>%</h2>
      <p class="muted small">+40 XP earned. Your PRS interview component uses this score.</p>
      ${out.answers.map(a => `<div class="card tight mt"><b>${esc(a.question)}</b>
        <div class="row"><span class="tag">relevance ${Math.round(a.relevance)}</span><span class="tag">structure ${Math.round(a.structure)}</span>
          <span class="tag">clarity ${Math.round(a.clarity)}</span><span class="tag">depth ${Math.round(a.depth)}</span></div>
        <ul class="clean small mt">${(a.feedback || []).map(f => `<li>• ${esc(f)}</li>`).join('')}</ul></div>`).join('')}
      <div class="row mt"><button class="btn primary" data-go="dashboard">Dashboard</button><button class="btn" data-go="interview">New interview</button></div></div>`;
    M.countUp(el('ivOverall'), out.overallPct || 0, { suffix: '', dur: 900 });
    M.stagger(el('view'), '.card', { step: 50 });
    el('view').querySelectorAll('[data-go]').forEach(b => b.onclick = () => go(b.dataset.go));
  };
}

/* ---------------- resume analyzer + checklist ---------------- */
async function renderResume() {
  const checklist = await api('/student/checklist');
  const items = checklist.items || [];
  const doneCount = items.filter(i => i.done).length;
  el('view').innerHTML = `
    <div class="split">
      <div class="card">
        <h2>Resume / ATS analyzer</h2>
        <p class="muted">Upload a .pdf or .docx (parsed in the backend with the JDK only) or paste the text. You get an ATS score, matched and missing keywords, and specific fixes.</p>
        <label class="mt">Target role<input id="rRole" value="${esc(state.user.targetRole || 'Software Engineer')}"></label>
        <div class="dropzone mt" id="dropzone" tabindex="0" role="button" aria-label="Upload resume file">
          <span style="font-size:var(--t28)" aria-hidden="true">📄</span>
          <p><b>Drag a .pdf or .docx here</b></p>
          <p class="small">or click to browse. Scanned PDFs cannot be parsed - paste the text below instead.</p>
          <input type="file" id="rFile" accept=".pdf,.docx,.txt" class="hide">
        </div>
        <label class="mt">Or paste resume text<textarea id="rText" rows="8" placeholder="Paste your resume text here..."></textarea></label>
        <button class="btn primary mt" id="analyzeBtn">Analyze resume</button>
        <div id="resumeOut" class="mt"></div>
      </div>
      <div class="card">
        <h3>Profile checklist</h3>
        <p class="muted small">${doneCount}/${items.length} complete</p>
        <div class="bar mb"><i style="width:${items.length ? Math.round((doneCount / items.length) * 100) : 0}%"></i></div>
        <div class="stack">${items.map(i => `<label class="check"><input type="checkbox" data-check="${esc(i.key)}" ${i.done ? 'checked' : ''}><span>${esc(i.label)}</span></label>`).join('')}</div>
      </div>
    </div>`;
  el('analyzeBtn').onclick = async () => {
    const file = el('rFile').files[0];
    const body = { targetRole: el('rRole').value, text: el('rText').value };
    if (file) {
      body.filename = file.name;
      const buf = await file.arrayBuffer();
      body.contentBase64 = btoa(String.fromCharCode(...new Uint8Array(buf)));
    }
    if (!file && !body.text) return toast('Upload a file or paste the text');
    el('resumeOut').innerHTML = `<div class="card tight"><span class="muted small">Parsing and scoring your resume</span>
      <div class="bar mt"><i id="analyzeBar" style="width:8%"></i></div></div>`;
    requestAnimationFrame(() => { const b = el('analyzeBar'); if (b) b.style.width = '72%'; });
    const r = await api('/student/resume/analyze', { method: 'POST', body });
    el('resumeOut').innerHTML = `<div class="card tight">
      <div class="ring-wrap">
        <svg class="ring" viewBox="0 0 132 132" role="img" aria-label="ATS score ${r.atsScore} out of 100">
          <circle class="track"></circle><circle class="val" transform="rotate(-90 66 66)"></circle>
          <text class="ring-value" x="66" y="66" text-anchor="middle" dominant-baseline="middle"><tspan id="atsNum">0</tspan></text>
          <text class="ring-sub" x="66" y="86" text-anchor="middle">ATS score</text>
        </svg>
        <div><h3>${r.atsScore >= 75 ? 'Strong pass' : r.atsScore >= 50 ? 'Needs tightening' : 'Likely filtered out'}</h3>
        <p class="small muted">Scored on sections, contact details, role keywords, action verbs, quantified bullets and length.</p></div>
      </div>
      <div class="grid g3 small">
        <div>Keywords: <b>${Math.round(r.keywordCoverage * 100)}%</b></div>
        <div>Quantified bullets: <b>${r.quantifiedBullets}</b></div>
        <div>Action-verb ratio: <b>${Math.round(r.actionVerbRatio * 100)}%</b></div>
        <div>Pages: <b>${r.pages}</b></div>
        <div>LinkedIn: <b>${r.linkedin ? 'yes' : 'missing'}</b></div>
        <div>GitHub: <b>${r.github ? 'yes' : 'missing'}</b></div>
      </div>
      <p class="small mt"><b>Matched:</b> ${esc((r.matchedKeywords || []).join(', ') || 'none')}</p>
      <p class="small"><b>Missing keywords:</b> ${esc((r.missingKeywords || []).join(', ') || 'none')}</p>
      <p class="small"><b>Missing sections:</b> ${esc((r.missingSections || []).join(', ') || 'none')}</p>
      <h4 class="mt">Fix these next</h4>
      <ul class="clean small" id="atsFixes">${(r.suggestions || []).map(s => `<li class="enter">• ${esc(s)}</li>`).join('')}</ul>
      <p class="summary">${esc(r.pages)} page(s), ${esc(r.words)} words, ${Math.round((r.keywordCoverage || 0) * 100)}% of the role keywords present.</p></div>`;
    M.ring(el('resumeOut').querySelector('.ring'), r.atsScore || 0, { dur: 900, color: Charts.lvl(r.atsScore || 0) });
    M.countUp(el('atsNum'), r.atsScore || 0, { dur: 900 });
    M.stagger(el('resumeOut'), '#atsFixes li', { step: 60 });
    Sound.play('notify');                                  // analysis-finished ping
    loadNotifications();
  };
  const dz = el('dropzone'), fileInput = el('rFile');
  dz.onclick = () => fileInput.click();
  dz.onkeydown = (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); fileInput.click(); } };
  ['dragenter', 'dragover'].forEach(ev => dz.addEventListener(ev, e => { e.preventDefault(); dz.classList.add('over'); }));
  ['dragleave', 'drop'].forEach(ev => dz.addEventListener(ev, e => { e.preventDefault(); dz.classList.remove('over'); }));
  dz.addEventListener('drop', e => { if (e.dataTransfer.files[0]) { fileInput.files = e.dataTransfer.files; el('analyzeBtn').click(); } });
  fileInput.onchange = () => { if (fileInput.files[0]) dz.querySelector('p b').textContent = fileInput.files[0].name; };
  el('view').querySelectorAll('[data-check]').forEach(c => c.onchange = async () => {
    await api('/student/checklist', { method: 'POST', body: { key: c.dataset.check, done: c.checked } });
    Sound.play('taskCheck');
    if (c.checked) M.celebrate(c.closest('.check'), { count: 10 });
    setTimeout(() => renderResume(), 700);
  });
}

/* ---------------- companies ---------------- */
async function renderCompanies() {
  const companies = await api('/meta/companies');
  el('view').innerHTML = `
    <div class="card"><h2>Company intelligence</h2>
      <p class="muted">Hiring rounds, question patterns, emphasized skills, eligibility and a fit analysis against your current profile.
      Data lives in <code>data/companies/*.json</code> and can be updated from the admin import screen.</p>
      <div class="row mt"><input id="coSearch" placeholder="Search company, role or skill" style="max-width:320px"></div></div>
    <div class="grid g2" id="coGrid"></div>`;
  const draw = (list) => {
    el('coGrid').innerHTML = list.map(c => `<div class="card tight lift" data-card="${esc(c.id)}">
      <div class="between"><strong>${esc(c.name)}</strong><span class="tag ${c.difficulty === 'Very Hard' ? 'bad' : c.difficulty === 'Hard' ? 'warn' : 'good'}">${esc(c.difficulty)}</span></div>
      <p class="small muted">${esc(c.fullName || '')} · hiring: ${esc(c.hiringSeason || '-')}</p>
      <p class="small"><b>Roles:</b> ${esc((c.roles || []).join(' · '))}</p>
      <p class="small"><b>Rounds:</b> ${(c.rounds || []).map(r => esc(r.name)).join(' → ')}</p>
      <div class="row small">${Object.entries(c.skills || {}).map(([k, v]) => `<span class="tag">${esc(k)} ${Math.round(v * 100)}%</span>`).join('')}</div>
      <div class="row mt"><button class="btn small primary" data-company="${esc(c.id)}">Fit analysis</button>
        <button class="btn small" data-detail="${esc(c.id)}">Full pattern</button>
        <button class="btn small ghost" data-mockco="${esc(c.id)}">Practice test</button></div></div>`).join('');
    M.stagger(el('coGrid'), '.card', { step: 45 });          // grid reflows with a 45ms stagger
    el('coGrid').querySelectorAll('[data-company]').forEach(b => b.onclick = () => fitAnalysis(b.dataset.company, b.closest('.card')));
    el('coGrid').querySelectorAll('[data-detail]').forEach(b => b.onclick = () => {
      const c = list.find(x => x.id === b.dataset.detail) || companies.find(x => x.id === b.dataset.detail);
      const sourceCard = b.closest('.card');
      api('/meta/companies/' + c.id).then(full => openModal(`
        <h2>${esc(full.name)} · ${esc(full.fullName || '')}</h2>
        <p class="muted small">${esc(full.hiringSeason || '')}</p>
        <h3 class="mt">Rounds</h3>
        <ul class="clean">${(full.rounds || []).map(r => `<li class="card tight"><b>${esc(r.name)}</b>
          <p class="small muted">${esc(r.mode || '')} ${r.cutoff ? '· ' + esc(r.cutoff) : ''}</p><p class="small">${esc(r.detail || '')}</p></li>`).join('')}</ul>
        <h3 class="mt">Aptitude patterns</h3>
        <ul class="clean small">${(full.aptitudePatterns || []).map(p => `<li><b>${esc(p.topic)}</b> (${esc(p.frequency || '')})<br><span class="muted">${esc(p.sample || '')}</span></li>`).join('')}</ul>
        <h3 class="mt">Coding patterns</h3>
        <ul class="clean small">${(full.codingPatterns || []).map(p => `<li><b>${esc(p.topic)}</b><br><span class="muted">${esc(p.sample || '')}</span></li>`).join('')}</ul>
        <h3 class="mt">HR questions</h3>
        <ul class="clean small">${(full.hrQuestions || []).map(q => `<li>• ${esc(q)}</li>`).join('')}</ul>
        <h3 class="mt">Eligibility</h3>
        <p class="small muted">${Object.entries(full.eligibility || {}).map(([k, v]) => `${esc(k)}: ${esc(v)}`).join(' · ')}</p>
        <h3 class="mt">Prep tips</h3>
        <ul class="clean small">${(full.tips || []).map(t => `<li>• ${esc(t)}</li>`).join('')}</ul>
        <div class="tabs-underline mt" id="coTabs"><button data-tab="rounds" class="active">Rounds</button>
          <button data-tab="questions">Questions</button><button data-tab="skills">Skills</button>
          <button data-tab="tips">Tips</button><i style="width:0"></i></div>
        <p class="summary mt">${esc(full.name)} runs ${(full.rounds || []).length} rounds, emphasises ${Object.keys(full.skills || {}).join(', ') || 'general skills'}, and hires around ${esc(full.hiringSeason || '-')}.</p>`,
        { fromEl: sourceCard }));
      initTabs();
    });
    el('coGrid').querySelectorAll('[data-mockco]').forEach(b => b.onclick = () => startMock(b.dataset.mockco));
  };
  draw(companies);
  el('coSearch').oninput = () => {
    const q = el('coSearch').value.toLowerCase();
    draw(companies.filter(c => JSON.stringify(c).toLowerCase().includes(q)));
  };
}
async function fitAnalysis(companyId, fromEl) {
  const fit = await api('/student/company-fit/' + companyId);
  openModal(`<h2>${esc(fit.company)} fit analysis</h2>
    <div class="ring-wrap mt">
      <svg class="ring" viewBox="0 0 132 132" role="img" aria-label="Profile match percentage">
        <circle class="track"></circle><circle class="val" transform="rotate(-90 66 66)"></circle>
        <text class="ring-value" x="66" y="66" text-anchor="middle" dominant-baseline="middle"><tspan id="fitNum">0</tspan></text>
        <text class="ring-sub" x="66" y="86" text-anchor="middle">match</text>
      </svg>
      <div><h3>${esc(fit.role)}</h3>
      <p class="small muted">Difficulty ${esc(fit.difficulty || '')}. Section-by-section comparison below, with the three gaps worth fixing first.</p></div>
    </div>
    <h3>Top gaps to close</h3>
    <ul class="clean">${(fit.topGaps || []).map(g => `<li class="card tight"><div class="between"><b>${esc(g.section)}</b>
      <span class="tag warn">${g.yourScore}% → ${g.target}%</span></div><p class="small muted">${esc(g.tip)}</p></li>`).join('') || '<li class="muted small">No gaps - you match the shadow profile.</li>'}</ul>
    <h3 class="mt">Section comparison</h3>
    <canvas id="fitChart"></canvas>
    <h3 class="mt">Rounds to prepare</h3>
    <ul class="clean small">${(fit.rounds || []).map(r => `<li><b>${esc(r.name)}</b> — ${esc(r.detail || '')}</li>`).join('')}</ul>`, { fromEl });
  M.ring(document.querySelector('.modal-card .ring'), fit.matchPct || 0, { dur: 900 });
  M.countUp(el('fitNum'), fit.matchPct || 0, { suffix: '%', dur: 900 });
  M.stagger(document.querySelector('.modal-card'), '.card', { step: 50 });
  Charts.bars(el('fitChart'), (fit.sections || []).map(s => ({
    label: s.section, value: s.yourScore,
    color: s.yourScore >= s.requiredScore ? getComputedStyle(document.documentElement).getPropertyValue('--lvl-advanced')
      : s.yourScore >= s.requiredScore * 0.7 ? getComputedStyle(document.documentElement).getPropertyValue('--lvl-beginner')
        : getComputedStyle(document.documentElement).getPropertyValue('--danger')
  })));
  document.querySelector('.modal-card').insertAdjacentHTML('beforeend',
    `<p class="summary mt">You match ${fit.matchPct}% of the ${esc(fit.company)} profile. Weakest: ${(fit.topGaps || []).map(g => esc(g.section)).join(', ') || 'none'}.</p>`);
}

/* ---------------- mentor views ---------------- */
async function renderMentor() {
  const students = await api('/mentor/students');
  const cohort = await api('/mentor/cohort');
  el('view').innerHTML = `
    <div class="grid g4">
      <div class="card kpi"><span class="label">Students</span><span class="value">${cohort.students.length}</span></div>
      <div class="card kpi"><span class="label">Average PRS</span><span class="value">${Math.round(cohort.averagePrs)}</span></div>
      <div class="card kpi"><span class="label">Avg improvement</span><span class="value">${cohort.averageImprovementPoints} pts</span></div>
      <div class="card kpi"><span class="label">At risk</span><span class="value" style="color:var(--bad)">${cohort.atRisk}</span></div>
    </div>
    <div class="card"><h3>Students <span class="muted small">(click a column to sort)</span></h3>
      <div class="scroll-x"><table id="mentorTable">
        <thead><tr><th data-sort="name">Student</th><th data-sort="college">College</th><th data-sort="targetCompany">Target</th>
        <th data-sort="prs">PRS</th><th data-sort="improvementPoints">Improvement</th><th data-sort="daysInactive">Last active</th>
        <th class="static">Flags</th><th class="static"></th></tr></thead>
        <tbody></tbody></table></div></div>
    <div class="split">
      <div class="card"><h3>Section-wise weakness</h3><canvas id="secChart"></canvas></div>
      <div class="card"><h3>Readiness distribution</h3><canvas id="distChart"></canvas></div>
    </div>`;
  Charts.bars(el('secChart'), (cohort.sectionAverages || []).map(s => ({ label: s.section, value: s.averagePct })));
  Charts.bars(el('distChart'), (cohort.readinessDistribution || []).map(r => ({ label: r.level, value: (r.count / Math.max(1, cohort.students.length)) * 100 })));
  const rowHtml = (s) => `<td><b>${esc(s.name)}</b><div class="small muted">${esc(s.email)}</div></td>
      <td class="small">${esc(s.college)}</td><td class="small">${esc(s.targetCompany || '-')}</td>
      <td>${Math.round(s.prs)} ${levelTag(s.readiness)}</td>
      <td>${s.improvementPoints >= 0 ? '+' : ''}${s.improvementPoints} pts</td>
      <td>${s.daysInactive}d</td>
      <td>${(s.flags || []).map((f, i) => `<span class="tag bad ${i === 0 ? 'pulse-once' : ''}">${esc(f)}</span>`).join(' ') || '<span class="tag good">on track</span>'}</td>
      <td><button class="btn small" data-student="${s.id}">Open</button></td>`;
  let sortKey = 'prs', asc = true;
  const paint = () => {
    const sorted = students.slice().sort((a, b) => {
      const x = a[sortKey], y = b[sortKey];
      const cmp = typeof x === 'string' ? String(x).localeCompare(String(y)) : (x - y);
      return asc ? cmp : -cmp;
    });
    const body = el('mentorTable').querySelector('tbody');
    body.innerHTML = sorted.map(s => `<tr>${rowHtml(s)}</tr>`).join('');
    M.stagger(body, 'tr', { step: 40 });
    body.querySelectorAll('[data-student]').forEach(b => b.onclick = () => studentDetail(b.dataset.student));
  };
  paint();
  el('mentorTable').querySelectorAll('th[data-sort]').forEach(th => th.onclick = () => {
    if (sortKey === th.dataset.sort) asc = !asc; else { sortKey = th.dataset.sort; asc = true; }
    el('mentorTable').querySelectorAll('th[data-sort]').forEach(x => x.textContent = x.textContent.replace(/ [▲▼]$/, ''));
    th.textContent = th.textContent + ' ' + (asc ? '▲' : '▼');
    Sound.play('tap');
    paint();
  });
}
async function studentDetail(id) {
  const d = await api('/mentor/students/' + id);
  const s = d.student;
  openDrawer(`<h2>${esc(s.name)}</h2>
    <p class="muted small">${esc(s.college)} · ${esc(s.branch)} · target ${esc(s.targetCompany || '-')} / ${esc(s.targetRole || '-')}</p>
    <div class="grid g3 mt">
      <div class="kpi"><span class="label">PRS</span><span class="value">${Math.round(d.prs.score)}</span></div>
      <div class="kpi"><span class="label">Percentile</span><span class="value">${d.cohortComparison.percentile || 0}%</span><span class="small muted">cohort avg ${d.cohortComparison.average || 0}</span></div>
      <div class="kpi"><span class="label">Plan completion</span><span class="value">${d.plan ? d.plan.completionPct : 0}%</span></div>
    </div>
    <canvas id="stuChart" class="mt"></canvas>
    <h3 class="mt">Skill gaps</h3>
    <ul class="clean small">${(d.gaps || []).slice(0, 5).map(g => `<li><b>${esc(g.skill)}</b> — accuracy ${Math.round(g.accuracy)}% (severity ${g.severity})</li>`).join('') || '<li class="muted">none</li>'}</ul>
    <h3 class="mt">Recent events</h3>
    <ul class="clean small muted">${(d.events || []).slice(0, 8).map(e => `<li>${esc(e.type)} · ${esc(e.detail || '')} · ${esc((e.createdAt || '').slice(0, 16))}</li>`).join('') || '<li>none</li>'}</ul>
    <h3 class="mt">Mentor notes</h3>
    <div id="noteList">${(d.notes || []).map(n => `<div class="check"><span class="tag">${esc(n.mentor || 'mentor')}</span><div>${esc(n.note)}<div class="small muted">${esc((n.createdAt || '').slice(0, 16))}</div></div></div>`).join('')}</div>
    <textarea id="newNote" rows="3" class="mt" placeholder="Add a note for this student"></textarea>
    <div class="row mt"><button class="btn primary" id="addNote">Save note</button></div>
    <h3 class="mt">Assign work</h3>
    <div class="row"><input id="taskTitle" placeholder="Task title (e.g. Revise DBMS joins)">
      <select id="taskSection">${(state.meta.sections || []).map(x => `<option value="${esc(x.id)}">${esc(x.name)}</option>`).join('')}</select>
      <input id="taskDue" type="date" value="${new Date(Date.now() + 7 * 86400000).toISOString().slice(0, 10)}"></div>
    <div class="row mt"><button class="btn" id="assignTask">Assign task</button>
      <select id="assignCompany">${(state.meta.companies || []).map(c => `<option value="${esc(c.id)}">${esc(c.name)}</option>`).join('')}</select>
      <button class="btn" id="assignTest">Assign timed test</button></div>`);
  Charts.line(el('stuChart'), (d.attempts || []).map((a, i) => 'A' + (i + 1)),
    [{ name: 'Overall', color: '#4f7cff', data: (d.attempts || []).map(a => a.overallPct) }]);
  el('addNote').onclick = async () => {
    if (!el('newNote').value.trim()) return toast('Write a note first');
    const out = await api(`/mentor/students/${id}/note`, { method: 'POST', body: { note: el('newNote').value } });
    el('noteList').innerHTML = (out.notes || []).map(n => `<div class="check"><span class="tag">${esc(n.mentor || 'mentor')}</span><div>${esc(n.note)}</div></div>`).join('');
    el('newNote').value = '';
    toast('Note saved');
  };
  el('assignTask').onclick = async () => {
    if (!el('taskTitle').value.trim()) return toast('Give the task a title');
    await api(`/mentor/students/${id}/assign-task`, { method: 'POST', body: { title: el('taskTitle').value, type: 'practice', section: el('taskSection').value, dueDate: el('taskDue').value } });
    toast('Task assigned and student notified');
  };
  el('assignTest').onclick = async () => {
    await api(`/mentor/students/${id}/assign-test`, { method: 'POST', body: { company: el('assignCompany').value } });
    toast('Timed test assigned');
  };
}

/* ---------------- admin ---------------- */
async function renderAdmin() {
  const cohort = await api('/admin/cohort');
  el('view').innerHTML = `
    <div class="grid g4">
      <div class="card kpi"><span class="label">Cohort size</span><span class="value" id="kAdm1">0</span></div>
      <div class="card kpi"><span class="label">Average PRS</span><span class="value" id="kAdm2">0</span>
        <span class="small muted">p25 ${cohort.percentileBands.p25} · p50 ${cohort.percentileBands.p50} · p75 ${cohort.percentileBands.p75}</span></div>
      <div class="card kpi"><span class="label">Avg plan completion</span><span class="value" id="kAdm3">0%</span></div>
      <div class="card kpi"><span class="label">Avg improvement</span><span class="value" id="kAdm4">0 pts</span><span class="small muted">first to latest attempt</span></div>
    </div>
    <div class="split">
      <div class="card"><h3>Section-wise averages (weakest first)</h3><canvas id="adminSec"></canvas>
        <p class="small muted mt">${(cohort.sectionAverages || []).filter(s => s.weakest).map(s => esc(s.section)).join(', ') || 'no section below 55%'}</p></div>
      <div class="card"><h3>Readiness distribution</h3><canvas id="adminDist"></canvas></div>
    </div>
    <div class="card" id="secPanel"><h3>Sign-in security</h3><p class="muted small">Loading the audit trail...</p></div>
    <div class="card"><h3>Cohort roster</h3>
      <div class="row mb"><button class="btn small" data-export="/api/admin/export/students">Export students CSV</button>
        <button class="btn small" data-export="/api/admin/export/attempts">Export attempts CSV</button></div>
      <div class="scroll-x"><table>
        <thead><tr><th>Student</th><th>Assessments</th><th>Latest</th><th>Improvement</th><th>PRS</th><th>At risk</th></tr></thead>
        <tbody>${cohort.students.map(s => `<tr><td>${esc(s.name)}<div class="small muted">${esc(s.college)}</div></td>
          <td>${s.assessments}</td><td>${pct(s.latestPct)}</td><td>${s.improvementPoints} pts</td>
          <td>${Math.round(s.prs)} ${levelTag(s.readiness)}</td>
          <td>${(s.flags || []).map(f => `<span class="tag bad">${esc(f)}</span>`).join(' ') || '<span class="tag good">ok</span>'}</td></tr>`).join('')}</tbody></table></div></div>`;
  // sign-in security: locked accounts + the audit trail of failed attempts
  api('/admin/security').then(sec => {
    const host = el('view').querySelector('#secPanel');
    if (!host) return;
    const locked = sec.lockedAccounts || [], events = sec.recentEvents || [];
    host.innerHTML = `
      <div class="between"><h3>Sign-in security</h3>
        <span class="chip ${locked.length ? 'warn' : 'good'}">${locked.length ? locked.length + ' locked' : 'no lockouts'}</span></div>
      <p class="muted small">${esc(sec.policy.hashing)} · lockout: ${esc(sec.policy.lockout)} · sessions expire after
        ${sec.policy.sessionHours} h.</p>
      <div class="grid g2 mt">
        <div><b class="small">Locked accounts</b>
          ${locked.map(l => `<div class="sec-row"><div><b class="small">${esc(l.email)}</b>
              <div class="tiny muted">${l.fails} failed tries · ${l.minutesLeft} min left · ${esc(l.ip || '')}</div></div>
            <button class="btn small" data-unlock="${esc(l.email)}">Unlock</button></div>`).join('')
            || '<p class="muted small mt">Nobody is locked out.</p>'}
        </div>
        <div><b class="small">Recent sign-in activity</b>
          ${events.slice(0, 10).map(e => `<div class="sec-row"><div><b class="small">${esc(e.email || '-')}</b>
              <div class="tiny muted">${esc(e.kind)} · ${esc(e.detail || '')}</div></div>
            <span class="tiny muted">${esc((e.createdAt || '').slice(5, 16))}</span></div>`).join('')
            || '<p class="muted small mt">No attempts recorded yet.</p>'}
        </div>
      </div>`;
    host.querySelectorAll('[data-unlock]').forEach(b => b.onclick = async () => {
      await api('/admin/security/unlock', { method: 'POST', body: { email: b.dataset.unlock } });
      Sound.play('stepComplete');
      toast('Lock cleared for ' + b.dataset.unlock);
      go('admin');
    });
  }).catch(() => { });

  el('view').querySelectorAll('[data-export]').forEach(b => b.onclick = () => {
    // exports are authenticated by the token, so fetch then hand the browser a blob
    toast('Preparing CSV...');
    api(b.dataset.export, { raw: true }).then(csv => {
      const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
      const a = document.createElement('a');
      a.href = url;
      a.download = b.dataset.export.split('/').pop() + '.csv';
      document.body.append(a); a.click(); a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 4000);
      toast('CSV downloaded');
    });
  });
  Charts.bars(el('adminSec'), (cohort.sectionAverages || []).map(s => ({ label: s.section, value: s.averagePct })));
  Charts.bars(el('adminDist'), (cohort.readinessDistribution || []).map(r => ({ label: r.level, value: (r.count / Math.max(1, cohort.students.length)) * 100 })));
  el('adminSec').parentElement.insertAdjacentHTML('beforeend', `<p class="summary">Watch list: ${esc((cohort.sectionAverages || []).filter(x => x.weakest).map(x => x.section).join(', ') || 'no section below 55%')}.</p>`);
  el('adminDist').parentElement.insertAdjacentHTML('beforeend', `<p class="summary">${(cohort.readinessDistribution || []).map(r => `${r.level}: ${r.count}`).join(' · ')}</p>`);
  M.countUp(el('kAdm1'), cohort.students.length, { dur: 700 });
  M.countUp(el('kAdm2'), cohort.averagePrs || 0, { dur: 900 });
  M.countUp(el('kAdm3'), cohort.averagePlanCompletion || 0, { suffix: '%', dur: 900 });
  M.countUp(el('kAdm4'), cohort.averageImprovementPoints || 0, { suffix: ' pts', dur: 900 });
  M.stagger(el('view'), '.card', { step: 50 });
}

/* ---------------- question bank admin ---------------- */
async function renderQuestions() {
  const questions = await api('/admin/questions');
  const sections = state.meta.sections || [];
  el('view').innerHTML = `
    <div class="card"><h2>Question bank</h2>
      <p class="muted">${questions.length} questions loaded from the database (seeded from <code>data/questions.json</code>). Edits here are validated server-side.</p>
      <div class="row mt"><select id="newSection">${sections.map(s => `<option value="${esc(s.id)}">${esc(s.name)}</option>`).join('')}</select>
        <select id="newType"><option>mcq</option><option>multi</option><option>short</option><option>coding</option><option>written</option></select>
        <select id="newDiff"><option>easy</option><option selected>medium</option><option>hard</option></select>
        <input id="newId" placeholder="ID (e.g. Q065)">
        <input id="newTopic" placeholder="Topic">
        <input id="newSkill" placeholder="Skill id (e.g. code.arrays)"></div>
      <textarea id="newPrompt" rows="2" class="mt" placeholder="Question prompt"></textarea>
      <div class="row mt"><input id="newOptions" placeholder="Options separated by | (for MCQ/multi)">
        <input id="newAnswer" placeholder="Answer letters separated by | (e.g. B or A|C)"></div>
      <textarea id="newExplanation" rows="2" class="mt" placeholder="Explanation"></textarea>
      <button class="btn primary mt" id="addQuestion">Add or update question</button>
      <p class="error" id="qError"></p></div>
    <div class="card"><div class="scroll-x"><table>
      <thead><tr><th>ID</th><th>Section</th><th>Topic</th><th>Difficulty</th><th>Type</th><th>Prompt</th><th></th></tr></thead>
      <tbody>${questions.map(q => `<tr><td>${esc(q.id)}</td><td>${esc(q.section)}</td><td class="small">${esc(q.topic || '')}</td>
        <td><span class="tag">${esc(q.difficulty)}</span></td><td class="small">${esc(q.type)}</td>
        <td class="small muted">${esc((q.prompt || '').slice(0, 80))}</td>
        <td><button class="btn small danger" data-del="${esc(q.id)}">Delete</button></td></tr>`).join('')}</tbody></table></div></div>`;
  el('addQuestion').onclick = async () => {
    const body = {
      id: el('newId').value.trim(), section: el('newSection').value, type: el('newType').value, difficulty: el('newDiff').value,
      topic: el('newTopic').value, skillId: el('newSkill').value, prompt: el('newPrompt').value,
      options: el('newOptions').value.split('|').map(s => s.trim()).filter(Boolean),
      answer: el('newAnswer').value.split('|').map(s => s.trim().toUpperCase()).filter(Boolean),
      explanation: el('newExplanation').value, timeLimit: 90
    };
    const out = await api('/admin/questions', { method: 'POST', body });
    if (!out.ok) return el('qError').textContent = (out.errors || []).join(' · ');
    toast('Question saved'); go('questions');
  };
  el('view').querySelectorAll('[data-del]').forEach(b => b.onclick = async () => {
    await api('/admin/questions/' + b.dataset.del, { method: 'DELETE' });
    toast('Deleted'); go('questions');
  });
}

/* ---------------- data import ---------------- */
async function renderImport() {
  el('view').innerHTML = `
    <div class="card"><h2>Data import</h2>
      <p class="muted">Update the question bank (CSV) or company data (JSON) without touching code. Every row is validated and errors are reported with the row number.</p>
      <div class="row mt">
        <button class="btn" id="qTemplate">Load question CSV template</button>
        <button class="btn" id="cTemplate">Load company JSON template</button>
      </div>
      <label class="mt">Kind<select id="impKind"><option value="questions">questions (CSV)</option><option value="companies">companies (JSON)</option></select></label>
      <textarea id="impBody" rows="12" class="mt" placeholder="Paste CSV or JSON here"></textarea>
      <button class="btn primary mt" id="impBtn">Validate and import</button>
      <div id="impOut" class="mt"></div></div>`;
  el('qTemplate').onclick = () => {
    el('impKind').value = 'questions';
    el('impBody').value = 'id,section,topic,skillId,difficulty,type,timeLimit,prompt,optionA,optionB,optionC,optionD,answer,explanation\n' +
      'Q200,aptitude,Ratio & Proportion,quant.ratio,medium,mcq,75,"If x:y=2:3 and y:z=4:5 then x:z is?",8:15,15:8,2:5,5:2,A,"2/3 x 4/5 = 8/15"';
  };
  el('cTemplate').onclick = () => {
    el('impKind').value = 'companies';
    el('impBody').value = JSON.stringify({
      id: 'newco', name: 'NewCo', fullName: 'NewCo Technologies', difficulty: 'Moderate', hiringSeason: 'Aug-Nov',
      roles: ['Graduate Engineer'], eligibility: { cgpa: '6.5+', backlogs: 'none' },
      rounds: [{ name: 'Online test', mode: '90 min', detail: 'Aptitude + 2 coding questions' }],
      skills: { aptitude: 0.3, logical: 0.2, coding: 0.3, communication: 0.1, domain: 0.1 },
      aptitudePatterns: [{ topic: 'Percentages', frequency: 'High', sample: 'Sample question' }],
      codingPatterns: [{ topic: 'Arrays', sample: 'Sample problem' }], hrQuestions: ['Why NewCo?'],
      tips: ['Practice timed sets'], links: {}
    }, null, 2);
  };
  el('impBtn').onclick = async () => {
    el('impOut').innerHTML = `<div class="card tight"><span class="muted small">Validating rows</span>
      <div class="bar mt"><i id="impBar" style="width:10%"></i></div></div>`;
    requestAnimationFrame(() => { const b = el('impBar'); if (b) b.style.width = '80%'; });
    let out;
    try {
      out = await api('/admin/import', { method: 'POST', body: { kind: el('impKind').value,
        filename: el('impKind').value === 'questions' ? 'upload.csv' : 'upload.json', content: el('impBody').value } });
    } catch (e) {
      el('impOut').innerHTML = `<div class="card tight field-error">${esc(e.message)}</div>`;
      M.shake(el('impOut'));                       // errors shake once and never play a sound
      return;
    }
    const failed = (out.errors || []).length > 0;
    el('impOut').innerHTML = `<div class="card tight"><b>${out.imported} row(s) imported</b>
      ${failed ? `<p class="small muted mt">${out.errors.length} row(s) rejected - fix and import again:</p>
        <ul class="clean small mt">${out.errors.map((er, i) => `<li class="tag bad ${i === 0 ? 'pulse-once' : ''}">${esc(er)}</li>`).join('')}</ul>`
        : '<p class="small muted">No validation errors.</p>'}</div>`;
    if (failed) M.shake(el('impOut')); else Sound.play('stepComplete');
    loadNotifications();
  };
}

/* ---------------- misc ---------------- */
/* Tabs with a sliding underline (company detail). */
function initTabs() {
  const bar = document.querySelector('.tabs-underline');
  if (!bar) return;
  const ind = bar.querySelector('i');
  const move = (btn) => {
    bar.querySelectorAll('button').forEach(b => b.classList.toggle('active', b === btn));
    ind.style.width = btn.offsetWidth + 'px';
    ind.style.transform = `translateX(${btn.offsetLeft}px)`;
  };
  bar.querySelectorAll('button').forEach(b => b.onclick = () => {
    move(b);
    const sectionId = { rounds: 'Rounds', questions: 'Aptitude patterns', skills: 'Eligibility', tips: 'Prep tips' }[b.dataset.tab];
    const cards = [...document.querySelectorAll('.modal-card ul.clean, .modal-card .tabs-underline')];
    const target = [...document.querySelectorAll('.modal-card h3')].find(h => h.textContent.includes(sectionId));
    if (target) target.scrollIntoView({ behavior: M.reduced() ? 'auto' : 'smooth', block: 'start' });
  });
  move(bar.querySelector('button.active') || bar.querySelector('button'));
}

/* Right-hand drawer for mentor screens (slide in, dimmed backdrop). */
function openDrawer(html) {
  closeDrawer();
  const back = document.createElement('div');
  back.className = 'backdrop';
  back.onclick = closeDrawer;
  const panel = document.createElement('aside');
  panel.className = 'drawer';
  panel.setAttribute('role', 'dialog');
  panel.setAttribute('aria-modal', 'true');
  panel.innerHTML = `<button class="icon-btn close" style="float:right" aria-label="Close">✕</button>${html}`;
  panel.querySelector('.close').onclick = closeDrawer;
  document.body.append(back, panel);
  document.addEventListener('keydown', escClose);
  return panel;
}
function closeDrawer() {
  document.querySelectorAll('.drawer,.backdrop').forEach(n => n.remove());
  document.removeEventListener('keydown', escClose);
}

let modalEl = null;
function openModal(html, { fromEl = null } = {}) {
  closeModal();
  const wrap = document.createElement('div');
  wrap.className = 'modal';
  wrap.setAttribute('role', 'dialog');
  wrap.setAttribute('aria-modal', 'true');
  wrap.innerHTML = `<div class="modal-card"><button class="icon-btn close" aria-label="Close">✕</button><div id="modalBody">${html}</div></div>`;
  el('modalHost').appendChild(wrap);
  modalEl = wrap;
  if (fromEl) M.morphFrom(wrap.querySelector('.modal-card'), fromEl);   // shared-element morph
  const close = () => closeModal();
  wrap.querySelector('.close').onclick = close;
  wrap.onclick = (e) => { if (e.target === wrap) close(); };
  document.addEventListener('keydown', escClose);
  const first = wrap.querySelector('button, input, select, textarea, a');
  if (first) first.focus();
  return wrap;
}
function closeModal() {
  if (!modalEl) return;
  modalEl.remove();
  modalEl = null;
  document.removeEventListener('keydown', escClose);
}
function escClose(e) { if (e.key === 'Escape') { closeModal(); closeDrawer(); } }
window.addEventListener('resize', () => { if (['dashboard', 'results', 'mentor', 'admin'].includes(state.view)) go(state.view); });
boot();
