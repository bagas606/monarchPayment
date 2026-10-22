import { h, clear, put, field, notice } from './dom.js';
import { api, setCsrf } from './api.js';

/**
 * The sign-in flow, driven entirely by the server's `status`:
 *   AUTHENTICATED             -> done
 *   MFA_REQUIRED              -> ask for the 6-digit code
 *   MFA_ENROLLMENT_REQUIRED   -> show the new authenticator key, ask for a confirming code
 * The session token itself lives in an HttpOnly cookie this script cannot read.
 */
export function loginView(root, { message, done }) {
  clear(root);
  const card = h('div', { class: 'card login' });
  const slot = h('div', {});
  root.append(h('main', { id: 'main' }, card));
  showPassword();

  function fail(e) { slot.replaceChildren(notice('error', e.message)); }

  function showPassword() {
    clear(card);
    const user = h('input', { name: 'username', autocomplete: 'username', required: true });
    const pass = h('input', { name: 'password', type: 'password', autocomplete: 'current-password', required: true });
    const submit = h('button', { class: 'primary', type: 'submit' }, 'Sign in');
    put(card, h('h1', {}, 'PPOB2 Backoffice'), message ? notice('warn', message) : null, slot,
      h('form', { onsubmit: async (e) => {
        e.preventDefault(); submit.disabled = true;
        try { handle(await api('POST', '/admin/auth/login', { username: user.value, password: pass.value }, { quiet401: true })); }
        catch (err) { fail(err); pass.value = ''; } finally { submit.disabled = false; }
      } }, field('Username', user), field('Password', pass), submit));
    user.focus();
  }

  function handle(session) {
    setCsrf(session.csrf_token);
    if (session.status === 'AUTHENTICATED') return done();
    if (session.status === 'MFA_REQUIRED') return showCode('Two-step verification',
      'Enter the 6-digit code from your authenticator app.', '/admin/auth/mfa/verify');
    if (session.status === 'MFA_ENROLLMENT_REQUIRED') return showEnrollment();
    fail(new Error('Unexpected sign-in state.'));
  }

  function showCode(title, hint, path, extra) {
    clear(card);
    const code = h('input', { name: 'code', inputmode: 'numeric', pattern: '[0-9]{6}', maxlength: 6, autocomplete: 'one-time-code', required: true });
    const submit = h('button', { class: 'primary', type: 'submit' }, 'Verify');
    slot.replaceChildren();
    put(card, h('h1', {}, title), h('p', { class: 'muted' }, hint), extra || null, slot,
      h('form', { onsubmit: async (e) => {
        e.preventDefault(); submit.disabled = true;
        try {
          const r = await api('POST', path, { code: code.value.trim() }, { quiet401: true });
          if (r.csrf_token) setCsrf(r.csrf_token);
          done();
        } catch (err) {
          fail(err);
          // The server revokes a session after repeated wrong codes; a 401 means start over.
          if (err.status === 401 && err.code !== 'MFA_CODE_INVALID') showPassword();
          code.value = ''; code.focus();
        } finally { submit.disabled = false; }
      } }, field('Code', code), submit),
      h('p', {}, h('button', { type: 'button', onclick: async () => { try { await api('POST', '/admin/auth/logout', {}, { quiet401: true }); } catch (e) { /* already gone */ } showPassword(); } }, 'Cancel')));
    code.focus();
  }

  async function showEnrollment() {
    clear(card);
    card.append(h('h1', {}, 'Set up two-step verification'), h('p', { class: 'muted' }, 'Loading…'));
    try {
      const e = await api('POST', '/admin/auth/mfa/enroll', {}, { quiet401: true });
      showCode('Set up two-step verification',
        'Your role requires an authenticator app. Add this key to it (choose "enter a setup key", time-based), then enter the 6-digit code it shows.',
        '/admin/auth/mfa/confirm',
        h('div', {}, h('div', { class: 'secret', 'aria-label': 'Authenticator setup key' }, e.secret.replace(/(.{4})/g, '$1 ').trim()),
          h('p', { class: 'muted' }, 'This key is shown once. If you lose the authenticator, an administrator must reset your MFA.')));
    } catch (err) { showPassword(); fail(err); }
  }
}
