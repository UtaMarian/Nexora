// Draws the showcase site's illustrations as SVG and renders them with a local Chrome.
//   node art.js <output folder>        (needs: npm i playwright-core)
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright-core');

function rng(seed) {
  let s = seed;
  return () => ((s = (s * 16807) % 2147483647) - 1) / 2147483646;
}

/** A ridge line made of a few sine waves, closed along the bottom. */
function ridge(w, h, base, amp, seed, fill) {
  const r = rng(seed);
  const waves = [0, 1, 2, 3].map((i) => ({ f: (i + 1) * (0.6 + r()) * 0.0028 * (1800 / w) * (w / 1800), p: r() * 7, a: amp / (i + 1) }));
  let d = `M0 ${h}`;
  for (let x = 0; x <= w; x += 12) d += ` L${x} ${(height(x)).toFixed(1)}`;
  function height(x) {
    return base + waves.reduce((sum, v) => sum + Math.sin(x * v.f + v.p) * v.a, 0);
  }
  d += ` L${w} ${h} Z`;
  return { svg: `<path d="${d}" fill="${fill}"/>`, height };
}

function turbine(x, y, size, color, angle) {
  const hub = y - size;
  const blade = size * 0.62;
  const b = (a) => `<path transform="rotate(${a} ${x} ${hub})" d="M${x - size * 0.012} ${hub} Q${x + size * 0.035} ${hub - blade * 0.3} ${x} ${hub - blade} Q${x - size * 0.016} ${hub - blade * 0.4} ${x - size * 0.012} ${hub} Z"/>`;
  return `<g fill="${color}"><path d="M${x - size * 0.016} ${y} L${x - size * 0.006} ${hub} L${x + size * 0.006} ${hub} L${x + size * 0.016} ${y} Z"/>${b(angle)}${b(angle + 120)}${b(angle + 240)}<circle cx="${x}" cy="${hub}" r="${size * 0.019}"/></g>`;
}

function stars(w, h, count, seed) {
  const r = rng(seed);
  let out = '';
  for (let i = 0; i < count; i++) out += `<circle cx="${r() * w}" cy="${r() * h}" r="${0.5 + r() * 1.4}" fill="#fff" opacity="${0.25 + r() * 0.75}"/>`;
  return out;
}

const defs = (id, stops) => `<linearGradient id="${id}" x1="0" y1="0" x2="0" y2="1">${stops.map((c, i) => `<stop offset="${i / (stops.length - 1)}" stop-color="${c}"/>`).join('')}</linearGradient>`;
const glow = (id, color) => `<radialGradient id="${id}"><stop offset="0" stop-color="${color}" stop-opacity=".95"/><stop offset=".35" stop-color="${color}" stop-opacity=".35"/><stop offset="1" stop-color="${color}" stop-opacity="0"/></radialGradient>`;
const GRAIN = '<filter id="grain"><feTurbulence type="fractalNoise" baseFrequency=".9" numOctaves="2" stitchTiles="stitch"/><feColorMatrix values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  0 0 0 .05 0"/></filter><filter id="soft"><feGaussianBlur stdDeviation="14"/></filter>';

function frame(w, h, extraDefs, body) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}"><defs>${GRAIN}${extraDefs}</defs>${body}<rect width="${w}" height="${h}" filter="url(#grain)"/></svg>`;
}

