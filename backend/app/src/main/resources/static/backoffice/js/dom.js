// Tiny DOM helpers. Everything that reaches the page goes through textContent / setAttribute for a known
// attribute - never innerHTML - because much of what this console displays (customer references, order
// metadata, callback URLs) is chosen by a partner, not by us.

export function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs || {})) {
    if (value === null || value === undefined || value === false) continue;
    if (key === 'class') el.className = value;
    else if (key.startsWith('on') && typeof value === 'function') el.addEventListener(key.slice(2), value);
    else if (key === 'text') el.textContent = value;
    else if (value === true) el.setAttribute(key, '');
    else el.setAttribute(key, String(value));
  }
  append(el, children);
  return el;
}

export function append(el, children) {
  for (const child of children.flat(Infinity)) {
    if (child === null || child === undefined || child === false) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return el;
}

/**
 * Like Element.append, but skips null / undefined / false. The native method turns a null argument into
 * the literal text "null" - which is exactly what an optional section (`cond ? node : null`) produces.
 * Never call .append directly with an argument that can be null.
 */
export function put(el, ...children) { return append(el, children); }

export function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); return el; }

const idr = new Intl.NumberFormat('id-ID', { style: 'currency', currency: 'IDR', maximumFractionDigits: 0 });
export function money(value) {
  if (value === null || value === undefined || value === '') return '';
  const n = Number(value);
  return Number.isFinite(n) ? idr.format(n) : String(value);
}

export function when(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return String(iso);
  return h('time', { datetime: iso, title: iso + ' (UTC)' }, d.toLocaleString());
}

/** Clock time only, for a value that is obviously today (a session's end). */
export function clockTime(iso) {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return h('time', { datetime: iso, title: iso + ' (UTC)' }, d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }));
}

const TONE = {
  SUCCESS: 'ok', PAID: 'ok', ACTIVE: 'ok', RESOLVED: 'ok', DELIVERED: 'ok', REFUNDED: 'ok', RECEIVED: 'ok', COMPENSATED: 'info',
  FAILED: 'bad', PARTIAL_FAILED: 'bad', EXHAUSTED: 'bad', EXPIRED: 'warn', CANCELLED: 'warn',
  REFUND_PENDING: 'warn', PAYMENT_PENDING: 'info', PENDING: 'info', EXECUTING: 'info', FULFILLING: 'info',
  OPEN: 'warn', INVESTIGATING: 'info', CREATED: 'info', DECOMPOSITION_SELECTED: 'info', TIMEOUT: 'bad', UNKNOWN: 'warn',
};
export function badge(value) {
  if (value === null || value === undefined) return '';
  return h('span', { class: 'badge ' + (TONE[value] || '') }, String(value));
}

export function notice(kind, message) {
  return h('div', { class: 'notice ' + kind, role: kind === 'error' ? 'alert' : 'status' }, message);
}

export function field(label, input) { return h('label', {}, label, input); }

export function table(columns, rows, emptyText) {
  if (!rows.length) return h('div', { class: 'empty' }, emptyText || 'Nothing to show.');
  return h('div', { class: 'table-wrap' },
    h('table', {},
      h('thead', {}, h('tr', {}, columns.map(c => h('th', { class: c.num ? 'num' : '' }, c.title)))),
      h('tbody', {}, rows.map(row => h('tr', {}, columns.map(c => h('td', { class: c.num ? 'num' : '' }, c.cell(row))))))));
}

export function kv(pairs) {
  const dl = h('dl', { class: 'kv' });
  for (const [k, v] of pairs) {
    if (v === null || v === undefined || v === '') continue;
    dl.append(h('dt', {}, k), h('dd', {}, v));
  }
  return dl;
}

export function json(value) { return h('pre', {}, JSON.stringify(value, null, 2)); }

/** datetime-local value -> ISO instant (the user's local time -> UTC), or '' */
export function toInstant(local) {
  if (!local) return '';
  const d = new Date(local);
  return Number.isNaN(d.getTime()) ? '' : d.toISOString();
}
