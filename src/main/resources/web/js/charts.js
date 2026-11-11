// Hand-made SVG charts (no library, works offline).
// Marks: 2px lines, ≤24px bars with 4px rounded data ends, 2px surface gaps, hairline grid,
// a hover/touch tooltip on every chart, and a legend whenever there is more than one series.
import { h, s, fmt, compact, pct, time, weekday, dayShort } from './util.js';

export const SERIES = ['var(--c1)', 'var(--c2)', 'var(--c3)', 'var(--c4)', 'var(--c5)', 'var(--c6)', 'var(--c7)', 'var(--c8)'];
export const OTHER = 'var(--c-other)';

// ───────── tooltip ─────────

let tipEl;
export function showTip(x, y, ...content) {
  if (!tipEl) { tipEl = h('div.tip', { role: 'tooltip' }); document.body.appendChild(tipEl); }
  tipEl.replaceChildren(...content.filter((c) => c != null && c !== false));
  tipEl.classList.add('on');
  const r = tipEl.getBoundingClientRect();
  let left = x + 14;
  let top = y - r.height - 12;
  if (left + r.width > window.innerWidth - 8) left = x - r.width - 14;
  if (left < 8) left = 8;
  if (top < 8) top = y + 16;
  tipEl.style.left = left + 'px';
  tipEl.style.top = top + 'px';
}
export function hideTip() { if (tipEl) tipEl.classList.remove('on'); }
export const tipTitle = (t) => h('div.tt', t);
export const tipRow = (color, text) => h('div.tr', color ? h('i', { style: { background: color } }) : null, text);

/** Calls draw(width) now and whenever the element changes width. */
function responsive(el, draw) {
  let last = -1;
  const ro = new ResizeObserver((entries) => {
    const w = Math.round(entries[0].contentRect.width);
    if (w > 0 && w !== last) { last = w; el.replaceChildren(draw(w)); }
  });
  ro.observe(el);
  return el;
}

function niceStep(range, count) {
  const raw = range / Math.max(1, count);
  const mag = Math.pow(10, Math.floor(Math.log10(raw || 1)));
  const n = raw / mag;
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10) * mag;
}

/** Path of a bar with 4px rounded corners at the data end only. */
function barPath(x, y0, y1, w) {
  const up = y1 < y0;
  const hgt = Math.abs(y1 - y0);
  const r = Math.min(4, w / 2, hgt);
  if (hgt < 0.5) return '';
  if (up) {
    return `M${x},${y0}V${y1 + r}Q${x},${y1} ${x + r},${y1}H${x + w - r}Q${x + w},${y1} ${x + w},${y1 + r}V${y0}Z`;
  }
  return `M${x},${y0}V${y1 - r}Q${x},${y1} ${x + r},${y1}H${x + w - r}Q${x + w},${y1} ${x + w},${y1 - r}V${y0}Z`;
}

// ───────── the wheel (donut) ─────────

/**
 * data: [{id, label, value, color, href, icon}] ; total shown in the middle.
 */
