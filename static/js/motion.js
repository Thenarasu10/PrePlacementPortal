/* ============================================================================
   PlaceIQ motion helpers - all durations/easings mirror the CSS tokens.
   Every helper degrades to an instant state change under reduced motion.
   ========================================================================== */
const M = (() => {
  const reduced = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const DUR = { micro: 150, comp: 250, page: 420, chart: 500, celebrate: 1200 };
  const easeOut = t => 1 - Math.pow(1 - t, 3);
  /** rAF loop with a clamped progress value (never below 0, so numbers can never go negative
      if a frame timestamp arrives out of order or a polyfill passes an odd clock value). */
  const raf = (ms, step) => new Promise(res => {
    if (reduced() || ms <= 0) { step(1); return res(); }
    const t0 = performance.now();
    const tick = now => {
      const p = Math.max(0, Math.min(1, (Number(now) - t0) / ms));
      step(easeOut(p));
      p < 1 ? requestAnimationFrame(tick) : res();
    };
    requestAnimationFrame(tick);
  });

  /** Stagger entrance for lists/grids: 40-60ms per item, capped at 8 items. */
  function stagger(container, selector, { step = 50, max = 8, cls = 'enter in' } = {}) {
    const items = [...container.querySelectorAll(selector)];
    items.forEach((el, i) => {
      el.classList.add('enter');
      const delay = Math.min(i, max - 1) * step;
      if (reduced()) { el.classList.remove('enter'); return; }
      el.style.animationDelay = delay + 'ms';
      requestAnimationFrame(() => el.classList.add(...cls.split(' ')));
    });
    return items;
  }

  /** Count-up for key numbers. */
  function countUp(el, to, { dur = 900, decimals = 0, prefix = '', suffix = '' } = {}) {
    const target = Number(to) || 0;
    const paint = v => { el.textContent = prefix + v.toFixed(decimals) + suffix; };
    if (reduced()) { paint(target); return; }
    return raf(dur, p => paint(target * p)).then(() => paint(target));   // always land exactly on target
  }

  /** Animated SVG progress ring: r=58 => circumference 364.4 */
  function ring(svg, value, { dur = DUR.celebrate, color = null } = {}) {
    const r = 58, c = 2 * Math.PI * r;
    const track = svg.querySelector('.track'), val = svg.querySelector('.val');
    [track, val].forEach(x => { x.setAttribute('r', r); x.setAttribute('cx', 66); x.setAttribute('cy', 66); x.setAttribute('stroke-dasharray', c); });
    if (color) val.style.stroke = color;
    const target = c - (Math.max(0, Math.min(100, Number(value) || 0)) / 100) * c;
    if (reduced()) { val.setAttribute('stroke-dashoffset', target); return; }
    val.setAttribute('stroke-dashoffset', c);
    return raf(dur, p => val.setAttribute('stroke-dashoffset', c - (c - target) * p));
  }

  /** Small sparkle burst anchored to one element (never full screen, skippable). */
  function celebrate(anchor, { count = 22, dur = DUR.celebrate } = {}) {
    if (reduced() || !anchor) return;
    const rect = anchor.getBoundingClientRect();
    const wrap = document.createElement('div');
    wrap.className = 'sparkles';
    const colors = ['#4F46E5', '#16A34A', '#F59E0B', '#2563EB', '#EC4899'];
    for (let i = 0; i < count; i++) {
      const s = document.createElement('i');
      s.className = 'spark';
      const angle = (Math.PI * 2 * i) / count + Math.random() * .5;
      const dist = 60 + Math.random() * 90;
      s.style.setProperty('--dx', Math.cos(angle) * dist + 'px');
      s.style.setProperty('--dy', Math.sin(angle) * dist + 'px');
      s.style.left = (rect.left + rect.width / 2 + (Math.random() - .5) * rect.width) + 'px';
      s.style.top = (rect.top + rect.height / 3 + (Math.random() - .5) * 40) + 'px';
      s.style.background = colors[i % colors.length];
      s.style.animationDuration = dur + 'ms';
      wrap.appendChild(s);
    }
    document.body.appendChild(wrap);
    setTimeout(() => wrap.remove(), dur + 120);
  }

  /** Milestone = sparkles + sound, with a visual fallback if sound is off. */
  function milestone(anchor, label) {
    celebrate(anchor);
    Sound.play('milestone');
    if (label) announce('Milestone: ' + label);
  }

  /** Shared-element morph: grows a modal card out of the card that was clicked. */
  function morphFrom(el, fromEl, { dur = DUR.comp } = {}) {
    if (reduced() || !el || !fromEl) return;
    const a = el.getBoundingClientRect(), b = fromEl.getBoundingClientRect();
    const dx = (b.left + b.width / 2) - (a.left + a.width / 2);
    const dy = (b.top + b.height / 2) - (a.top + a.height / 2);
    const sx = Math.max(.2, b.width / a.width), sy = Math.max(.2, b.height / a.height);
    el.style.animation = 'none';
    el.style.transformOrigin = 'center';
    el.style.transform = `translate(${dx}px,${dy}px) scale(${sx},${sy})`;
    el.style.opacity = '.4';
    requestAnimationFrame(() => {
      el.style.transition = `transform ${dur}ms cubic-bezier(.16,1,.3,1), opacity ${dur}ms ease-out`;
      el.style.transform = 'none';
      el.style.opacity = '1';
    });
  }

  /** Cross-fade around a content swap (used for chart toggles). */
  function crossFade(el, swap, { out = 120, back = DUR.comp } = {}) {
    if (reduced()) { swap(); return Promise.resolve(); }
    el.style.transition = `opacity ${out}ms ease-in`;
    el.style.opacity = '0';
    return new Promise(res => setTimeout(() => {
      swap();
      el.style.transition = `opacity ${back}ms ease-out`;
      el.style.opacity = '1';
      setTimeout(res, back);
    }, out));
  }

  /** Line-by-line typing with a visible caret (mock interview question). */
  function typing(el, text, { cps = 45 } = {}) {
    if (reduced()) { el.textContent = text; return Promise.resolve(); }
    el.classList.add('typing');
    el.textContent = '';
    return new Promise(res => {
      let i = 0;
      const step = Math.max(12, 1000 / cps);
      const t = setInterval(() => {
        el.textContent = text.slice(0, ++i);
        if (i >= text.length) { clearInterval(t); el.classList.remove('typing'); res(); }
      }, step);
    });
  }

  /** One-shot shake for validation errors. */
  function shake(el) {
    if (!el || reduced()) return;
    el.style.animation = 'none';
    requestAnimationFrame(() => { el.style.animation = 'shake 220ms cubic-bezier(.16,1,.3,1) 1'; });
  }

  /** Nudge a ring/stat to show it moved. */
  function nudge(el) {
    if (!el || reduced()) return;
    el.style.animation = 'none';
    requestAnimationFrame(() => { el.style.animation = 'ring-nudge 320ms cubic-bezier(.34,1.32,.64,1) 1'; });
  }

  function slideSwap(card, render) {
    if (!card || reduced()) { render(); return; }
    card.classList.add('slide-out');
    setTimeout(() => { render(); }, DUR.comp - 40);
  }

  /** First-view-of-day gate: returns true once per key per day (used for the ring chime). */
  function firstViewToday(key) {
    const stamp = new Date().toISOString().slice(0, 10);
    const seen = localStorage.getItem('placeiq_seen_' + key);
    if (seen === stamp) return false;
    localStorage.setItem('placeiq_seen_' + key, stamp);
    return true;
  }

  /** Screen-reader equivalent so no information is conveyed by sound alone. */
  function announce(text) {
    const live = document.getElementById('srLive');
    if (!live) return;
    live.textContent = '';
    setTimeout(() => { live.textContent = text; }, 30);
  }

  /** Skeleton placeholders shown while a screen loads. */
  function skeleton(container, cards = 4) {
    container.innerHTML = `<div class="grid g2">${Array.from({ length: cards })
      .map(() => '<div class="skeleton card"></div>').join('')}</div>`;
  }

  return { reduced, DUR, stagger, countUp, ring, celebrate, milestone, morphFrom, crossFade, typing,
           shake, nudge, slideSwap, firstViewToday, announce, skeleton };
})();
