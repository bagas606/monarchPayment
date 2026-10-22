import { h, badge, money, when, table, kv } from '../dom.js';
import { api, qs } from '../api.js';
import { filterForm, pagerByTotal, section, errorNotice, prompt } from '../ui.js';

const TYPES = ['PAYMENT_VS_PG', 'PAYMENT_VS_SETTLEMENT', 'ORDER_VS_FULFILLMENT', 'PROVIDER_VS_REPORT', 'MARGIN_EXPECTED_VS_ACTUAL'];

function refLink(r, can) {
  if (r.reference_type === 'PARENT_ORDER' && can('order:view')) return h('a', { href: '#/orders/' + r.reference_id }, 'order #' + r.reference_id);
  if (r.reference_type === 'PAYMENT' && can('payment:view')) return h('a', { href: '#/payments/' + r.reference_id }, 'payment #' + r.reference_id);
  return (r.reference_type || '') + ' #' + (r.reference_id ?? '');
}

export async function reconList({ params, navigate, can }) {
  const page = Number(params.get('page') || 0);
  const data = await api('GET', '/admin/reconciliations' + qs({ status: params.get('status'), recon_type: params.get('recon_type'),
    reference_id: params.get('reference_id'), from_date: params.get('from_date'), to_date: params.get('to_date'), page, size: 25 }));
  return h('div', {}, h('h1', {}, 'Reconciliation'),
    section('Filters', filterForm([
      { name: 'status', label: 'Status', options: ['OPEN', 'INVESTIGATING', 'RESOLVED'] },
      { name: 'recon_type', label: 'Type', options: TYPES }, { name: 'reference_id', label: 'Reference id' },
      { name: 'from_date', label: 'From date', type: 'date' }, { name: 'to_date', label: 'To date', type: 'date' },
    ], params, (v) => navigate('/recon', v))),
    h('section', { class: 'card' }, table([
      { title: 'ID', cell: r => h('a', { href: '#/recon/' + r.id }, '#' + r.id) }, { title: 'Type', cell: r => r.recon_type },
      { title: 'Status', cell: r => badge(r.status) }, { title: 'Reference', cell: r => refLink(r, can) },
      { title: 'Expected', num: true, cell: r => money(r.expected_value) }, { title: 'Actual', num: true, cell: r => money(r.actual_value) },
      { title: 'Discrepancy', num: true, cell: r => money(r.discrepancy) }, { title: 'Date', cell: r => r.recon_date },
    ], data.items, 'No reconciliation records match.'), pagerByTotal(data, (n) => navigate('/recon', { ...Object.fromEntries(params), page: n }))));
}

export async function reconDetail({ id, can, reload }) {
  const r = await api('GET', '/admin/reconciliations/' + id);
  const flash = h('div', {});
  const act = (verb, label, intro) => h('button', { type: 'button', class: verb === 'resolve' ? 'primary' : '', onclick: async () => {
    const v = await prompt({ title: label + ' #' + id, intro, confirmText: label, fields: [] });
    if (!v) return;
    try { const res = await api('POST', '/admin/reconciliations/' + id + '/' + verb, {}); reload(res.applied ? 'Done: now ' + res.status + '.' : 'No change: it was already ' + res.status + '.'); }
    catch (e) { flash.replaceChildren(errorNotice(e)); }
  } }, label);
  return h('div', {}, h('h1', {}, 'Reconciliation #' + id, ' ', badge(r.status)), flash,
    h('div', { class: 'actions' },
      r.status === 'OPEN' && can('reconciliation:investigate') ? act('investigate', 'Investigate', 'Marks this record as being looked at. Recorded with your name.') : null,
      r.status !== 'RESOLVED' && can('reconciliation:resolve') ? act('resolve', 'Resolve', 'Closes the record and records you as the resolver. There is no free-text note yet; add the context to your ticket.') : null),
    section('Record', kv([['Type', r.recon_type], ['Reference', refLink(r, can)], ['Date', r.recon_date],
      ['Expected', money(r.expected_value)], ['Actual', money(r.actual_value)], ['Discrepancy', money(r.discrepancy)],
      ['Resolved by', r.resolved_by ?? null], ['Resolved at', when(r.resolved_at)]])));
}
