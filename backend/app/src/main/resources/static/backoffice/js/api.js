// All HTTP goes through here. Same-origin, cookie session: the browser attaches the HttpOnly cookie; for any
// state-changing request we add X-CSRF-Token, the value the server derived from our session.

export class ApiError extends Error {
  constructor(status, body) {
    super((body && body.message) || ('Request failed (' + status + ')'));
    this.status = status;
    this.code = body && body.error_code;
    this.details = (body && body.details) || [];
  }
}

let csrf = null;
let onUnauthenticated = () => {};

export function setCsrf(value) { csrf = value || null; }
export function onSessionLost(handler) { onUnauthenticated = handler; }

export async function api(method, path, body, { quiet401 = false } = {}) {
  const headers = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET' && csrf) headers['X-CSRF-Token'] = csrf;
  let response;
  try {
    response = await fetch(path, { method, headers, credentials: 'same-origin', body: body === undefined ? undefined : JSON.stringify(body) });
  } catch (e) {
    throw new ApiError(0, { message: 'Cannot reach the server. Check your connection and try again.' });
  }
  if (response.status === 204) return null;
  let data = null;
  try { data = await response.json(); } catch (e) { /* non-JSON error page */ }
  if (!response.ok) {
    if (response.status === 401 && !quiet401) onUnauthenticated();
    throw new ApiError(response.status, data);
  }
  return data;
}

export function qs(params) {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== null && v !== '') q.set(k, v);
  const s = q.toString();
  return s ? '?' + s : '';
}