export function wheel(data, { centerLabel = 'objets', onPick } = {}) {
  const total = data.reduce((a, d) => a + d.value, 0) || 1;
  const R = 96;
  const r = 62;
  const C = 100;
  const svg = s('svg', { viewBox: '0 0 200 200', role: 'img', 'aria-label': 'Répartition des objets' });
  const ctrV = s('text', { x: C, y: C + 2, 'text-anchor': 'middle', class: 'ctr-v' }, compact(total));
  const ctrL = s('text', { x: C, y: C + 22, 'text-anchor': 'middle', class: 'ctr-l' }, centerLabel);
  const legend = h('div.wl');
  const donutBox = h('div.donut', svg);
  const paths = [];
  const rows = [];

  const setCenter = (d) => {
    if (d) {
      ctrV.textContent = compact(d.value);
      const lbl = d.label.length > 16 ? d.label.slice(0, 15) + '…' : d.label;
      ctrL.textContent = lbl;
    } else {
      ctrV.textContent = compact(total);
      ctrL.textContent = centerLabel;
    }
  };
  const highlight = (i) => {
    donutBox.classList.toggle('hl', i != null);
    paths.forEach((p, j) => p && p.classList.toggle('hl', j === i));
    rows.forEach((row, j) => row.classList.toggle('hl', j === i));
    setCenter(i == null ? null : data[i]);
  };

  let a = -Math.PI / 2;
  const gap = 2 / R;
  data.forEach((d, i) => {
    const sweep = (d.value / total) * Math.PI * 2;
    const a0 = a + (data.length > 1 ? gap / 2 : 0);
    const a1 = a + sweep - (data.length > 1 ? gap / 2 : 0);
    a += sweep;
    if (a1 - a0 <= 0.002) { paths.push(null); return; }
    const large = a1 - a0 > Math.PI ? 1 : 0;
    const full = data.length === 1;
    const p = full
      ? `M${C},${C - R}A${R},${R} 0 1 1 ${C - 0.01},${C - R}ZM${C},${C - r}A${r},${r} 0 1 0 ${C + 0.01},${C - r}Z`
      : `M${C + R * Math.cos(a0)},${C + R * Math.sin(a0)}A${R},${R} 0 ${large} 1 ${C + R * Math.cos(a1)},${C + R * Math.sin(a1)}`
        + `L${C + r * Math.cos(a1)},${C + r * Math.sin(a1)}A${r},${r} 0 ${large} 0 ${C + r * Math.cos(a0)},${C + r * Math.sin(a0)}Z`;
    const path = s('path', { d: p, style: `fill:${d.color}`, 'fill-rule': 'evenodd', tabindex: '-1' });
    path.addEventListener('pointerenter', (e) => { highlight(i); showTip(e.clientX, e.clientY, tipTitle(d.label), tipRow(d.color, `${fmt(d.value)} · ${pct(d.value / total)}`)); });
    path.addEventListener('pointermove', (e) => showTip(e.clientX, e.clientY, tipTitle(d.label), tipRow(d.color, `${fmt(d.value)} · ${pct(d.value / total)}`)));
    path.addEventListener('pointerleave', () => { highlight(null); hideTip(); });
    path.addEventListener('click', () => { hideTip(); if (d.href) location.hash = d.href; else if (onPick) onPick(d); });
    svg.appendChild(path);
    paths.push(path);
  });
  svg.append(ctrV, ctrL);

  data.forEach((d, i) => {
    const row = h(d.href ? 'a' : 'div', { href: d.href || null },
      h('i.sw', { style: { background: d.color } }),
      d.icon || null,
      h('span.nm', d.label),
      h('span.v', fmt(d.value)),
      h('span.p', pct(d.value / total)));
    row.addEventListener('pointerenter', () => highlight(i));
    row.addEventListener('pointerleave', () => highlight(null));
    legend.appendChild(row);
    rows.push(row);
  });
  return h('div.wheel', donutBox, legend);
}

// ───────── line / area over time ─────────

/**
 * points: numbers, evenly spaced from `from` every `step` ms.
 * Area wash only when the axis starts at zero (otherwise just the line, so small changes stay visible).
 */
