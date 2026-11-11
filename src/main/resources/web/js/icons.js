// Item icons and names.
// Textures are the official Minecraft ones, downloaded once by the plugin from Mojang.
// Flat items are drawn as-is; blocks are drawn as small isometric cubes, like in the game inventory.
// When a texture is missing, a neat colored tile with initials is drawn instead.
import { h } from './util.js';

let assets = { items: new Set(), blocks: new Set(), ready: false };
let lang = {};
let readyPromise = null;

export function initAssets() {
  if (!readyPromise) {
    readyPromise = Promise.all([
      fetch('/assets/index.json').then((r) => r.json()).then((j) => {
        assets = { items: new Set(j.items || []), blocks: new Set(j.blocks || []), ready: !!j.ready };
      }).catch(() => {}),
      fetch('/assets/lang.json').then((r) => r.json()).then((j) => { lang = j || {}; }).catch(() => {}),
    ]);
  }
  return readyPromise;
}

export function assetsReady() { return assets.ready; }

export function prettyId(id) {
  return String(id).split('_').filter(Boolean).map((w) => w[0].toUpperCase() + w.slice(1)).join(' ');
}

export function itemName(id) {
  if (id === '_other') return 'Autres';
  return lang['item.minecraft.' + id] || lang['block.minecraft.' + id] || prettyId(id);
}

const BLANK = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7';
const cache = new Map();
const images = new Map();

// Icons are drawn only when they scroll into view (long inventories stay fast on phones).
const lazy = 'IntersectionObserver' in window ? new IntersectionObserver((entries) => {
  for (const e of entries) {
    if (!e.isIntersecting) continue;
    lazy.unobserve(e.target);
    iconUrl(e.target.dataset.id).then((url) => { e.target.src = url; });
  }
}, { rootMargin: '200px' }) : null;

/** <img> for an item id; its picture is filled in as soon as it is drawn. */
export function itemIcon(id, cls = '') {
  const img = h('img', { class: 'ic ' + cls, alt: '', title: itemName(id), draggable: 'false', dataset: { id } });
  img.src = BLANK;
  if (lazy) lazy.observe(img);
  else iconUrl(id).then((url) => { img.src = url; });
  return img;
}

export function iconUrl(id) {
  if (!cache.has(id)) {
    cache.set(id, initAssets().then(() => render(id)).catch(() => fallbackTile(id)));
  }
  return cache.get(id);
}

// ───────── drawing ─────────

function canvas(size = 64) {
  const c = document.createElement('canvas');
  c.width = size;
  c.height = size;
  return c;
}

function ctx(c) {
  const g = c.getContext('2d');
  g.imageSmoothingEnabled = false;
  return g;
}

function load(path) {
  if (!images.has(path)) {
    images.set(path, new Promise((resolve, reject) => {
      const img = new Image();
      img.onload = () => resolve(img);
      img.onerror = reject;
      img.src = '/tex/' + path + '.png';
    }));
  }
  return images.get(path);
}

function tinted(img, color) {
  const w = img.width;
  const c = canvas(w);
  c.height = img.height;
  const g = ctx(c);
  g.drawImage(img, 0, 0);
  g.globalCompositeOperation = 'multiply';
  g.fillStyle = color;
  g.fillRect(0, 0, w, img.height);
  g.globalCompositeOperation = 'destination-in';
  g.drawImage(img, 0, 0);
  return c;
}

function tintFor(name) {
  if (name === 'grass_block_top' || /^(short_grass|tall_grass|fern|large_fern|vine|bush)$/.test(name)) return '#7FB238';
  if (name === 'birch_leaves') return '#80A755';
  if (name === 'spruce_leaves') return '#619961';
  if (/^(oak|jungle|acacia|dark_oak|mangrove)_leaves$/.test(name)) return '#59AE30';
  if (name === 'lily_pad') return '#208030';
  if (/^leather_(helmet|chestplate|leggings|boots|horse_armor)$/.test(name)) return '#A06540';
  return null;
}

async function flat(path, name) {
  const img = await load(path);
  const t = tintFor(name);
  const src = t ? tinted(img, t) : img;
  const c = canvas(64);
  const g = ctx(c);
  const w = img.width;
  g.drawImage(src, 0, 0, w, w, 2, 2, 60, 60);
  return c.toDataURL();
}

// Isometric faces: [a, b, c, d, e, f] transforms mapping a 16×16 texture onto the 64×64 canvas.
const TOP = [28 / 16, -14 / 16, 28 / 16, 14 / 16, 4, 16];
const LEFT = [28 / 16, 14 / 16, 0, 2, 4, 16];
const RIGHT = [28 / 16, -14 / 16, 0, 2, 32, 30];

