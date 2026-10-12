/* ============================================================================
   PlaceIQ sound manager
   - Off until the student opts in (onboarding or Profile). Never autoplays.
   - Volume slider, per-event mapping, all tones synthesised with WebAudio
     (no audio files, so the app stays fully offline).
   - TEST MODE RULE: during timed tests only timer warnings and the submit
     confirmation play. Correct/incorrect sounds are muted so answers are not
     revealed by sound.
   - Every sound has a visible equivalent in the UI; nothing is sound-only.
   ========================================================================== */
const Sound = (() => {
  let ctx = null, master = null, enabled = false, volume = 0.5, testMode = false, unlockBound = false;

  /* Per-event mapping: notes are (frequency, start s, duration s, gain), wave, and whether
     the event is allowed inside a timed test. */
  const EVENTS = {
    tap:        { wave: 'sine',     notes: [[660, 0, .07, .05]] },
    correct:    { wave: 'triangle', notes: [[523.25, 0, .14, .12], [784, .1, .18, .1]] },       // rising chime
    incorrect:  { wave: 'sine',     notes: [[233, 0, .18, .07], [196, .12, .22, .05]] },        // soft, low, non-punishing
    taskCheck:  { wave: 'triangle', notes: [[880, 0, .08, .07], [1318.5, .07, .1, .05]] },      // light click-chime
    stepComplete:{ wave: 'triangle', notes: [[587.33, 0, .16, .1], [880, .14, .22, .09]] },      // two-note chime
    pageComplete:{ wave: 'triangle', notes: [[659.25, 0, .16, .1], [987.77, .15, .24, .09]] },
    dayComplete: { wave: 'triangle', notes: [[659.25, 0, .14, .1], [880, .12, .16, .09], [1174.66, .26, .24, .08]] },
    milestone:  { wave: 'triangle', notes: [[523.25, 0, .18, .1], [659.25, .16, .18, .1], [783.99, .32, .2, .1], [1046.5, .5, .5, .09]] }, // ~1.0s, brighter
    timerWarn:  { wave: 'sine',     notes: [[1000, 0, .05, .04], [1000, .16, .05, .035]], test: true },  // gentle tick, not an alarm
    notify:     { wave: 'sine',     notes: [[1174.66, 0, .18, .07]] },
    submitConfirm: { wave: 'triangle', notes: [[659.25, 0, .12, .09], [880, .11, .2, .08]], test: true }
  };

  function load() {
    enabled = localStorage.getItem('placeiq_sound') === 'on';
    const v = parseFloat(localStorage.getItem('placeiq_volume'));
    volume = isNaN(v) ? 0.5 : v;
    return { enabled, volume };
  }

  function ensureContext() {
    if (ctx) return ctx;
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return null;
    ctx = new AC();
    master = ctx.createGain();
    master.gain.value = volume * 0.6;      // keep the whole system quiet by default
    master.connect(ctx.destination);
    return ctx;
  }

  /* Browsers require a user gesture before audio - we bind the unlock lazily, never on load. */
  function bindUnlock() {
    if (unlockBound) return;
    unlockBound = true;
    const unlock = () => { ensureContext(); if (ctx && ctx.state === 'suspended') ctx.resume(); };
    ['pointerdown', 'keydown', 'touchstart'].forEach(ev => window.addEventListener(ev, unlock, { once: true, passive: true }));
  }

  function play(event) {
    const spec = EVENTS[event];
    if (!spec) return false;
    if (!enabled) return false;                                  // opt-in only
    if (testMode && !spec.test) return false;                    // test mode rule
    if (!ensureContext()) return false;
    if (ctx.state === 'suspended') ctx.resume();
    const t0 = ctx.currentTime + 0.01;
    spec.notes.forEach(([freq, start, dur, gain]) => {
      const osc = ctx.createOscillator();
      const g = ctx.createGain();
      osc.type = spec.wave;
      osc.frequency.setValueAtTime(freq, t0 + start);
      g.gain.setValueAtTime(0.0001, t0 + start);
      g.gain.linearRampToValueAtTime(gain, t0 + start + 0.012);          // soft attack
      g.gain.exponentialRampToValueAtTime(0.0001, t0 + start + dur);     // gentle decay
      osc.connect(g).connect(master);
      osc.start(t0 + start);
      osc.stop(t0 + start + dur + 0.02);
    });
    return true;
  }

  function setEnabled(on) {
    enabled = !!on;
    localStorage.setItem('placeiq_sound', enabled ? 'on' : 'off');
    if (enabled) { ensureContext(); if (ctx && ctx.state === 'suspended') ctx.resume(); }
  }

  function setVolume(v) {
    volume = Math.max(0, Math.min(1, Number(v) || 0));
    localStorage.setItem('placeiq_volume', String(volume));
    if (master) master.gain.value = volume * 0.6;
  }

  function preview(event) {
    const was = enabled;
    enabled = true;                    // preview button is an explicit user action
    if (!ensureContext()) { enabled = was; return; }
    const spec = EVENTS[event] || EVENTS.stepComplete;
    spec.notes.forEach(([freq, start, dur, gain]) => {
      const t0 = ctx.currentTime + 0.01;
      const osc = ctx.createOscillator(), g = ctx.createGain();
      osc.type = spec.wave;
      osc.frequency.setValueAtTime(freq, t0 + start);
      g.gain.setValueAtTime(0.0001, t0 + start);
      g.gain.linearRampToValueAtTime(gain, t0 + start + 0.012);
      g.gain.exponentialRampToValueAtTime(0.0001, t0 + start + dur);
      osc.connect(g).connect(master);
      osc.start(t0 + start); osc.stop(t0 + start + dur + 0.02);
    });
    if (!was) enabled = false;         // previewing never flips the stored preference
  }

  const state = load();
  enabled = state.enabled;
  bindUnlock();

  return {
    play, setEnabled, setVolume, preview,
    isEnabled: () => enabled,
    getVolume: () => volume,
    setTestMode: (on) => { testMode = !!on; },
    isTestMode: () => testMode,
    EVENTS: Object.keys(EVENTS)
  };
})();
