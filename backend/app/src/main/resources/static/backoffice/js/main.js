import { h, clear, notice, clockTime } from './dom.js';
import { api, setCsrf, onSessionLost } from './api.js';
import { loginView } from './login.js';
import { errorNotice } from './ui.js';
import { ordersList, orderDetail } from './pages/orders.js';
import { paymentsList, paymentDetail, callbacks } from './pages/payments.js';
import { reconList, reconDetail } from './pages/recon.js';
import { auditList } from './pages/audit.js';
import { sessionsPage } from './pages/sessions.js';

const root = document.getElementById('app');
let me = null;
let signedOutMessage = null;

// path pattern -> { permission, list/detail handler, nav label }
const ROUTES = [
  { re: /^\/orders$/, perm: 'order:view', page: ordersList, nav: 'Orders', href: '/orders' },
  { re: /^\/orders\/(\d+)$/, perm: 'order:view', page: orderDetail, nav: 'Orders' },
  { re: /^\/payments$/, perm: 'payment:view', page: paymentsList, nav: 'Payments', href: '/payments' },
  { re: /^\/payments\/(\d+)$/, perm: 'payment:view', page: paymentDetail, nav: 'Payments' },
  { re: /^\/callbacks$/, perm: 'payment:view', page: callbacks, nav: 'Webhook log', href: '/callbacks' },
  { re: /^\/recon$/, perm: 'reconciliation:view', page: reconList, nav: 'Reconciliation', href: '/recon' },
  { re: /^\/recon\/(\d+)$/, perm: 'reconciliation:view', page: reconDetail, nav: 'Reconciliation' },
  { re: /^\/audit$/, perm: 'audit:view', page: auditList, nav: 'Audit log', href: '/audit' },
  { re: /^\/sessions$/, perm: 'session:revoke', page: sessionsPage, nav: 'Sessions', href: '/sessions' },
];

const can = (permission) => !!me && me.user.permissions.includes(permission);

function parseHash() {
  const raw = location.hash.replace(/^#/, '') || '/';
  const [path, query] = raw.split('?');
  return { path, params: new URLSearchParams(query || '') };
}

export function navigate(path, values, replace) {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(values || {})) {
    if (v !== undefined && v !== null && v !== '' && !(k === 'page' && Number(v) === 0)) q.set(k, v);
  }
  const target = '#' + path + (q.toString() ? '?' + q : '');
  if (replace) location.replace(target); else location.hash = target;
  if (location.hash === target) render(); // same hash: no hashchange event fires
}

onSessionLost(() => {
  if (!me) return;
  me = null; setCsrf(null);
  loginView(root, { message: signedOutMessage || 'Your session has ended. Please sign in again.', done: boot });
});

async function boot() {
  try {
    me = await api('GET', '/admin/auth/me', undefined, { quiet401: true });
    setCsrf(me.csrf_token);
  } catch (e) {
    me = null;
    return loginView(root, { done: boot });
  }
  render();
}

function firstAllowed() {
  const r = ROUTES.find(r => r.href && can(r.perm));
  return r ? r.href : null;
}

async function render(flash) {
  if (!me) return;
  const { path, params } = parseHash();
  clear(root);
  const main = h('main', { id: 'main', tabindex: '-1' });
  const route = ROUTES.map(r => ({ r, m: path.match(r.re) })).find(x => x.m);

  const allowed = ROUTES.filter(r => r.href && can(r.perm));
  root.append(h('header', { class: 'topbar' },
    h('span', { class: 'brand' }, 'PPOB2 Backoffice'),
    h('nav', { class: 'nav', 'aria-label': 'Main' }, allowed.map(r => h('a', { href: '#' + r.href,
      'aria-current': route && route.r.nav === r.nav ? 'page' : null }, r.nav))),
    h('div', { class: 'who' }, h('span', {}, me.user.username), h('span', { class: 'muted' }, 'session ends ', clockTime(me.expires_at)),
      h('button', { type: 'button', onclick: signOut }, 'Sign out'))), main);

  if (!route) {
    const home = firstAllowed();
    if (home && path === '/') return navigate(home, {}, true);
    main.append(home ? notice('warn', 'That page does not exist.') : notice('warn', 'Your role has no backoffice pages. Ask an administrator for access.'));
    return;
  }
  if (!can(route.r.perm)) {
    main.append(notice('error', 'You do not have permission to view this page (' + route.r.perm + ').'));
    return;
  }
  if (flash) main.append(notice('ok', flash));
  main.append(h('p', { class: 'muted' }, 'Loading…'));
  const loading = main.lastChild;
  try {
    const view = await route.r.page({
      params, id: route.m[1], can, navigate,
      reload: (message) => render(message),
    });
    loading.remove();
    main.append(view);
    main.focus({ preventScroll: true });
  } catch (e) {
    loading.remove();
    if (e.status === 401) return; // onSessionLost already took over
    main.append(e.status === 404 ? notice('warn', 'Not found.') : errorNotice(e));
  }
}

async function signOut() {
  signedOutMessage = 'You have been signed out.';
  try { await api('POST', '/admin/auth/logout', {}, { quiet401: true }); } catch (e) { /* already ended */ }
  me = null; setCsrf(null);
  loginView(root, { message: signedOutMessage, done: boot });
  signedOutMessage = null;
}

window.addEventListener('hashchange', () => render());
boot();
