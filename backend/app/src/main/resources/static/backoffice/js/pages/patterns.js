import { h, badge, money, when, table, kv } from '../dom.js';
import { api, qs } from '../api.js';
import { filterForm, pager, section } from '../ui.js';

const comps = (p) => p.components.map(c => c.provider_sku_code + ' ×' + c.quantity).join(' + ');
const pct = (v) => (v === null || v === undefined ? '' : Number(v).toFixed(2) + '%');

export async function patternsList({ params, navigate }) {
  const page = Number(params.get('page') || 0);
  const data = await api('GET', '/admin/patterns' + qs({ generation_id: params.get('generation_id'), parent_amount: params.get('parent_amount'),
    product_id: params.get('product_id'), structural_status: params.get('structural_status'), eligible: params.get('eligible'), page, size: 25 }));
  return h('div', {}, h('h1', {}, 'Patterns'),
    h('p', { class: 'muted' }, 'Defaults to the ACTIVE generation. Economics are today\'s; a pattern with none is not routable.'),
    section('Filters', filterForm([{ name: 'parent_amount', label: 'Amount (Rp)' }, { name: 'product_id', label: 'Product id' }, { name: 'generation_id', label: 'Generation id' },
      { name: 'structural_status', label: 'Structure', options: ['VALID', 'INVALIDATED'] }, { name: 'eligible', label: 'Eligible', options: ['true', 'false'] }],
      params, (v) => navigate('/patterns', v))),
    h('section', { class: 'card' }, table([
      { title: 'ID', cell: p => h('a', { href: '#/patterns/' + p.id }, '#' + p.id) }, { title: 'Amount', num: true, cell: p => money(p.parent_amount) },
      { title: 'Components', cell: p => comps(p) }, { title: 'Units', num: true, cell: p => p.total_quantity },
      { title: 'Cost', num: true, cell: p => p.economics_today ? money(p.economics_today.provider_cost_total) : '' },
      { title: 'Net margin', num: true, cell: p => p.economics_today ? pct(p.economics_today.net_margin_pct) : '' },
      { title: 'Score', num: true, cell: p => p.economics_today ? Number(p.economics_today.score).toFixed(2) : '' },
      { title: 'Eligible', cell: p => p.economics_today ? badge(p.economics_today.eligible ? 'ELIGIBLE' : 'INELIGIBLE') : badge('NO ECONOMICS') },
      { title: 'Structure', cell: p => p.structural_status === 'VALID' ? '' : badge(p.structural_status) }], data.items, 'No patterns match.'),
      pager(data, (n) => navigate('/patterns', { ...Object.fromEntries(params), page: n }))));
}

export async function patternDetail({ id }) {
  const d = await api('GET', '/admin/patterns/' + id);
  const p = d.pattern;
  return h('div', {}, h('h1', {}, 'Pattern #' + id, ' ', p.structural_status === 'VALID' ? null : badge(p.structural_status)),
    section('Pattern', kv([['Generation', h('a', { href: '#/generations/' + p.generation_id }, '#' + p.generation_id)], ['Amount', money(p.parent_amount)],
      ['Child orders', p.component_count], ['Units', p.total_quantity], ['Structure', p.structural_status], ['Hash', h('span', { class: 'mono' }, p.pattern_hash)]])),
    section('Components', table([{ title: 'SKU', cell: c => c.provider_sku_code }, { title: 'Quantity', num: true, cell: c => c.quantity },
      { title: 'Face value', num: true, cell: c => money(c.face_value) }, { title: 'Subtotal', num: true, cell: c => money(c.face_value * c.quantity) }], p.components)),
    h('section', { class: 'card' }, h('h2', {}, 'Economics by day'), table([
      { title: 'Day', cell: e => e.snapshot_date }, { title: 'Provider cost', num: true, cell: e => money(e.provider_cost_total) },
      { title: 'Gross profit', num: true, cell: e => money(e.gross_profit) }, { title: 'Payment fee', num: true, cell: e => money(e.payment_fee) },
      { title: 'Net', num: true, cell: e => money(e.net_contribution) }, { title: 'Net margin', num: true, cell: e => pct(e.net_margin_pct) },
      { title: 'Score', num: true, cell: e => Number(e.score).toFixed(2) }, { title: 'Eligible', cell: e => badge(e.eligible ? 'ELIGIBLE' : 'INELIGIBLE') }],
      d.economics_history, 'No economics yet.')));
}
