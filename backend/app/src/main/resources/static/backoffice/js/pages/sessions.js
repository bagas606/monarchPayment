import { h, field, notice } from '../dom.js';
import { api } from '../api.js';
import { section, errorNotice } from '../ui.js';

export async function sessionsPage() {
  const slot = h('div', {});
  const user = h('input', { name: 'username', required: true, autocomplete: 'off' });
  return h('div', {}, h('h1', {}, 'Sessions'),
    section('End an admin\'s sessions',
      h('p', { class: 'muted' }, 'Signs the account out everywhere, immediately. It does not disable the account or reset its MFA.'), slot,
      h('form', { class: 'filters', onsubmit: async (e) => {
        e.preventDefault();
        try { const r = await api('POST', '/admin/auth/sessions/revoke', { username: user.value.trim() });
          slot.replaceChildren(notice('ok', r.revoked + ' session(s) ended for ' + user.value.trim() + '.')); user.value = ''; }
        catch (err) { slot.replaceChildren(errorNotice(err)); }
      } }, field('Username', user), h('button', { class: 'danger', type: 'submit' }, 'End sessions'))));
}
