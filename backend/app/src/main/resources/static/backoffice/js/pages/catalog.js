import { h, badge, money, table } from '../dom.js';
import { api, qs } from '../api.js';
import { filterForm, prompt, section, errorNotice } from '../ui.js';

const num = (v) => (v === '' || v === undefined ? undefined : Number(v));

/** Runs a mutation, reloads with a message on success, shows the server's reason in `flash` on failure. */
async function run(flash, reload, call, message) {
  try {
    const result = await call();
    reload(typeof message === 'function' ? message(result) : message);
  } catch (e) { flash.replaceChildren(errorNotice(e)); }
}

export async function catalogPage({ params, can, reload, navigate }) {
  const [products, providers, skus] = await Promise.all([
    api('GET', '/admin/products'), api('GET', '/admin/providers'),
    api('GET', '/admin/provider-skus' + qs({ product_id: params.get('product_id'), provider_id: params.get('provider_id'), status: params.get('status') })),
  ]);
  const flash = h('div', {});
  const edit = can('catalog:edit');
  const priceEdit = can('pricing:edit');

  const addProduct = async () => {
    const v = await prompt({ title: 'Add product', confirmText: 'Add', fields: [
      { name: 'code', label: 'Code', required: true }, { name: 'name', label: 'Name', required: true },
      { name: 'category', label: 'Category (groups supported amounts)', required: true }] });
    if (v) run(flash, reload, () => api('POST', '/admin/products', v), (r) => 'Product added. ' + (r.note || ''));
  };
  const editProduct = async (p) => {
    const v = await prompt({ title: 'Edit ' + p.code, confirmText: 'Save', fields: [
      { name: 'name', label: 'Name', required: true, value: p.name }, { name: 'status', label: 'Status', options: ['ACTIVE', 'INACTIVE'], value: p.status }] });
    if (v) run(flash, reload, () => api('PATCH', '/admin/products/' + p.id, v), 'Product updated.');
  };
  const addProvider = async () => {
    const v = await prompt({ title: 'Add provider', confirmText: 'Add', fields: [
      { name: 'code', label: 'Code', required: true }, { name: 'name', label: 'Name', required: true },
      { name: 'timeout_ms', label: 'Timeout (ms)', value: '5000', numeric: true }, { name: 'rate_limit_per_min', label: 'Rate limit per minute (optional)', numeric: true }] });
    if (v) run(flash, reload, () => api('POST', '/admin/providers', { code: v.code, name: v.name, timeout_ms: num(v.timeout_ms), rate_limit_per_min: num(v.rate_limit_per_min) }), 'Provider added.');
  };
  const editProvider = async (p) => {
    const v = await prompt({ title: 'Edit ' + p.code, confirmText: 'Save', intro: 'DISABLED takes the provider out of routing; DEGRADED keeps it eligible.', fields: [
      { name: 'name', label: 'Name', required: true, value: p.name }, { name: 'status', label: 'Status', options: ['ACTIVE', 'DEGRADED', 'DISABLED'], value: p.status },
      { name: 'timeout_ms', label: 'Timeout (ms)', value: p.timeout_ms, numeric: true }, { name: 'rate_limit_per_min', label: 'Rate limit per minute', value: p.rate_limit_per_min ?? '', numeric: true }] });
    if (v) run(flash, reload, () => api('PATCH', '/admin/providers/' + p.id, { name: v.name, status: v.status, timeout_ms: num(v.timeout_ms), rate_limit_per_min: num(v.rate_limit_per_min) }), 'Provider updated.');
  };
  const addSku = async () => {
    const v = await prompt({ title: 'Add SKU', confirmText: 'Add', intro: 'Use the ids shown in the Products and Providers tables. A new SKU has no price and takes part in routing only after a new generation is activated.', fields: [
      { name: 'provider_id', label: 'Provider id', required: true, numeric: true }, { name: 'product_id', label: 'Product id', required: true, numeric: true },
      { name: 'provider_sku_code', label: 'Provider SKU code', required: true }, { name: 'face_value', label: 'Face value (Rp)', required: true, numeric: true },
      { name: 'quota_daily', label: 'Daily quota (optional)', numeric: true }] });
    if (v) run(flash, reload, () => api('POST', '/admin/provider-skus', { provider_id: num(v.provider_id), product_id: num(v.product_id), provider_sku_code: v.provider_sku_code,
      face_value: num(v.face_value), quota_daily: num(v.quota_daily) }), (r) => 'SKU added. ' + (r.note || ''));
  };
  const editSku = async (s) => {
    const v = await prompt({ title: 'Edit ' + s.provider_sku_code, confirmText: 'Save', danger: true,
      intro: 'Changing the face value invalidates every pattern that contains this SKU until a new generation is activated. Leave it unchanged to edit only status or quota.',
      fields: [{ name: 'status', label: 'Status', options: ['ACTIVE', 'INACTIVE'], value: s.status }, { name: 'quota_daily', label: 'Daily quota', value: s.quota_daily ?? '', numeric: true },
        { name: 'face_value', label: 'Face value (Rp)', value: s.face_value, numeric: true }, { name: 'reason', label: 'Reason (required if the face value changes)' }] });
    if (!v) return;
    const body = { status: v.status, quota_daily: num(v.quota_daily), face_value: num(v.face_value), reason: v.reason || undefined };
    run(flash, reload, () => api('PATCH', '/admin/provider-skus/' + s.id, body),
      (r) => r.patterns_invalidated !== undefined ? 'SKU updated: ' + r.patterns_invalidated + ' pattern(s) invalidated, ' + (r.lost_coverage || []).length + ' (product, amount) pair(s) lost coverage.' : 'SKU updated.');
  };
  const setPrice = async (s) => {
    const v = await prompt({ title: 'New price for ' + s.provider_sku_code, confirmText: 'Set price', danger: true,
      intro: 'Creates a new price version (the old one is kept) and re-scores the patterns that contain this SKU now.',
      fields: [{ name: 'provider_cost', label: 'Provider cost (Rp), now ' + (s.current_cost ?? 'none'), required: true, numeric: true }, { name: 'reason', label: 'Reason', required: true, multiline: true }] });
    if (v) run(flash, reload, () => api('POST', '/admin/provider-skus/' + s.id + '/prices', { provider_cost: num(v.provider_cost), reason: v.reason }),
      (r) => 'Price v' + r.pricing_version + ' set; ' + r.patterns_rescored + ' pattern(s) re-scored.');
  };
  const history = async (s) => {
    const data = await api('GET', '/admin/provider-skus/' + s.id + '/prices');
    historySlot.replaceChildren(section('Price history: ' + s.provider_sku_code, table([
      { title: 'Version', num: true, cell: r => r.pricing_version }, { title: 'Cost', num: true, cell: r => money(r.provider_cost) },
      { title: 'From', cell: r => r.effective_from }, { title: 'Until', cell: r => r.effective_until || 'current' }], data.items)));
  };
  const historySlot = h('div', {});

  return h('div', {}, h('h1', {}, 'Catalog'), flash,
    h('section', { class: 'card' }, h('h2', {}, 'Products'),
      edit ? h('div', { class: 'actions' }, h('button', { type: 'button', class: 'primary', onclick: addProduct }, 'Add product')) : null,
      table([{ title: 'ID', num: true, cell: p => p.id }, { title: 'Code', cell: p => p.code }, { title: 'Name', cell: p => p.name },
        { title: 'Category', cell: p => p.category }, { title: 'Status', cell: p => badge(p.status) }, { title: 'Active SKUs', num: true, cell: p => p.active_skus },
        { title: '', cell: p => edit ? h('button', { type: 'button', onclick: () => editProduct(p) }, 'Edit') : '' }], products.items, 'No products.')),
    h('section', { class: 'card' }, h('h2', {}, 'Providers'),
      edit ? h('div', { class: 'actions' }, h('button', { type: 'button', class: 'primary', onclick: addProvider }, 'Add provider')) : null,
      table([{ title: 'ID', num: true, cell: p => p.id }, { title: 'Code', cell: p => p.code }, { title: 'Name', cell: p => p.name },
        { title: 'Status', cell: p => badge(p.status) }, { title: 'Timeout ms', num: true, cell: p => p.timeout_ms }, { title: 'Rate/min', num: true, cell: p => p.rate_limit_per_min ?? '' },
        { title: 'Active SKUs', num: true, cell: p => p.active_skus },
        { title: '', cell: p => edit ? h('button', { type: 'button', onclick: () => editProvider(p) }, 'Edit') : '' }], providers.items, 'No providers.')),
    h('section', { class: 'card' }, h('h2', {}, 'Provider SKUs'),
      filterForm([{ name: 'product_id', label: 'Product id' }, { name: 'provider_id', label: 'Provider id' }, { name: 'status', label: 'Status', options: ['ACTIVE', 'INACTIVE'] }],
        params, (v) => navigate('/catalog', v)),
      edit ? h('div', { class: 'actions' }, h('button', { type: 'button', class: 'primary', onclick: addSku }, 'Add SKU')) : null,
      table([{ title: 'ID', num: true, cell: s => s.id }, { title: 'SKU', cell: s => s.provider_sku_code }, { title: 'Provider', cell: s => s.provider_code },
        { title: 'Product', cell: s => s.product_code }, { title: 'Face value', num: true, cell: s => money(s.face_value) }, { title: 'Status', cell: s => badge(s.status) },
        { title: 'Quota/day', num: true, cell: s => s.quota_daily ?? '' }, { title: 'Cost', num: true, cell: s => s.current_cost == null ? h('span', { class: 'badge warn' }, 'unpriced') : money(s.current_cost) },
        { title: 'Ver', num: true, cell: s => s.current_pricing_version ?? '' },
        { title: '', cell: s => h('span', { class: 'actions' },
          edit ? h('button', { type: 'button', onclick: () => editSku(s) }, 'Edit') : null,
          priceEdit ? h('button', { type: 'button', onclick: () => setPrice(s) }, 'Price') : null,
          h('button', { type: 'button', onclick: () => history(s).catch(e => flash.replaceChildren(errorNotice(e))) }, 'History')) }],
        skus.items, 'No SKUs match.')),
    historySlot);
}