function dawn(w, h, seed = 7) {
  const r = rng(seed);
  const sunX = w * 0.68;
  const sunY = h * 0.62;
  const far = ridge(w, h, h * 0.66, h * 0.05, seed + 1, '#2f6f7e');
  const mid = ridge(w, h, h * 0.76, h * 0.07, seed + 2, '#16485a');
  const near = ridge(w, h, h * 0.9, h * 0.06, seed + 3, '#0a2a38');
  let farT = '', midT = '', nearT = '';
  for (let i = 0; i < 9; i++) { const x = w * (0.05 + i * 0.105 + r() * 0.03); farT += turbine(x, far.height(x) + 4, h * 0.2, '#7fb2b5', r() * 120); }
  for (let i = 0; i < 6; i++) { const x = w * (0.08 + i * 0.16 + r() * 0.04); midT += turbine(x, mid.height(x) + 6, h * 0.36, '#d8efe9', r() * 120); }
  for (let i = 0; i < 3; i++) { const x = w * (0.14 + i * 0.32 + r() * 0.06); nearT += turbine(x, near.height(x) + 8, h * 0.62, '#ffffff', r() * 120); }
  return frame(w, h, defs('sky', ['#0c2340', '#14506b', '#3f8f94', '#f2a65a', '#ffd9a0']) + glow('sun', '#fff0c2'),
    `<rect width="${w}" height="${h}" fill="url(#sky)"/><circle cx="${sunX}" cy="${sunY}" r="${h * 0.7}" fill="url(#sun)"/><circle cx="${sunX}" cy="${sunY}" r="${h * 0.075}" fill="#fff6dc"/>${stars(w, h * 0.34, 90, seed)}${far.svg}${farT}<rect y="${h * 0.62}" width="${w}" height="${h * 0.1}" fill="#ffd9a0" opacity=".16" filter="url(#soft)"/>${mid.svg}${midT}${near.svg}${nearT}`);
}

function offshore(w, h, seed = 21) {
  const r = rng(seed);
  const horizon = h * 0.6;
  let lines = '';
  for (let i = 0; i < 70; i++) {
    const y = horizon + (i / 70) ** 1.6 * (h - horizon);
    const len = 40 + r() * w * 0.16;
    lines += `<rect x="${r() * w}" y="${y}" width="${len}" height="${1 + i / 26}" fill="#ffc9a8" opacity="${0.05 + r() * 0.22}" rx="1"/>`;
  }
  let t = '';
  const rows = [[0.012, 0.13, '#b78aa6', 11], [0.04, 0.24, '#e7c2c9', 7], [0.1, 0.46, '#ffffff', 4]];
  for (const [dy, size, color, n] of rows) for (let i = 0; i < n; i++) {
    const x = w * ((i + 0.5 + (r() - 0.5) * 0.5) / n);
    const y = horizon + h * dy;
    t += `<rect x="${x - h * size * 0.03}" y="${y - 3}" width="${h * size * 0.06}" height="${h * size * 0.03}" fill="#f6d36b"/>` + turbine(x, y, h * size, color, r() * 120);
  }
  return frame(w, h, defs('sky', ['#1b1642', '#4b2a6f', '#b24a7e', '#f1835e', '#ffc27a']) + defs('sea', ['#d0737a', '#5e3573', '#1c1a48']) + glow('sun', '#ffe3b0'),
    `<rect width="${w}" height="${horizon}" fill="url(#sky)"/><circle cx="${w * 0.3}" cy="${horizon}" r="${h * 0.62}" fill="url(#sun)"/><circle cx="${w * 0.3}" cy="${horizon}" r="${h * 0.09}" fill="#fff1d0"/>${stars(w, h * 0.28, 70, seed)}<rect y="${horizon}" width="${w}" height="${h - horizon}" fill="url(#sea)"/>${lines}${t}`);
}