function drawFace(g, src, m, shade, size) {
  const layer = canvas(64);
  const lg = ctx(layer);
  lg.setTransform(...m);
  if (typeof src === 'string') {
    lg.fillStyle = src;
    lg.fillRect(-0.06, -0.06, 16.12, 16.12);
  } else {
    lg.drawImage(src, 0, 0, size, size, -0.06, -0.06, 16.12, 16.12);
  }
  if (shade) {
    lg.globalCompositeOperation = 'source-atop';
    lg.fillStyle = `rgba(0,0,0,${shade})`;
    lg.fillRect(-1, -1, 18, 18);
  }
  g.drawImage(layer, 0, 0);
  return lg;
}

async function cube(f) {
  const [t, l, r] = await Promise.all([f.top, f.left, f.right].map((n) => load('block/' + n)));
  const c = canvas(64);
  const g = ctx(c);
  const tt = tintFor(f.top);
  drawFace(g, tt ? tinted(t, tt) : t, TOP, 0, t.width);
  drawFace(g, l, LEFT, 0.2, l.width);
  drawFace(g, r, RIGHT, 0.4, r.width);
  return c.toDataURL();
}

const WOODS = ['oak', 'spruce', 'birch', 'jungle', 'acacia', 'dark_oak', 'mangrove', 'cherry', 'pale_oak', 'bamboo', 'crimson', 'warped'];
const OVERRIDES = {
  bookshelf: { top: 'oak_planks', left: 'bookshelf', right: 'bookshelf' },
  piston: { top: 'piston_top', left: 'piston_side', right: 'piston_side' },
  sticky_piston: { top: 'piston_top_sticky', left: 'piston_side', right: 'piston_side' },
  tnt: { top: 'tnt_top', left: 'tnt_side', right: 'tnt_side' },
  furnace: { top: 'furnace_top', left: 'furnace_front', right: 'furnace_side' },
  blast_furnace: { top: 'blast_furnace_top', left: 'blast_furnace_front', right: 'blast_furnace_side' },
  smoker: { top: 'smoker_top', left: 'smoker_front', right: 'smoker_side' },
  observer: { top: 'observer_top', left: 'observer_front', right: 'observer_side' },
  chiseled_bookshelf: { top: 'chiseled_bookshelf_top', left: 'chiseled_bookshelf_occupied', right: 'chiseled_bookshelf_side' },
  dispenser: { top: 'furnace_top', left: 'dispenser_front', right: 'furnace_side' },
  dropper: { top: 'furnace_top', left: 'dropper_front', right: 'furnace_side' },
  crafter: { top: 'crafter_top', left: 'crafter_south', right: 'crafter_east' },
  cartography_table: { top: 'cartography_table_top', left: 'cartography_table_side3', right: 'cartography_table_side1' },
  fletching_table: { top: 'fletching_table_top', left: 'fletching_table_front', right: 'fletching_table_side' },
  smithing_table: { top: 'smithing_table_top', left: 'smithing_table_front', right: 'smithing_table_side' },
  respawn_anchor: { top: 'respawn_anchor_top_off', left: 'respawn_anchor_side0', right: 'respawn_anchor_side0' },
};

function bases(id) {
  const x = id.replace(/^waxed_/, '').replace(/^infested_/, '');
  const out = [x];
  const strip = x.replace(/_(stairs|slab|wall|fence_gate|fence|pressure_plate|button)$/, '');
  if (strip !== x) {
    out.push(strip);
    if (WOODS.includes(strip)) out.push(strip + '_planks');
    out.push(strip + 's', strip.replace(/brick$/, 'bricks'), strip.replace(/tile$/, 'tiles'), strip + '_block');
  }
  if (/_wood$/.test(x)) out.push(x.replace(/_wood$/, '_log'));
  if (/_hyphae$/.test(x)) out.push(x.replace(/_hyphae$/, '_stem'));
  if (/_block$/.test(x)) out.push(x.replace(/_block$/, ''));
  out.push(x + '_block');
  return [...new Set(out)];
}

function pick(names) {
  for (const n of names) if (assets.blocks.has(n)) return n;
  return null;
}

function cubeFaces(id) {
  const o = OVERRIDES[id];
  if (o && assets.blocks.has(o.top) && assets.blocks.has(o.left) && assets.blocks.has(o.right)) return o;
  const sideOnly = /_wood$|_hyphae$/.test(id);
  for (const b of bases(id)) {
    const side = pick([b + '_side', b, b + '_front']);
    const front = pick([b + '_front', b + '_side', b]);
    const top = sideOnly ? side : pick([b + '_top', b + '_end', b]);
    if (side || top) return { top: top || side, left: front || side || top, right: side || top };
  }
  return null;
}

