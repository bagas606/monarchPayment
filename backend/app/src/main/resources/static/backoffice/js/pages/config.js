import { h, badge, money, table } from '../dom.js';
import { api, qs } from '../api.js';
import { filterForm, prompt, section, errorNotice } from '../ui.js';

export async function configPage({ params, can, reload, navigate }) {
  const [cfg, amounts] = await Promise.all([api('GET', '/admin/configuration'),
    api('GET', '/admin/supported-amounts' + qs({ product_category: params.get('product_category') }))]);
  const flash = h('div', {});
  const edit = can('config:edit');
  const catalogEdit = can('catalog:edit');

  const change = async (c) => {
    const v = await prompt({ title: c.key, confirmText: 'Save', intro: c.description + ' Allowed ' + c.min + ' to ' + c.max + '. Takes effect at the next score or generation.',
      fields: [{ name: 'value', label: 'Value (now ' + c.value + ')', required: true, value: c.value, numeric: true }] });
    if (!v) return;
    try { await api('PUT', '/admin/configuration/' + c.key, { value: Number(v.value) }); reload(c.key + ' updated.'); }
    catch (e) { flash.replaceChildren(errorNotice(e)); }
  };
  const addAmount = async () => {
    const v = await prompt({ title: 'Add supported amount', confirmText: 'Add', intro: 'Orders for it are accepted at once but cannot be fulfilled until a generation that includes it is activated.',
      fields: [{ name: 'product_category', label: 'Product category', required: true }, { name: 'amount', label: 'Amount (Rp)', required: true, numeric: true }] });
    if (!v) return;
    try { const r = await api('POST', '/admin/supported-amounts', { product_category: v.product_category, amount: Number(v.amount) }); reload('Amount added. ' + r.note); }
    catch (e) { flash.replaceChildren(errorNotice(e)); }
  };
  const toggle = async (a) => {
    const next = a.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
    try { await api('PATCH', '/admin/supported-amounts/' + a.id, { status: next }); reload('Amount ' + a.amount + ' is now ' + next + '.'); }
    catch (e) { flash.replaceChildren(errorNotice(e)); }
  };

  return h('div', {}, h('h1', {}, 'Configuration'), flash,
    h('section', { class: 'card' }, h('h2', {}, 'Scoring, fee and generation limits'),
      h('p', { class: 'muted' }, 'The payment fee is 0 until the real gateway fee is confirmed, so net margin equals gross margin until then.'),
      table([{ title: 'Key', cell: c => h('span', { class: 'mono' }, c.key) }, { title: 'Value', num: true, cell: c => c.value }, { title: 'Default', num: true, cell: c => c.default },
        { title: 'Source', cell: c => c.is_default ? 'default' : badge('PENDING') }, { title: 'What it does', cell: c => c.description },
        { title: '', cell: c => edit ? h('button', { type: 'button', onclick: () => change(c) }, 'Change') : '' }], cfg.items)),
    h('section', { class: 'card' }, h('h2', {}, 'Supported amounts'),
      filterForm([{ name: 'product_category', label: 'Category' }], params, (v) => navigate('/config', v)),
      catalogEdit ? h('div', { class: 'actions' }, h('button', { type: 'button', class: 'primary', onclick: addAmount }, 'Add amount')) : null,
      table([{ title: 'Category', cell: a => a.product_category }, { title: 'Amount', num: true, cell: a => money(a.amount) }, { title: 'Status', cell: a => badge(a.status) },
        { title: 'Patterns in active generation', num: true, cell: a => a.status === 'ACTIVE' && a.active_patterns === 0 ? h('span', { class: 'badge warn' }, 'none: cannot be fulfilled') : a.active_patterns },
        { title: '', cell: a => catalogEdit ? h('button', { type: 'button', onclick: () => toggle(a) }, a.status === 'ACTIVE' ? 'Switch off' : 'Switch on') : '' }],
        amounts.items, 'No supported amounts.')));
}
