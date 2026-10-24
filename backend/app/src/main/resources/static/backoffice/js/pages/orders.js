import { h, put, badge, money, when, table, kv, json, notice } from '../dom.js';
import { api, qs } from '../api.js';
import { filterForm, pager, prompt, section, errorNotice } from '../ui.js';

const STATES = ['CREATED', 'PAYMENT_PENDING', 'PAID', 'DECOMPOSITION_SELECTED', 'FULFILLING', 'SUCCESS',
  'PARTIAL_FAILED', 'FAILED', 'REFUND_PENDING', 'REFUNDED', 'EXPIRED', 'CANCELLED'];

export async function ordersList({ params, navigate }) {
  const page = Number(params.get('page') || 0);
  const query = {
    state: params.get('state'), partner_code: params.get('partner_code'), product_code: params.get('product_code'),
    order_no: params.get('order_no'), customer_reference: params.get('customer_reference'),
    from: params.get('from'), to: params.get('to'), page, size: 25,
  };
  const data = await api('GET', '/admin/parent-orders' + qs(query));
  const go = (next) => navigate('/orders', { ...Object.fromEntries(params), page: next });

  const form = filterForm([
    { name: 'state', label: 'State', options: STATES },
    { name: 'order_no', label: 'Order no' },
    { name: 'customer_reference', label: 'Customer reference' },
    { name: 'partner_code', label: 'Partner' },
    { name: 'product_code', label: 'Product' },
    { name: 'from', label: 'From (UTC)', type: 'text' },
    { name: 'to', label: 'To (UTC, exclusive)', type: 'text' },
  ], params, (values) => navigate('/orders', values));

  return h('div', {},
    h('h1', {}, 'Orders'),
    h('p', { class: 'muted' }, 'Dates are ISO-8601 instants, e.g. 2026-10-01T00:00:00Z.'),
    section('Filters', form),
    h('section', { class: 'card' }, table([
      { title: 'Order', cell: o => h('a', { href: '#/orders/' + o.id }, o.order_no) },
      { title: 'State', cell: o => badge(o.state) },
      { title: 'Amount', num: true, cell: o => money(o.parent_amount) },
      { title: 'Partner', cell: o => o.partner_code || '' },
      { title: 'Product', cell: o => o.product_code || '' },
      { title: 'Customer ref', cell: o => o.customer_reference || '' },
      { title: 'Reseller txn', cell: o => o.reseller_txn_id || '' },
      { title: 'Created', cell: o => when(o.created_at) },
    ], data.items, 'No orders match these filters.'), pager(data, go)));
}