function solar(w, h, seed = 33) {
  const r = rng(seed);
  const horizon = h * 0.46;
  const far = ridge(w, h, horizon, h * 0.04, seed, '#c98b52');
  let panels = '';
  for (let row = 0; row < 9; row++) {
    const t = row / 8;
    const y = horizon + h * 0.03 + t ** 1.5 * h * 0.5;
    const ph = 12 + t * h * 0.11;
    const pw = 60 + t * w * 0.13;
    const skew = pw * 0.26;
    for (let x = -pw + ((row % 2) * pw) / 2; x < w + pw; x += pw * 1.1) {
      panels += `<path d="M${x} ${y + ph} L${x + skew} ${y} L${x + skew + pw} ${y} L${x + pw} ${y + ph} Z" fill="url(#cell)" stroke="#bfe6ff" stroke-opacity=".5" stroke-width=".8"/>`;
      for (let c = 1; c < 5; c++) panels += `<path d="M${x + (pw * c) / 5} ${y + ph} l${skew} ${-ph}" stroke="#bfe6ff" stroke-opacity=".28" stroke-width=".7"/>`;
      panels += `<path d="M${x + skew / 2} ${y + ph / 2} h${pw}" stroke="#bfe6ff" stroke-opacity=".28" stroke-width=".7"/>`;
      panels += `<rect x="${x + pw * 0.5}" y="${y + ph}" width="${Math.max(2, pw * 0.012)}" height="${ph * 0.4}" fill="#6d5236"/>`;
    }
  }
  let dunes = '';
  for (let i = 0; i < 26; i++) dunes += `<ellipse cx="${r() * w}" cy="${horizon + r() * (h - horizon)}" rx="${80 + r() * 240}" ry="${4 + r() * 12}" fill="#fff" opacity=".04"/>`;
  return frame(w, h, defs('sky', ['#0f6fb5', '#46a9dd', '#a9e0ee', '#ffeec9']) + defs('land', ['#e2ad6e', '#b97a43', '#8a5a33']) + defs('cell', ['#123f7a', '#0b2a59']) + glow('sun', '#fff7d6'),
    `<rect width="${w}" height="${h}" fill="url(#sky)"/><circle cx="${w * 0.8}" cy="${h * 0.2}" r="${h * 0.55}" fill="url(#sun)"/><circle cx="${w * 0.8}" cy="${h * 0.2}" r="${h * 0.07}" fill="#fffbe6"/>${far.svg}<rect y="${horizon + h * 0.03}" width="${w}" height="${h}" fill="url(#land)"/>${dunes}${panels}`);
}

function storage(w, h, seed = 44) {
  const r = rng(seed);
  const ground = h * 0.8;
  let aurora = '';
  for (let i = 0; i < 4; i++) {
    const y = h * (0.16 + i * 0.1);
    aurora += `<path d="M${-50} ${y} C ${w * 0.25} ${y - h * 0.26}, ${w * 0.55} ${y + h * 0.24}, ${w + 50} ${y - h * 0.08}" stroke="${['#33e6b0', '#29c8d8', '#7a7bff', '#2fd98f'][i]}" stroke-width="${h * 0.09}" fill="none" opacity=".26" filter="url(#soft)"/>`;
  }
  let boxes = '';
  const bw = w * 0.085;
  for (let i = 0; i < 11; i++) {
    const x = w * 0.02 + i * bw * 1.05;
    const bh = h * 0.2;
    boxes += `<rect x="${x}" y="${ground - bh}" width="${bw}" height="${bh}" rx="3" fill="#10263b" stroke="#2a4d68"/>`;
    for (let j = 0; j < 6; j++) boxes += `<rect x="${x + bw * 0.07 + (j * bw * 0.88) / 6}" y="${ground - bh * 0.76}" width="${bw * 0.09}" height="${bh * 0.5}" rx="1" fill="${r() > 0.28 ? '#39e6b2' : '#17465c'}" opacity=".9"/>`;
  }
  const hill = ridge(w, h, h * 0.62, h * 0.06, seed, '#0a1c2e');
  let t = '';
  for (let i = 0; i < 7; i++) { const x = w * (0.08 + i * 0.14); t += turbine(x, hill.height(x) + 4, h * 0.3, '#7da7b4', r() * 120); }
  return frame(w, h, defs('sky', ['#050b1e', '#0b1f3f', '#113b52']) + defs('g', ['#0d2433', '#06121d']),
    `<rect width="${w}" height="${h}" fill="url(#sky)"/>${stars(w, h * 0.6, 240, seed)}${aurora}${hill.svg}${t}<rect y="${ground}" width="${w}" height="${h - ground}" fill="url(#g)"/>${boxes}<rect y="${ground}" width="${w}" height="${h * 0.05}" fill="#39e6b2" opacity=".12" filter="url(#soft)"/>`);
}

