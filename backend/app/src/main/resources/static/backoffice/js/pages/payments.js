import { h, badge, money, when, table, kv } from '../dom.js';
import { api, qs } from '../api.js';
import { filterForm, pager, section } from '../ui.js';

export async function paymentsList({ params, navigate }) {
  const page = Number(params.get('page') || 0);
  const data = await api('GET', '/admin/payments' + qs({ status: params.get('status'), pg_reference: params.get('pg_reference'),
    parent_order_id: params.get('parent_order_id'), from: params.get('from'), to: params.get('to'), page, size: 25 }));
  return h('div', {}, h('h1', {}, 'Payments'),
    section('Filters', filterForm([
      { name: 'status', label: 'Status', options: ['PENDING', 'SUCCESS', 'FAILED', 'EXPIRED', 'REFUNDED'] },
      { name: 'pg_reference', label: 'Gateway reference' }, { name: 'parent_order_id', label: 'Order id' },
      { name: 'from', label: 'From (UTC)' }, { name: 'to', label: 'To (UTC, exclusive)' },
    ], params, (v) => navigate('/payments', v))),
    h('section', { class: 'card' }, table([
      { title: 'Payment', cell: p => h('a', { href: '#/payments/' + p.id }, '#' + p.id) },
      { title: 'Order', cell: p => h('a', { href: '#/orders/' + p.parent_order_id }, p.order_no) },
      { title: 'Status', cell: p => badge(p.status) }, { title: 'Amount', num: true, cell: p => money(p.amount) },
      { title: 'Gateway reference', cell: p => h('span', { class: 'mono' }, p.pg_reference || '') },
      { title: 'Paid', cell: p => when(p.paid_at) }, { title: 'QR expires', cell: p => when(p.expires_at) },
    ], data.items, 'No payments match.'), pager(data, (n) => navigate('/payments', { ...Object.fromEntries(params), page: n }))));
}

export async function paymentDetail({ id }) {
  const d = await api('GET', '/admin/payments/' + id);
  const p = d.payment;
  return h('div', {}, h('h1', {}, 'Payment #' + p.id, ' ', badge(p.status)),
    section('Payment', kv([['Order', h('a', { href: '#/orders/' + p.parent_order_id }, p.order_no)], ['Amount', money(p.amount)],
      ['Method', p.method], ['Gateway reference', p.pg_reference], ['QR issued', p.has_qr ? 'yes (payload not shown)' : 'no'],
      ['Paid', when(p.paid_at)], ['Refunded', when(p.refunded_at)], ['QR expires', when(p.expires_at)]])),
    section('Callbacks matched to this payment', table([
      { title: 'Received', cell: e => when(e.received_at) }, { title: 'Type', cell: e => e.event_type },
      { title: 'Signature', cell: e => e.signature_valid ? badge('ACTIVE') : badge('FAILED') },
      { title: 'Dedup key', cell: e => h('span', { class: 'mono' }, e.dedup_key) },
    ], d.events, 'No callbacks received yet.'),
    h('p', { class: 'muted' }, 'Raw gateway payloads are deliberately never shown here.')));
}

export async function callbacks({ params, navigate }) {
  const page = Number(params.get('page') || 0);
  const data = await api('GET', '/admin/webhook-events' + qs({ direction: params.get('direction'), source: params.get('source'),
    status: params.get('status'), from: params.get('from'), to: params.get('to'), page, size: 25 }));
  return h('div', {}, h('h1', {}, 'Webhook log'),
    h('p', { class: 'muted' }, 'Inbound payment callbacks and outbound partner webhooks. A callback whose signature fails is recorded here with status FAILED - it never reaches a payment. ',
      h('a', { href: '#/callbacks?status=FAILED' }, 'Show rejected only')),
    section('Filters', filterForm([
      { name: 'direction', label: 'Direction', options: ['INBOUND', 'OUTBOUND'] }, { name: 'source', label: 'Source' },
      { name: 'status', label: 'Status' }, { name: 'from', label: 'From (UTC)' }, { name: 'to', label: 'To (UTC, exclusive)' },
    ], params, (v) => navigate('/callbacks', v))),
    h('section', { class: 'card' }, table([
      { title: 'Received', cell: e => when(e.received_at) }, { title: 'Direction', cell: e => e.direction },
      { title: 'Source', cell: e => e.source }, { title: 'Event', cell: e => e.event_type },
      { title: 'Status', cell: e => badge(e.status) }, { title: 'Dedup key', cell: e => h('span', { class: 'mono' }, e.dedup_key || '') },
    ], data.items, 'Nothing logged for these filters.'), pager(data, (n) => navigate('/callbacks', { ...Object.fromEntries(params), page: n }))));
}