export async function orderDetail({ id, can, reload }) {
  const d = await api('GET', '/admin/parent-orders/' + id);
  const o = d.order;
  const out = h('div', {});
  const actionsSlot = h('div', {});
  const flash = h('div', {});

  const refundable = d.state === 'REFUND_PENDING' && can('refund:initiate');
  const actions = h('div', { class: 'actions' },
    refundable ? h('button', { class: 'danger', type: 'button', onclick: async () => {
      const v = await prompt({ title: 'Refund order ' + o.order_no, danger: true, confirmText: 'Refund',
        intro: 'This reverses the payment. Record why; it is written to the audit log with your name.',
        fields: [{ name: 'reason', label: 'Reason', required: true, multiline: true },
          { name: 'external_reference', label: 'Bank / gateway reference (for an out-of-band refund)' }] });
      if (!v) return;
      try {
        const r = await api('POST', '/admin/parent-orders/' + id + '/refund',
          { reason: v.reason, external_reference: v.external_reference || undefined });
        reload('Refunded. Refund reference ' + r.refundReference + (r.gatewayExecuted ? ' (executed at the gateway)' : ' (recorded as out-of-band)') + '.');
      } catch (e) { flash.replaceChildren(errorNotice(e)); }
    } }, 'Refund order…') : null);
  actionsSlot.append(actions);

  put(out,
    h('h1', {}, o.order_no, ' ', badge(d.state)), flash, actionsSlot,
    h('div', { class: 'grid' },
      section('Order', kv([
        ['Amount', money(o.parent_amount)], ['Partner', o.partner_code], ['Product', o.product_name ? o.product_name + ' (' + o.product_code + ')' : o.product_code],
        ['Source', o.order_source], ['Customer reference', o.customer_reference], ['Reseller txn id', o.reseller_txn_id],
        ['Callback host', d.callback_host], ['Created', when(o.created_at)], ['QR expires', when(o.expires_at)], ['Last updated', when(d.updated_at)]])),
      d.payment ? section('Payment', kv([
        ['Status', badge(d.payment.status)], ['Amount', money(d.payment.amount)], ['Method', d.payment.method],
        ['Gateway reference', d.payment.pg_reference], ['QR issued', d.payment.has_qr ? 'yes (payload not shown)' : 'no'],
        ['Paid', when(d.payment.paid_at)], ['Refunded', when(d.payment.refunded_at)],
        ['Detail', can('payment:view') ? h('a', { href: '#/payments/' + d.payment.id }, 'open payment #' + d.payment.id) : null]])) : section('Payment', h('p', { class: 'muted' }, 'No payment was created for this order.')),
      d.pattern ? section('Decomposition pattern #' + d.pattern.id, kv([
        ['Generation', d.pattern.generation_id], ['Amount', money(d.pattern.parent_amount)], ['Components', d.pattern.component_count + ' (' + d.pattern.total_quantity + ' units)'],
        ['Structural status', badge(d.pattern.structural_status)]]), json(d.pattern.components)) : null),
    section('Child orders', table([
      { title: '#', cell: c => c.sequence_no },
      { title: 'State', cell: c => badge(c.state) },
      { title: 'Provider / SKU', cell: c => [c.provider_code, c.provider_sku_code].filter(Boolean).join(' / ') },
      { title: 'Face value', num: true, cell: c => money(c.face_value) },
      { title: 'Qty', num: true, cell: c => c.quantity },
      { title: 'Attempts', num: true, cell: c => c.attempt_count },
      { title: 'Provider tx', cell: c => c.provider_transaction_status ? badge(c.provider_transaction_status) : '' },
      { title: 'Latency', num: true, cell: c => c.latency_ms != null ? c.latency_ms + ' ms' : '' },
      { title: '', cell: c => (c.state === 'FAILED' && can('retry:execute')) ? h('button', { type: 'button', onclick: async () => {
        const v = await prompt({ title: 'Retry child order #' + c.sequence_no, confirmText: 'Retry',
          intro: 'Dispatches this child to its provider again. Record why; it is audit-logged.',
          fields: [{ name: 'reason', label: 'Reason', required: true, multiline: true }] });
        if (!v) return;
        try {
          const r = await api('POST', '/admin/child-orders/' + c.id + '/retry', { reason: v.reason });
          reload('Retried: child is now ' + r.childState + ', order is ' + r.parentState + '.');
        } catch (e) { flash.replaceChildren(errorNotice(e)); }
      } }, 'Retry…') : '' },
    ], d.children, 'No child orders (the order was not paid or has no pattern).')),
    section('Partner webhook deliveries', table([
      { title: 'Event', cell: w => w.event_type }, { title: 'Status', cell: w => badge(w.status) },
      { title: 'Attempts', num: true, cell: w => w.attempt_count }, { title: 'Last attempt', cell: w => when(w.last_attempt_at) },
      { title: 'Next attempt', cell: w => when(w.next_attempt_at) }, { title: 'Last failure', cell: w => w.last_failure_reason || '' },
    ], d.webhook_deliveries, 'No outbound webhook was queued for this order.')),
    d.metadata ? section('Partner metadata', json(d.metadata)) : null,
    section('Timeline',
      notice('warn', d.timeline.note),
      h('ol', { class: 'timeline' }, d.timeline.entries.map(e => h('li', {},
        h('strong', {}, e.event), ' ', h('span', { class: 'muted' }, '[' + e.source + '] '), when(e.at), e.detail ? h('div', { class: 'muted' }, e.detail) : null)))));
  return out;
}