function valley(w, h, seed = 52) {
  const r = rng(seed);
  const a = ridge(w, h, h * 0.42, h * 0.07, seed + 1, '#9bd1b0');
  const b = ridge(w, h, h * 0.56, h * 0.08, seed + 2, '#5fb182');
  const c = ridge(w, h, h * 0.74, h * 0.08, seed + 3, '#2f8a5f');
  const d = ridge(w, h, h * 0.92, h * 0.05, seed + 4, '#176045');
  let t = '';
  for (let i = 0; i < 6; i++) { const x = w * (0.1 + i * 0.16); t += turbine(x, a.height(x) + 3, h * 0.28, '#fff', r() * 120); }
  let houses = '';
  for (let i = 0; i < 14; i++) {
    const x = w * (0.25 + r() * 0.55);
    const y = c.height(x) + h * (0.03 + r() * 0.04);
    const s = h * 0.03;
    houses += `<rect x="${x}" y="${y}" width="${s * 1.4}" height="${s}" fill="#fff7e8"/><path d="M${x - 2} ${y} L${x + s * 0.7} ${y - s * 0.7} L${x + s * 1.4 + 2} ${y} Z" fill="#c9553d"/>`;
  }
  let trees = '';
  for (let i = 0; i < 90; i++) {
    const x = r() * w;
    const y = d.height(x) + r() * 10;
    const s = h * (0.02 + r() * 0.03);
    trees += `<path d="M${x} ${y - s * 2} L${x + s * 0.7} ${y} L${x - s * 0.7} ${y} Z" fill="#0d4733"/>`;
  }
  const clouds = [0.2, 0.55, 0.82].map((f, i) => `<ellipse cx="${w * f}" cy="${h * (0.14 + i * 0.05)}" rx="${w * 0.1}" ry="${h * 0.035}" fill="#fff" opacity=".8" filter="url(#soft)"/>`).join('');
  return frame(w, h, defs('sky', ['#58b7e6', '#a8e0ee', '#eafbe9']) + glow('sun', '#fff6c9'),
    `<rect width="${w}" height="${h}" fill="url(#sky)"/><circle cx="${w * 0.14}" cy="${h * 0.16}" r="${h * 0.42}" fill="url(#sun)"/>${clouds}${a.svg}${t}${b.svg}${c.svg}${houses}${d.svg}${trees}`);
}

function network(w, h, seed = 61) {
  const r = rng(seed);
  const pts = Array.from({ length: 62 }, () => [r() * w, r() * h]);
  let lines = '';
  pts.forEach((p, i) => pts.slice(i + 1).forEach((q) => {
    const dist = Math.hypot(p[0] - q[0], p[1] - q[1]);
    if (dist < h * 0.3) lines += `<path d="M${p[0]} ${p[1]} L${q[0]} ${q[1]}" stroke="#35d9c3" stroke-opacity="${0.42 * (1 - dist / (h * 0.3))}" stroke-width="1.2"/>`;
  }));
  const nodes = pts.map(([x, y]) => { const s = 2 + r() * 6; return `<circle cx="${x}" cy="${y}" r="${s * 3.4}" fill="#35d9c3" opacity=".08"/><circle cx="${x}" cy="${y}" r="${s}" fill="${r() > 0.75 ? '#ffc65c' : '#7ef2df'}"/>`; }).join('');
  return frame(w, h, `<linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#071a2e"/><stop offset=".6" stop-color="#0c3346"/><stop offset="1" stop-color="#0f5a63"/></linearGradient>`,
    `<rect width="${w}" height="${h}" fill="url(#bg)"/>${lines}${nodes}`);
}

function safety(w, h) {
  const cx = w * 0.72;
  const cy = h * 0.6;
  const s = h * 0.46;
  let rays = '';
  for (let i = 0; i < 18; i++) rays += `<path transform="rotate(${i * 20} ${cx} ${cy})" d="M${cx - s * 0.12} ${cy} L${cx} ${cy - w} L${cx + s * 0.12} ${cy} Z" fill="#fff" opacity=".07"/>`;
  return frame(w, h, `<linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#f59e2c"/><stop offset=".55" stop-color="#ee6c2b"/><stop offset="1" stop-color="#b93a2b"/></linearGradient>`,
    `<rect width="${w}" height="${h}" fill="url(#bg)"/>${rays}<g transform="translate(${cx} ${cy})"><path d="M${-s} 0 A${s} ${s} 0 0 1 ${s} 0 Z" fill="#fff7e6"/><rect x="${-s * 0.17}" y="${-s * 1.08}" width="${s * 0.34}" height="${s * 0.82}" rx="${s * 0.1}" fill="#ffe2b0"/><rect x="${-s * 1.24}" y="${-s * 0.02}" width="${s * 2.48}" height="${s * 0.17}" rx="${s * 0.08}" fill="#fff"/></g>`);
}

