import { h, when, table, json } from '../dom.js';
import { api, qs } from '../api.js';
import { filterForm, pager, section } from '../ui.js';

export async function auditList({ params, navigate }) {
  const page = Number(params.get('page') || 0);
  const data = await api('GET', '/admin/audit-logs' + qs({ action: params.get('action'), target_type: params.get('target_type'),
    target_id: params.get('target_id'), actor_id: params.get('actor_id'), from: params.get('from'), to: params.get('to'), page, size: 25 }));
  return h('div', {}, h('h1', {}, 'Audit log'),
    h('p', { class: 'muted' }, 'Secret-looking values (passwords, hashes, tokens, signatures, keys) are masked before they reach this screen.'),
    section('Filters', filterForm([
      { name: 'action', label: 'Action' }, { name: 'target_type', label: 'Target type' }, { name: 'target_id', label: 'Target id' },
      { name: 'actor_id', label: 'Actor id' }, { name: 'from', label: 'From (UTC)' }, { name: 'to', label: 'To (UTC, exclusive)' },
    ], params, (v) => navigate('/audit', v))),
    h('section', { class: 'card' }, table([
      { title: 'When', cell: a => when(a.occurred_at) },
      { title: 'Who', cell: a => a.actor_username || (a.actor_type === 'SYSTEM' ? 'system' : (a.actor_id != null ? 'admin #' + a.actor_id : '—')) },
      { title: 'Action', cell: a => h('span', { class: 'mono' }, a.action) },
      { title: 'Target', cell: a => a.target_type + (a.target_id != null ? ' #' + a.target_id : '') },
      { title: 'IP', cell: a => a.ip_address || '' },
      { title: 'State', cell: a => (a.before_state || a.after_state) ? h('details', {}, h('summary', {}, 'view'),
        a.before_state ? [h('div', { class: 'muted' }, 'before'), json(a.before_state)] : null,
        a.after_state ? [h('div', { class: 'muted' }, 'after'), json(a.after_state)] : null) : '' },
    ], data.items, 'No audit rows match.'), pager(data, (n) => navigate('/audit', { ...Object.fromEntries(params), page: n }))));
}
