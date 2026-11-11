// Talks to the plugin. The login token is kept in localStorage (it also works in the
// iPhone home-screen app, which has its own storage separate from Safari).
import { store } from './util.js';

let token = store.get('token', null);
export const session = { me: null };

export function loggedIn() { return !!token; }
export function setToken(t) { token = t; store.set('token', t); }
export function clearToken() { token = null; store.del('token'); session.me = null; }

export class ApiError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

/** body: an object (sent as JSON) or a File/Blob (sent as is, e.g. a .litematic plan). */
export async function api(path, { method = 'GET', body, signal } = {}) {
  const headers = { Accept: 'application/json' };
  if (token) headers.Authorization = 'Bearer ' + token;
  const raw = typeof Blob !== 'undefined' && body instanceof Blob;
  if (body !== undefined) headers['Content-Type'] = raw ? 'application/octet-stream' : 'application/json';
  let res;
  try {
    res = await fetch('/api/' + path, { method, headers, body: body === undefined ? undefined : raw ? body : JSON.stringify(body), signal, cache: 'no-store' });
  } catch (e) {
    if (e.name === 'AbortError') throw e;
    throw new ApiError(0, 'Le serveur ne répond pas. Vérifie ta connexion.');
  }
  let data = null;
  try { data = await res.json(); } catch { /* empty body */ }
  if (res.status === 401 && path !== 'auth/verify') {
    clearToken();
    if (!location.hash.startsWith('#/connexion')) location.hash = '#/connexion';
    throw new ApiError(401, (data && data.error) || 'Connexion requise.');
  }
  if (!res.ok) throw new ApiError(res.status, (data && data.error) || 'Erreur ' + res.status);
  return data;
}

/** Ask the game to highlight containers for the logged-in player. */
export function locate(ids) {
  return api('locate', { method: 'POST', body: { chests: ids } });
}