function team(w, h, seed = 71) {
  const r = rng(seed);
  const palette = ['#0f766e', '#14b8a6', '#f59e0b', '#ef6c4f', '#2563eb', '#7c3aed', '#0ea5e9', '#84cc16'];
  let people = '';
  const rows = 3;
  for (let row = 0; row < rows; row++) {
    const s = h * (0.13 + row * 0.055);
    const y = h * (0.48 + row * 0.2);
    for (let x = -s + (row % 2) * s; x < w + s * 2; x += s * 2.15) {
      const c = palette[Math.floor(r() * palette.length)];
      people += `<g><path d="M${x - s} ${y + s * 1.7} a${s} ${s * 1.1} 0 0 1 ${s * 2} 0 Z" fill="${c}"/><circle cx="${x}" cy="${y - s * 0.02}" r="${s * 0.5}" fill="${['#f6d1b1', '#d9a47c', '#a06a4a', '#7a4a33', '#f2c49b'][Math.floor(r() * 5)]}"/></g>`;
    }
  }
  return frame(w, h, defs('sky', ['#fff4dc', '#ffe0b8', '#ffd0ad']), `<rect width="${w}" height="${h}" fill="url(#sky)"/><circle cx="${w * 0.5}" cy="${h * 0.42}" r="${h * 0.34}" fill="#fff" opacity=".55"/>${people}`);
}

function logo() {
  const blade = (a) => `<path transform="rotate(${a} 256 256)" d="M240 256 C 236 190, 262 92, 320 56 C 330 130, 306 214, 272 256 Z" fill="#fff"/>`;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#14b8a6"/><stop offset="1" stop-color="#0b4f6c"/></linearGradient></defs><rect width="512" height="512" rx="112" fill="url(#g)"/><circle cx="404" cy="108" r="40" fill="#ffc65c"/><g transform="translate(256 256) scale(.78) translate(-256 -256)">${blade(0)}${blade(120)}${blade(240)}</g><circle cx="256" cy="256" r="30" fill="#0b4f6c"/><circle cx="256" cy="256" r="14" fill="#fff"/></svg>`;
}

const B = [2400, 600];
const C = [1280, 720];
const IMAGES = {
  'banner-home': dawn(...B), 'banner-about': valley(...B), 'banner-assets': offshore(...B), 'banner-safety': safety(...B),
  'banner-people': team(...B), 'banner-digital': network(...B), 'banner-sustainability': solar(...B),
  'news-offshore': offshore(...C, 23), 'news-solar': solar(...C, 34), 'news-storage': storage(...C), 'news-community': valley(...C, 53),
  'news-digital': network(...C, 62), 'news-safety': safety(...C), 'news-team': team(...C, 72), 'news-dawn': dawn(...C, 11),
  'photo-storage': storage(1600, 900, 45), 'photo-ridge': dawn(1600, 900, 13), 'photo-solar': solar(1600, 900, 35), 'photo-sea': offshore(1600, 900, 24),
};

(async () => {
  const out = process.argv[2];
  fs.mkdirSync(out, { recursive: true });
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage();
  for (const [name, svg] of Object.entries(IMAGES)) {
    const [, w, h] = svg.match(/width="(\d+)" height="(\d+)"/);
    await page.setViewportSize({ width: +w, height: +h });
    await page.setContent(`<!doctype html><body style="margin:0">${svg}</body>`);
    await page.screenshot({ path: path.join(out, name + '.jpg'), type: 'jpeg', quality: 88 });
  }
  await page.setViewportSize({ width: 512, height: 512 });
  await page.setContent(`<!doctype html><body style="margin:0;background:transparent">${logo()}</body>`);
  await page.screenshot({ path: path.join(out, 'logo.png'), omitBackground: true });
  await browser.close();
  console.log(Object.keys(IMAGES).length + 1, 'images in', out);
})();
