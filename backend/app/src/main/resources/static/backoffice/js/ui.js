import { h, append, clear, field, notice } from './dom.js';

/** Previous / next, driven by the server's has_more (lists never run a count query). */
export function pager(data, go) {
  return h('div', { class: 'pager' },
    h('span', {}, 'Page ' + (data.page + 1)),
    h('button', { type: 'button', disabled: data.page === 0, onclick: () => go(data.page - 1) }, 'Previous'),
    h('button', { type: 'button', disabled: !data.has_more, onclick: () => go(data.page + 1) }, 'Next'));
}

/** Reconciliation lists use Spring's Page (total_pages) rather than has_more. */
export function pagerByTotal(data, go) {
  return h('div', { class: 'pager' },
    h('span', {}, 'Page ' + (data.page + 1) + ' of ' + Math.max(1, data.total_pages) + ' (' + data.total_elements + ' total)'),
    h('button', { type: 'button', disabled: data.page === 0, onclick: () => go(data.page - 1) }, 'Previous'),
    h('button', { type: 'button', disabled: data.page + 1 >= data.total_pages, onclick: () => go(data.page + 1) }, 'Next'));
}

/**
 * A GET filter form that writes its values into the URL hash, so a filtered view can be bookmarked,
 * reloaded and shared. `fields` = [{name, label, type, options}].
 */
export function filterForm(fields, params, apply) {
  const inputs = {};
  const form = h('form', { class: 'filters', onsubmit: (e) => {
    e.preventDefault();
    const next = {};
    for (const f of fields) next[f.name] = inputs[f.name].value;
    apply(next);
  } });
  for (const f of fields) {
    let el;
    if (f.options) {
      el = h('select', { name: f.name }, h('option', { value: '' }, 'Any'), f.options.map(o => h('option', { value: o }, o)));
    } else {
      el = h('input', { name: f.name, type: f.type || 'text', autocomplete: 'off' });
    }
    el.value = params.get(f.name) || '';
    inputs[f.name] = el;
    form.append(field(f.label, el));
  }
  form.append(h('button', { class: 'primary', type: 'submit' }, 'Apply'),
    h('button', { type: 'button', onclick: () => apply({}) }, 'Clear'));
  return form;
}

/**
 * A modal prompt returning the entered values, or null if cancelled. Native <dialog> gives focus trapping
 * and Escape handling for free. `fields` = [{name, label, required, multiline}].
 */
export function prompt({ title, intro, fields, confirmText, danger }) {
  return new Promise((resolve) => {
    const inputs = {};
    const error = h('div', {});
    const form = h('form', { method: 'dialog' }, h('h2', {}, title), intro ? h('p', { class: 'muted' }, intro) : null, error);
    for (const f of fields) {
      let el;
      if (f.options) el = h('select', { name: f.name }, f.options.map(o => h('option', { value: o }, o)));
      else if (f.multiline) el = h('textarea', { name: f.name, rows: 3 });
      else el = h('input', { name: f.name, autocomplete: 'off', inputmode: f.numeric ? 'numeric' : null });
      if (f.value !== undefined && f.value !== null) el.value = String(f.value);
      inputs[f.name] = el;
      form.append(field(f.label + (f.required ? ' (required)' : ''), el));
    }
    const dialog = h('dialog', {}, form);
    const finish = (value) => { dialog.close(); dialog.remove(); resolve(value); };
    form.append(h('div', { class: 'actions' },
      h('button', { class: (danger ? 'danger solid' : 'primary'), type: 'submit' }, confirmText || 'Confirm'),
      h('button', { type: 'button', onclick: () => finish(null) }, 'Cancel')));
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      const values = {};
      for (const f of fields) values[f.name] = inputs[f.name].value.trim();
      const missing = fields.find(f => f.required && !values[f.name]);
      if (missing) { clear(error); error.append(notice('error', missing.label + ' is required.')); inputs[missing.name].focus(); return; }
      finish(values);
    });
    dialog.addEventListener('cancel', (e) => { e.preventDefault(); finish(null); });
    document.body.append(dialog);
    dialog.showModal();
    const first = fields.length ? inputs[fields[0].name] : null;
    if (first) first.focus();
  });
}

export function errorNotice(e) {
  const box = h('div', { class: 'notice error', role: 'alert' }, e.message || 'Something went wrong.');
  if (e.details && e.details.length) {
    append(box, h('ul', {}, e.details.map(d => h('li', {}, (d.field ? d.field + ': ' : '') + d.issue))));
  }
  return box;
}

export function section(title, ...body) { return h('section', { class: 'card' }, h('h2', {}, title), body); }