export function timeChart({ points, from, step, height = 190, color = 'var(--c1)', unit = 'objets' }) {
  const el = h('div.chart');
  return responsive(el, (W) => {
    const H = height;
    const pad = { l: 46, r: 14, t: 12, b: 26 };
    const iw = W - pad.l - pad.r;
    const ih = H - pad.t - pad.b;
    const n = points.length;
    const min = Math.min(...points);
    const max = Math.max(...points);
    let lo = min > max * 0.55 ? min - (max - min) * 0.25 : 0;
    let hi = max === lo ? lo + 10 : max;
    const st = niceStep(hi - lo, 4);
    lo = Math.max(0, Math.floor(lo / st) * st);
    hi = Math.ceil(hi / st) * st;
    if (hi === lo) hi = lo + st;
    const x = (i) => pad.l + (n <= 1 ? iw : (i / (n - 1)) * iw);
    const y = (v) => pad.t + ih - ((v - lo) / (hi - lo)) * ih;

    const svg = s('svg', { viewBox: `0 0 ${W} ${H}`, height: H, role: 'img', 'aria-label': `Évolution, de ${fmt(points[0])} à ${fmt(points[n - 1])} ${unit}` });
    for (let v = lo; v <= hi + st / 2; v += st) {
      svg.appendChild(s('line', { x1: pad.l, x2: W - pad.r, y1: y(v), y2: y(v), class: v === lo ? 'baseline' : 'gridline' }));
      svg.appendChild(s('text', { x: pad.l - 8, y: y(v) + 4, 'text-anchor': 'end' }, compact(Math.round(v))));
    }
    // x labels
    const span = step * (n - 1);
    const label = (t) => (span <= 2 * 86_400_000 ? time(t) : span <= 8 * 86_400_000 ? weekday(t) : dayShort(t));
    const ticks = Math.min(6, Math.max(2, Math.floor(iw / 80)));
    for (let k = 0; k < ticks; k++) {
      const i = Math.round((k / (ticks - 1)) * (n - 1));
      const t = from + i * step;
      svg.appendChild(s('text', { x: x(i), y: H - 6, 'text-anchor': k === 0 ? 'start' : k === ticks - 1 ? 'end' : 'middle' }, label(t)));
    }
    const line = points.map((v, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join('');
    if (lo === 0) {
      svg.appendChild(s('path', { d: `${line}L${x(n - 1)},${y(lo)}L${x(0)},${y(lo)}Z`, style: `fill:${color};opacity:.1` }));
    }
    svg.appendChild(s('path', { d: line, style: `fill:none;stroke:${color};stroke-width:2;stroke-linejoin:round;stroke-linecap:round` }));
    svg.appendChild(s('circle', { cx: x(n - 1), cy: y(points[n - 1]), r: 4.5, style: `fill:${color};stroke:var(--surface);stroke-width:2` }));

    const cross = s('line', { y1: pad.t, y2: pad.t + ih, style: 'stroke:var(--text-3);stroke-width:1;opacity:0' });
    const dot = s('circle', { r: 5, style: `fill:${color};stroke:var(--surface);stroke-width:2;opacity:0` });
    const hit = s('rect', { x: pad.l, y: 0, width: iw, height: H, style: 'fill:transparent;touch-action:pan-y' });
    svg.append(cross, dot, hit);
    const move = (e) => {
      const rect = svg.getBoundingClientRect();
      const px = ((e.clientX - rect.left) / rect.width) * W;
      const i = Math.max(0, Math.min(n - 1, Math.round(((px - pad.l) / iw) * (n - 1))));
      cross.setAttribute('x1', x(i)); cross.setAttribute('x2', x(i)); cross.style.opacity = 0.5;
      dot.setAttribute('cx', x(i)); dot.setAttribute('cy', y(points[i])); dot.style.opacity = 1;
      const t = from + i * step;
      const when = span <= 2 * 86_400_000 ? time(t) : `${weekday(t)} ${dayShort(t)}${step < 86_400_000 ? ' · ' + time(t) : ''}`;
      const prev = i > 0 ? points[i] - points[i - 1] : 0;
      showTip(e.clientX, e.clientY, tipTitle(when), tipRow(color, `${fmt(points[i])} ${unit}`),
        i > 0 && prev !== 0 ? h('div.tr', h('span', { class: prev > 0 ? 'good' : 'bad' }, `${prev > 0 ? '+' : '−'}${fmt(Math.abs(prev))}`), ' depuis le point précédent') : null);
    };
    hit.addEventListener('pointermove', move);
    hit.addEventListener('pointerdown', move);
    hit.addEventListener('pointerleave', () => { cross.style.opacity = 0; dot.style.opacity = 0; hideTip(); });
    return svg;
  });
}

// ───────── deposits (up) / withdrawals (down) per day ─────────

export function inOutChart(days, { height = 200 } = {}) {
  const el = h('div.chart');
  const box = h('div', responsive(el, (W) => {
    const H = height;
    const pad = { l: 46, r: 8, t: 10, b: 24 };
    const iw = W - pad.l - pad.r;
    const ih = H - pad.t - pad.b;
    const maxIn = Math.max(1, ...days.map((d) => d.in));
    const maxOut = Math.max(1, ...days.map((d) => d.out));
    const st = niceStep(Math.max(maxIn, maxOut), 3);
    const top = Math.max(st, Math.ceil(maxIn / st) * st);
    const bot = Math.max(st, Math.ceil(maxOut / st) * st);
    const y = (v) => pad.t + ((top - v) / (top + bot)) * ih;
    const band = iw / days.length;
    const bw = Math.max(3, Math.min(24, band * 0.62));
    const svg = s('svg', { viewBox: `0 0 ${W} ${H}`, height: H, role: 'img', 'aria-label': 'Objets déposés et retirés par jour' });
    for (let v = -bot; v <= top + st / 2; v += st) {
      if (v !== 0) svg.appendChild(s('line', { x1: pad.l, x2: W - pad.r, y1: y(v), y2: y(v), class: 'gridline' }));
      svg.appendChild(s('text', { x: pad.l - 8, y: y(v) + 4, 'text-anchor': 'end' }, (v < 0 ? '−' : '') + compact(Math.abs(v))));
    }
    const every = Math.ceil(days.length / Math.max(2, Math.floor(iw / 44)));
    days.forEach((d, i) => {
      const cx = pad.l + band * i + band / 2;
      const x0 = cx - bw / 2;
      if (d.in > 0) svg.appendChild(s('path', { d: barPath(x0, y(0) - 1, y(d.in), bw), style: 'fill:var(--c1)' }));
      if (d.out > 0) svg.appendChild(s('path', { d: barPath(x0, y(0) + 1, y(-d.out), bw), style: 'fill:var(--c2)' }));
      if (i % every === 0 || i === days.length - 1) {
        svg.appendChild(s('text', { x: cx, y: H - 6, 'text-anchor': 'middle' }, days.length <= 8 ? weekday(d.day) : dayShort(d.day)));
      }
      const hit = s('rect', { x: pad.l + band * i, y: 0, width: band, height: H, style: 'fill:transparent;touch-action:pan-y' });
      const show = (e) => showTip(e.clientX, e.clientY, tipTitle(`${weekday(d.day)} ${dayShort(d.day)}`),
        tipRow('var(--c1)', `${fmt(d.in)} déposés`), tipRow('var(--c2)', `${fmt(d.out)} retirés`));
      hit.addEventListener('pointermove', show);
      hit.addEventListener('pointerdown', show);
      hit.addEventListener('pointerleave', hideTip);
      svg.appendChild(hit);
    });
    svg.appendChild(s('line', { x1: pad.l, x2: W - pad.r, y1: y(0), y2: y(0), class: 'baseline' }));
    return svg;
  }), legend([['var(--c1)', 'Déposés'], ['var(--c2)', 'Retirés']]));
  return box;
}

export function legend(items) {
  return h('div.legend', items.map(([c, l]) => h('span', h('i', { style: { background: c } }), l)));
}

// ───────── horizontal bars (HTML) ─────────

export function hbars(rows, { color = 'var(--c1)' } = {}) {
  const max = Math.max(1, ...rows.map((r) => r.value));
  return h('div.hbars', rows.map((r) => h(r.href ? 'a.hb' : 'div.hb', { href: r.href || null },
    h('div.top', h('span.nm', r.label), h('span.v', r.valueLabel || fmt(r.value))),
    h('div.bar', h('i', { style: { width: `${Math.max(0.5, (r.value / max) * 100)}%`, background: color } })))));
}

// ───────── one whole split into parts ─────────

export function segBar(parts) {
  const total = parts.reduce((a, p) => a + p.value, 0) || 1;
  const bar = h('div.seg-bar', { role: 'img', 'aria-label': parts.map((p) => `${p.label} ${pct(p.value / total)}`).join(', ') });
  parts.forEach((p) => {
    const share = p.value / total;
    const seg = h('i', { style: { flex: `${p.value} 1 0`, background: p.color } },
      share > 0.12 ? h('span', pct(share)) : null);
    const show = (e) => showTip(e.clientX, e.clientY, tipTitle(p.label), tipRow(p.color, `${fmt(p.value)} · ${pct(share)}`));
    seg.addEventListener('pointermove', show);
    seg.addEventListener('pointerdown', show);
    seg.addEventListener('pointerleave', hideTip);
    bar.appendChild(seg);
  });
  return h('div', bar, legend(parts.map((p) => [p.color, p.label])));
}

// ───────── fill meter ─────────

export function meter(used, total, { label = true } = {}) {
  const ratio = total ? used / total : 0;
  return h('div',
    h('div', { class: ratio >= 0.95 ? 'meter warn' : 'meter', role: 'meter', 'aria-valuemin': 0, 'aria-valuemax': total, 'aria-valuenow': used },
      h('i', { style: { width: `${Math.min(100, ratio * 100)}%` } })),
    label ? h('div.meter-l', h('span', ratio >= 0.95 ? '⚠︎ Presque plein' : `${used} / ${total} cases`), h('span', pct(ratio))) : null);
}

// ───────── progress ring (projects) ─────────

/** A ring filled to `ratio` (0–1) with the percentage in the middle. */
export function ring(ratio, { size = 120, stroke = 12, label, color } = {}) {
  const r = (100 - stroke) / 2;
  const c = 2 * Math.PI * r;
  const v = Math.max(0, Math.min(1, ratio || 0));
  const done = v >= 1;
  const col = color || (done ? 'var(--good)' : 'var(--accent)');
  const pctText = Math.floor(v * 100 + 1e-9) + ' %';
  const svg = s('svg', { viewBox: '0 0 100 100', width: size, height: size, role: 'img', 'aria-label': `${label ? label + ' : ' : ''}${pctText}` },
    s('circle', { cx: 50, cy: 50, r, style: `fill:none;stroke:var(--fill-2);stroke-width:${stroke}` }),
    v > 0 ? s('circle', {
      cx: 50, cy: 50, r, transform: 'rotate(-90 50 50)',
      style: `fill:none;stroke:${col};stroke-width:${stroke};stroke-linecap:round;stroke-dasharray:${(c * v).toFixed(2)} ${c.toFixed(2)}`,
    }) : null,
    s('text', { x: 50, y: label ? 52 : 57, 'text-anchor': 'middle', class: 'ring-v' }, pctText),
    label ? s('text', { x: 50, y: 67, 'text-anchor': 'middle', class: 'ring-l' }, label) : null);
  return h('div.ring', svg);
}

/** Thin progress bar for one material. */
export function progressBar(have, need) {
  const v = need ? Math.min(1, have / need) : 1;
  return h('div', { class: v >= 1 ? 'pbar done' : 'pbar', role: 'progressbar', 'aria-valuemin': 0, 'aria-valuemax': need, 'aria-valuenow': Math.min(have, need) },
    h('i', { style: { width: `${v * 100}%` } }));
}

/** Small table for the "Tableau" view of a chart. */
export function table(head, rows) {
  return h('div.table-wrap', h('table.data-table',
    h('thead', h('tr', head.map((c) => h('th', c)))),
    h('tbody', rows.map((r) => h('tr', r.map((c) => h('td', c)))))));
}
