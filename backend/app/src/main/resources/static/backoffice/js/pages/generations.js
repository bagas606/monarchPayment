import { h, badge, when, table, kv } from '../dom.js';
import { api } from '../api.js';
import { pager, prompt, section, errorNotice } from '../ui.js';

const phase = (g) => g.status === 'BUILDING' ? 'BUILDING' : (g.status === 'VALIDATING' && !g.ready ? 'VALIDATING…' : (g.status === 'VALIDATING' ? 'READY' : g.status));
const busy = (g) => g.status === 'BUILDING' || (g.status === 'VALIDATING' && !g.ready);

/** While something is building, look again every few seconds - but only while this page is still the one on screen. */
function pollWhile(hash, reload) {
  setTimeout(() => { if (location.hash.startsWith(hash)) reload(); }, 4000);
}

async function activate(g, reload, flash) {
  const attempt = async (reason) => {
    try {
      const r = await api('POST', '/admin/pattern-generations/' + g.id + '/activate', reason ? { reason } : {});
      reload('Generation #' + g.id + ' is now ACTIVE. Coverage gained ' + r.gained_coverage.length + ', lost ' + r.lost_coverage.length + ' (product, amount) pair(s).');
    } catch (e) {
      if (e.code !== 'COVERAGE_REGRESSION') { flash.replaceChildren(errorNotice(e)); return; }
      const v = await prompt({ title: 'Coverage would drop', danger: true, confirmText: 'Activate anyway',
        intro: e.message, fields: [{ name: 'reason', label: 'Reason for accepting reduced coverage', required: true, multiline: true }] });
      if (v) attempt(v.reason);
    }
  };
  const ok = await prompt({ title: 'Activate generation #' + g.id + '?', danger: true, confirmText: 'Activate',
    intro: 'Orders are routed through this generation from now on. The current one is kept as SUPERSEDED, and you can roll back by activating it again.', fields: [] });
  if (ok) attempt('');
}

export async function generationsList({ params, can, reload, navigate }) {
  const page = Number(params.get('page') || 0);
  const data = await api('GET', '/admin/pattern-generations?page=' + page + '&size=25');
  const flash = h('div', {});
  const active = data.items.find(g => g.status === 'ACTIVE');
  if (data.items.some(busy)) pollWhile('#/generations', reload);

  const start = async () => {
    const ok = await prompt({ title: 'Build a new generation?', confirmText: 'Start', intro: 'Builds patterns for every active product and supported amount in the background. It serves nothing until you activate it.', fields: [] });
    if (!ok) return;
    try { const r = await api('POST', '/admin/pattern-generations', {}); reload('Generation #' + r.generation_id + ' started.'); }
    catch (e) { flash.replaceChildren(errorNotice(e)); }
  };
  const rescore = async () => {
    try { const r = await api('POST', '/admin/pattern-economics/rescore', {}); reload('Re-scored ' + r.patterns_scored + ' pattern(s) of generation #' + r.generation_id + '.'); }
    catch (e) { flash.replaceChildren(errorNotice(e)); }
  };

  return h('div', {}, h('h1', {}, 'Pattern generations'), flash,
    section('Serving now', active
      ? kv([['Generation', h('a', { href: '#/generations/' + active.id }, '#' + active.id)], ['Patterns', active.pattern_count ?? ''], ['Activated', when(active.activated_at)]])
      : h('div', { class: 'notice warn' }, 'No generation is ACTIVE: orders cannot be routed. Build one and activate it.')),
    h('div', { class: 'actions' },
      can('pattern:generate') ? h('button', { type: 'button', class: 'primary', onclick: start }, 'Build new generation') : null,
      can('pattern:generate') ? h('button', { type: 'button', onclick: rescore }, 'Re-score active now') : null),
    h('section', { class: 'card' }, table([
      { title: 'ID', cell: g => h('a', { href: '#/generations/' + g.id }, '#' + g.id) }, { title: 'Status', cell: g => badge(phase(g)) },
      { title: 'Patterns', num: true, cell: g => g.pattern_count ?? '' }, { title: 'Started', cell: g => when(g.started_at) },
      { title: 'Finished', cell: g => when(g.completed_at) }, { title: 'Activated', cell: g => when(g.activated_at) }, { title: 'By', cell: g => g.triggered_by },
      { title: 'Notes', cell: g => g.notes || '' }], data.items, 'No generations yet.'),
      pager(data, (n) => navigate('/generations', { page: n }))));
}

export async function generationDetail({ id, can, reload }) {
  const d = await api('GET', '/admin/pattern-generations/' + id);
  const g = d.generation;
  const flash = h('div', {});
  if (busy(g)) pollWhile('#/generations/' + id, reload);
  const canActivate = can('pattern:activate') && (g.ready || g.status === 'SUPERSEDED');
  const discard = async () => {
    const ok = await prompt({ title: 'Discard generation #' + id + '?', danger: true, confirmText: 'Discard', intro: 'It is marked FAILED and cannot be activated. Its rows are kept.', fields: [] });
    if (!ok) return;
    try { await api('POST', '/admin/pattern-generations/' + id + '/discard', {}); reload('Generation #' + id + ' discarded.'); }
    catch (e) { flash.replaceChildren(errorNotice(e)); }
  };

  const summary = d.report && d.report.summary;
  const rows = [];
  for (const p of (d.report && d.report.products) || []) for (const a of p.amounts) rows.push({ product: p.product_code, ...a });
  return h('div', {}, h('h1', {}, 'Generation #' + id, ' ', badge(phase(g))), flash,
    h('div', { class: 'actions' },
      canActivate ? h('button', { type: 'button', class: 'primary', onclick: () => activate(g, reload, flash) }, g.status === 'SUPERSEDED' ? 'Roll back to this generation' : 'Activate') : null,
      can('pattern:activate') && g.ready ? h('button', { type: 'button', class: 'danger', onclick: discard }, 'Discard') : null,
      h('a', { href: '#/patterns?generation_id=' + id }, 'Browse its patterns')),
    section('Run', kv([['Status', g.status], ['Started by', g.triggered_by], ['Started', when(g.started_at)], ['Finished', when(g.completed_at)], ['Activated', when(g.activated_at)],
      ['Patterns', g.pattern_count ?? ''], ['Notes', g.notes]])),
    summary ? section('Result', kv([['Amounts built', summary.amounts], ['Amounts with no pattern', summary.amounts_without_patterns],
      ['Amounts at the pattern limit', summary.amounts_at_pattern_limit], ['Amounts that exhausted the search budget', summary.amounts_budget_exhausted]])) : null,
    d.params ? section('Parameters used', kv(Object.entries(d.params).map(([k, v]) => [k, String(v)]))) : null,
    rows.length ? h('section', { class: 'card' }, h('h2', {}, 'Per amount'), table([
      { title: 'Product', cell: r => r.product }, { title: 'Amount', num: true, cell: r => r.amount.toLocaleString('id-ID') }, { title: 'Patterns', num: true, cell: r => r.patterns },
      { title: 'Note', cell: r => r.infeasible ? badge('NONE POSSIBLE') : (r.budget_exhausted ? badge('SEARCH CUT SHORT') : (r.hit_pattern_limit ? 'limit reached' : '')) }], rows)) : null);
}