const FLAT = /(sapling|torch$|^rail$|_rail$|ladder|vine$|lily_pad|cobweb|mushroom$|dead_bush|short_grass|tall_grass|fern$|sugar_cane|seagrass|_bars$|_pane$|tulip|orchid|allium|bluet|daisy|cornflower|lily_of_the_valley|wither_rose|poppy|dandelion|lever|tripwire_hook|_coral$|_coral_fan$|bamboo$|petals|roots$|sprouts$|lantern$|chain$|eyeblossom|fungus$|propagule|spore_blossom|hanging_moss|glow_lichen|sculk_vein|pointed_dripstone|dripleaf|cactus_flower|bush$|leaf_litter|wildflowers|sunflower|lilac|rose_bush|peony|pitcher_plant|torchflower|kelp$|nether_wart$|sweet_berry_bush|cave_vines|twisting_vines|weeping_vines|frogspawn|candle$)/;

// ───────── procedural icons (things drawn by the game as 3D models) ─────────

const DYES = {
  white: '#E9ECEC', orange: '#F07613', magenta: '#BD44B3', light_blue: '#3AAFD9', yellow: '#F8C527', lime: '#70B919',
  pink: '#ED8DAC', gray: '#3E4447', light_gray: '#8E8E86', cyan: '#158991', purple: '#792AAC', blue: '#35399D',
  brown: '#724728', green: '#546D1B', red: '#A12722', black: '#141519',
};

function shade(hex, k) {
  const n = parseInt(hex.slice(1), 16);
  const r = Math.round(((n >> 16) & 255) * k);
  const g = Math.round(((n >> 8) & 255) * k);
  const b = Math.round((n & 255) * k);
  return `rgb(${Math.min(255, r)},${Math.min(255, g)},${Math.min(255, b)})`;
}

function modelCube(top, side, band, latch) {
  const c = canvas(64);
  const g = ctx(c);
  drawFace(g, top, TOP, 0, 16);
  for (const [m, k] of [[LEFT, 0.2], [RIGHT, 0.4]]) {
    const lg = drawFace(g, side, m, k, 16);
    lg.globalCompositeOperation = 'source-over';
    lg.setTransform(...m);
    lg.fillStyle = band;
    lg.fillRect(0, 5, 16, 1.2);
    if (latch && m === LEFT) {
      lg.fillStyle = latch;
      lg.fillRect(7, 3.5, 2, 4);
    }
    g.drawImage(lg.canvas, 0, 0);
  }
  return c.toDataURL();
}

function chestIcon(id) {
  if (id === 'ender_chest') return modelCube('#1F4744', '#173A3A', '#08100F', '#62D6B5');
  if (id.includes('copper')) {
    const base = id.includes('oxidized') ? '#4FA88B' : id.includes('weathered') ? '#6C9E73' : id.includes('exposed') ? '#A1806B' : '#C46A4C';
    return modelCube(shade(base, 1.08), base, shade(base, 0.45), '#F2C9A7');
  }
  return modelCube(id.startsWith('trapped') ? '#B07433' : '#A86D2E', '#94602A', '#3A2611', '#D8D8D8');
}

function shulkerIcon(id) {
  const color = id === 'shulker_box' ? '#8D63A0' : DYES[id.replace(/_shulker_box$/, '')] || '#8D63A0';
  return modelCube(shade(color, 1.1), color, shade(color, 0.55), null);
}

function hashHue(id) {
  let x = 7;
  for (const ch of id) x = (x * 33 + ch.charCodeAt(0)) >>> 0;
  return x % 360;
}

function fallbackTile(id) {
  const c = canvas(64);
  const g = c.getContext('2d');
  const hue = hashHue(id);
  g.fillStyle = id === '_other' ? '#8E8E93' : `hsl(${hue} 42% 52%)`;
  g.beginPath();
  if (g.roundRect) g.roundRect(6, 6, 52, 52, 12);
  else g.rect(6, 6, 52, 52);
  g.fill();
  g.fillStyle = '#fff';
  g.font = '700 22px -apple-system, system-ui, sans-serif';
  g.textAlign = 'center';
  g.textBaseline = 'middle';
  const name = itemName(id);
  const letters = id === '_other' ? '•••' : name.split(/\s+/).filter((w) => w.length > 2 || name.split(/\s+/).length === 1).slice(0, 2).map((w) => w[0]).join('').toUpperCase() || name.slice(0, 2);
  g.fillText(letters, 32, 33);
  return c.toDataURL();
}

async function render(id) {
  if (id === '_other') return fallbackTile(id);
  if (/^(trapped_)?chest$|copper_chest$|^ender_chest$/.test(id)) return chestIcon(id);
  if (/shulker_box$/.test(id)) return shulkerIcon(id);
  if (!assets.ready) return fallbackTile(id);
  try {
    if (assets.items.has(id)) return await flat('item/' + id, id);
    if (FLAT.test(id)) {
      const n = pick([id, id.replace(/_pane$/, ''), id + '_top', id + '_front', id.replace(/^potted_/, ''), id + '_stage3', id + '0']);
      if (n) return await flat('block/' + n, n);
    }
    const faces = cubeFaces(id);
    if (faces) return await cube(faces);
  } catch {
    // fall through to the generated tile
  }
  return fallbackTile(id);
}
