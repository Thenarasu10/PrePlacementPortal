/* ============================================================================
   PlaceIQ charts - canvas, no dependencies, offline.
   Every chart: animates in (lines draw, radar expands, bars grow, cells stagger),
   has a hover tooltip with a small scale-in, a no-data state, and a text summary
   so nothing is conveyed by colour alone.
   ========================================================================== */
const Charts = (() => {
  const C = () => getComputedStyle(document.documentElement);
  const css = n => C().getPropertyValue(n).trim() || '#888';
  const lvl = v => (v >= 75 ? css('--lvl-advanced') : v >= 45 ? css('--lvl-intermediate') : css('--lvl-beginner'));

  function setup(canvas, height) {
    const parent = canvas.parentElement;
    if (!parent.classList.contains('chart-wrap')) parent.classList.add('chart-wrap');
    const dpr = window.devicePixelRatio || 1;
    const w = parent.clientWidth || 460, h = height || 230;
    canvas.width = w * dpr; canvas.height = h * dpr;
    canvas.style.height = h + 'px'; canvas.style.width = '100%';
    const ctx = canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    return { ctx, w, h };
  }
  function noData(canvas, message = 'No data yet', height = 200) {
    const { ctx, w, h } = setup(canvas, height);
    ctx.fillStyle = css('--muted'); ctx.font = '13px Inter, system-ui';
    ctx.textAlign = 'center';
    ctx.fillText(message, w / 2, h / 2 - 6);
    ctx.font = '12px Inter, system-ui';
    ctx.fillText('Take an assessment to unlock this chart', w / 2, h / 2 + 14);
    ctx.textAlign = 'left';
    canvas._tipPoints = [];
  }
  function raf(ms, step) {
    if (M.reduced() || ms <= 0) { step(1); return; }
    const t0 = performance.now();
    const tick = now => {
      const p = Math.min(1, (now - t0) / ms);
      step(1 - Math.pow(1 - p, 3));
      if (p < 1) requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  }
  /** Hover tooltip with a small scale-in, anchored to the canvas. */
  function tooltip(canvas, points) {
    canvas._tipPoints = points || [];
    if (canvas._tipBound) return;
    canvas._tipBound = true;
    const host = canvas.parentElement;
    canvas.addEventListener('mousemove', e => {
      const r = canvas.getBoundingClientRect();
      const x = e.clientX - r.left, y = e.clientY - r.top;
      const hit = (canvas._tipPoints || []).find(p => Math.hypot(p.x - x, p.y - y) < 14);
      let tip = host.querySelector('.chart-tip');
      if (!hit) { if (tip) tip.remove(); return; }
      if (!tip) { tip = document.createElement('div'); tip.className = 'chart-tip'; host.appendChild(tip); }
      tip.textContent = hit.label;
      tip.style.left = Math.min(host.clientWidth - 90, Math.max(6, hit.x - 40)) + 'px';
      tip.style.top = Math.max(4, hit.y - 34) + 'px';
    });
    canvas.addEventListener('mouseleave', () => {
      const tip = host.querySelector('.chart-tip');
      if (tip) tip.remove();
    });
  }

  /** Line chart: series=[{name,color,data:[]}], labels=[] . Lines draw in left to right. */
  function line(canvas, labels, series, opts = {}) {
    if (!series || !series.length || !labels.length) return noData(canvas, 'No attempts recorded yet', 230);
    const { ctx, w, h } = setup(canvas, opts.height || 230);
    const pad = { l: 42, r: 14, t: 14, b: 30 };
    const max = opts.max || 100, min = opts.min || 0;
    const plotW = w - pad.l - pad.r, plotH = h - pad.t - pad.b;
    const n = Math.max(1, labels.length);
    const X = i => pad.l + (n === 1 ? plotW / 2 : (plotW * i) / (n - 1));
    const Y = v => pad.t + plotH - (Math.max(min, Math.min(max, v)) / max) * plotH;
    const tips = [];

    const frame = p => {
      ctx.clearRect(0, 0, w, h);
      // grid + axis labels
      ctx.strokeStyle = css('--line'); ctx.fillStyle = css('--muted'); ctx.font = '11px Inter, system-ui';
      for (let i = 0; i <= 4; i++) {
        const y = pad.t + (plotH * i) / 4;
        ctx.beginPath(); ctx.moveTo(pad.l, y); ctx.lineTo(w - pad.r, y); ctx.lineWidth = 1; ctx.stroke();
        ctx.fillText(String(Math.round(max - ((max - min) * i) / 4)), 4, y + 4);
      }
      labels.forEach((lab, i) => { if (n <= 8 || i % 2 === 0) ctx.fillText(String(lab).slice(0, 12), X(i) - 14, h - 8); });
      // clip to the animated progress so lines "draw"
      ctx.save();
      ctx.beginPath(); ctx.rect(pad.l - 2, 0, plotW * p + 4, h); ctx.clip();
      series.forEach(s => {
        ctx.strokeStyle = s.color; ctx.lineWidth = 2.2; ctx.beginPath();
        s.data.forEach((v, i) => { const x = X(i), y = Y(v); i ? ctx.lineTo(x, y) : ctx.moveTo(x, y); });
        ctx.stroke();
        ctx.fillStyle = s.color;
        s.data.forEach((v, i) => { ctx.beginPath(); ctx.arc(X(i), Y(v), 3.4, 0, 7); ctx.fill(); });
      });
      ctx.restore();
    };
    if (opts.animate === false || M.reduced()) { frame(1); buildTips(); }
    else raf(M.DUR.chart, p => { frame(p); if (p >= 1) buildTips(); });
    function buildTips() {
      series.forEach(s => s.data.forEach((v, i) => tips.push({ x: X(i), y: Y(v), label: `${s.name}: ${Math.round(v)}%` })));
      tooltip(canvas, tips);
    }
    canvas._tipPoints = tips;
  }

  /** Radar: polygon expands from the centre. */
  function radar(canvas, points, opts = {}) {
    if (!points || !points.length) return noData(canvas, 'Section profile appears after your first assessment', 250);
    const { ctx, w, h } = setup(canvas, opts.height || 260);
    const cx = w / 2, cy = h / 2 + 4, r = Math.min(w, h) / 2 - 40;
    const tips = [];
    const frame = p => {
      ctx.clearRect(0, 0, w, h);
      ctx.strokeStyle = css('--line'); ctx.fillStyle = css('--muted'); ctx.font = '11px Inter, system-ui';
      for (let ring = 1; ring <= 4; ring++) {
        ctx.beginPath();
        points.forEach((pt, i) => {
          const a = (Math.PI * 2 * i) / points.length - Math.PI / 2;
          const rr = (r * ring) / 4;
          const x = cx + rr * Math.cos(a), y = cy + rr * Math.sin(a);
          i ? ctx.lineTo(x, y) : ctx.moveTo(x, y);
        });
        ctx.closePath(); ctx.lineWidth = 1; ctx.stroke();
      }
      ctx.beginPath();
      points.forEach((pt, i) => {
        const a = (Math.PI * 2 * i) / points.length - Math.PI / 2;
        const rr = (r * Math.max(3, Math.min(100, pt.value)) * p) / 100;
        const x = cx + rr * Math.cos(a), y = cy + rr * Math.sin(a);
        i ? ctx.lineTo(x, y) : ctx.moveTo(x, y);
      });
      ctx.closePath();
      ctx.fillStyle = 'rgba(79,70,229,.25)'; ctx.fill();
      ctx.strokeStyle = css('--indigo'); ctx.lineWidth = 2; ctx.stroke();
      points.forEach((pt, i) => {
        const a = (Math.PI * 2 * i) / points.length - Math.PI / 2;
        const x = cx + (r + 18) * Math.cos(a), y = cy + (r + 18) * Math.sin(a);
        ctx.fillStyle = css('--muted');
        ctx.textAlign = Math.abs(Math.cos(a)) < .3 ? 'center' : Math.cos(a) > 0 ? 'left' : 'right';
        ctx.fillText(String(pt.axis).slice(0, 14), x, y + 3);
        ctx.textAlign = 'left';
      });
      tips.length = 0;
      points.forEach((pt, i) => {
        const a = (Math.PI * 2 * i) / points.length - Math.PI / 2;
        const rr = (r * Math.max(3, Math.min(100, pt.value))) / 100;
        tips.push({ x: cx + rr * Math.cos(a), y: cy + rr * Math.sin(a), label: `${pt.axis}: ${Math.round(pt.value)}%` });
      });
    };
    if (opts.animate === false || M.reduced()) frame(1);
    else raf(M.DUR.page, frame);
    tooltip(canvas, tips);
  }

  /** Scatter: accuracy (y) vs average seconds per question (x). */
  function scatter(canvas, points, opts = {}) {
    if (!points || !points.length) return noData(canvas, 'Not enough topic data for accuracy vs speed', 240);
    const { ctx, w, h } = setup(canvas, opts.height || 240);
    const pad = { l: 42, r: 16, t: 14, b: 30 };
    const plotW = w - pad.l - pad.r, plotH = h - pad.t - pad.b;
    const maxT = Math.max(60, ...points.map(p => p.avgTimeSec || 0));
    const tips = [];
    const frame = p => {
      ctx.clearRect(0, 0, w, h);
      ctx.strokeStyle = css('--line'); ctx.fillStyle = css('--muted'); ctx.font = '11px Inter, system-ui';
      for (let i = 0; i <= 4; i++) {
        const y = pad.t + (plotH * i) / 4;
        ctx.beginPath(); ctx.moveTo(pad.l, y); ctx.lineTo(w - pad.r, y); ctx.stroke();
        ctx.fillText(String(100 - i * 25), 4, y + 4);
      }
      ctx.fillText('straight answer time →  (accuracy % vertical)', pad.l, h - 8);
      const n = points.length;
      points.forEach((pt, i) => {
        const local = Math.max(0, Math.min(1, p * (n + 2) - i));   // points pop in one after another
        if (local <= 0) return;
        const x = pad.l + (Math.min(pt.avgTimeSec, maxT) / maxT) * plotW;
        const y = pad.t + plotH - (Math.max(0, Math.min(100, pt.accuracy)) / 100) * plotH;
        const colour = lvl(pt.accuracy);
        ctx.fillStyle = colour;
        ctx.beginPath(); ctx.arc(x, y, 4.4 * local, 0, 7); ctx.fill();
        ctx.fillStyle = css('--muted');
        ctx.fillText(String(pt.topic || '').slice(0, 16), x + 7, y + 3);
        tips[i] = { x, y, label: `${pt.topic}: ${Math.round(pt.accuracy)}% in ${Math.round(pt.avgTimeSec)}s avg` };
      });
    };
    if (opts.animate === false || M.reduced()) frame(1);
    else raf(M.DUR.chart, frame);
    tooltip(canvas, tips.filter(Boolean));
  }

  /** Horizontal bars - used for section averages and cohort distributions. */
  function bars(canvas, items, opts = {}) {
    if (!items || !items.length) return noData(canvas, 'No cohort data yet', 120);
    const { ctx, w, h } = setup(canvas, opts.height || items.length * 32 + 16);
    const pad = { l: 132, r: 46, t: 6, b: 6 };
    const barH = 16;
    const frame = p => {
      ctx.clearRect(0, 0, w, h);
      items.forEach((it, i) => {
        const y = pad.t + i * 30;
        ctx.fillStyle = css('--muted'); ctx.font = '12px Inter, system-ui';
        ctx.fillText(String(it.label).slice(0, 18), 6, y + 13);
        ctx.fillStyle = css('--surface-2');
        ctx.fillRect(pad.l, y, w - pad.l - pad.r, barH);
        const val = Math.max(0, Math.min(100, it.value));
        ctx.fillStyle = it.color || lvl(val);
        const bw = ((w - pad.l - pad.r) * val * p) / 100;
        ctx.fillRect(pad.l, y, Math.max(2, bw), barH);
        if (p > .8) { ctx.fillStyle = css('--text'); ctx.font = '12px Inter, system-ui'; ctx.fillText(Math.round(val) + '%', w - pad.r + 6, y + 13); }
      });
    };
    if (opts.animate === false || M.reduced()) frame(1);
    else raf(M.DUR.chart, frame);
  }

  /** Heatmap: cells fade in row by row, colour always paired with the number. */
  function heat(container, topics) {
    if (!topics || !topics.length) {
      container.innerHTML = '<div class="empty"><span class="big">🗺️</span>No topic data yet. Take an assessment to build your heatmap.</div>';
      return;
    }
    container.innerHTML = topics.map((t, i) => {
      const v = Math.round(t.accuracy);
      const bg = v >= 75 ? css('--lvl-advanced-bg') : v >= 45 ? css('--lvl-intermediate-bg') : css('--lvl-beginner-bg');
      const fg = v >= 75 ? 'var(--lvl-advanced)' : v >= 45 ? 'var(--lvl-intermediate)' : 'var(--lvl-beginner)';
      const label = v >= 75 ? 'strong' : v >= 45 ? 'needs work' : 'weak';
      return `<div class="enter" style="background:${bg};animation-delay:${Math.min(i, 8) * 50}ms">
        <b style="color:${fg}">${t.topic}</b><small>${v}% · ${label} · ${t.attempts} question(s)</small></div>`;
    }).join('');
    requestAnimationFrame(() => container.querySelectorAll('.heat > div').forEach(d => d.classList.add('in')));
  }

  /** Text equivalent of a chart, so the same information is available without colour. */
  function summarize(series, labels) {
    if (!series || !series.length || !labels.length) return 'No attempts recorded yet.';
    return series.map(s => {
      const first = s.data[0], last = s.data[s.data.length - 1];
      const change = Math.round(last - first);
      return `${s.name}: ${Math.round(first)}% at attempt 1 to ${Math.round(last)}% at attempt ${s.data.length} (${change >= 0 ? '+' : ''}${change} points).`;
    }).join(' ');
  }

  return { line, radar, scatter, bars, heat, summarize, lvl };
})();
