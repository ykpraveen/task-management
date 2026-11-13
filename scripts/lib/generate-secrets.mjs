// Fills __GENERATE__ placeholders from .env.example into .env without overwriting values.
// Prints only key names, never values.
import crypto from 'node:crypto';
import fs from 'node:fs';

const [examplePath, envPath] = process.argv.slice(2);

const parse = (text) => {
  const entries = new Map();
  for (const line of text.split(/\r?\n/)) {
    const m = /^([A-Z0-9_]+)=(.*)$/.exec(line);
    if (m) entries.set(m[1], m[2]);
  }
  return entries;
};

const exampleText = fs.readFileSync(examplePath, 'utf8');
const existingText = fs.existsSync(envPath) ? fs.readFileSync(envPath, 'utf8') : '';
const existing = parse(existingText);
const env = new Map([...parse(exampleText), ...existing]);
const needs = (k) => !env.has(k) || env.get(k) === '__GENERATE__' || env.get(k) === '';

const random = (bytes) => crypto.randomBytes(bytes).toString('base64url');
const b64u = (v) => Buffer.from(typeof v === 'string' ? v : JSON.stringify(v)).toString('base64url');

const iat = Math.floor(Date.now() / 1000);
const exp = iat + 5 * 365 * 24 * 3600;

const generated = new Map();
const set = (k, v) => {
  if (needs(k)) {
    env.set(k, v);
    generated.set(k, true);
  }
};

for (const k of [
  'POSTGRES_PASSWORD', 'DASHBOARD_PASSWORD', 'IDENTITY_OWNER_PASSWORD', 'TASK_OWNER_PASSWORD',
  'TASK_APP_PASSWORD', 'NOTIFICATION_OWNER_PASSWORD', 'NOTIFICATION_APP_PASSWORD',
  'NOTIFICATIONS_TO_TASK_API_TOKEN', 'OPERATOR_TOKEN',
]) set(k, random(32).replace(/[-_]/g, 'x'));
set('JWT_SECRET', random(48));
set('SECRET_KEY_BASE', random(48).slice(0, 64));
set('REALTIME_DB_ENC_KEY', random(12).slice(0, 16));
set('PG_META_CRYPTO_KEY', random(32));

// Legacy HS256 API keys (Supabase-internal only).
const hs256 = (payload) => {
  const data = `${b64u({ alg: 'HS256', typ: 'JWT' })}.${b64u(payload)}`;
  const sig = crypto.createHmac('sha256', env.get('JWT_SECRET')).update(data).digest('base64url');
  return `${data}.${sig}`;
};
set('ANON_KEY', hs256({ role: 'anon', iss: 'supabase', iat, exp }));
set('SERVICE_ROLE_KEY', hs256({ role: 'service_role', iss: 'supabase', iat, exp }));

// ES256 signing key pair, JWKS and internal asymmetric API keys (generated together).
if (['JWT_KEYS', 'JWT_JWKS', 'ANON_KEY_ASYMMETRIC', 'SERVICE_ROLE_KEY_ASYMMETRIC'].some(needs)) {
  const { privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const jwk = privateKey.export({ format: 'jwk' });
  const kid = crypto.randomUUID();
  const oct = { kty: 'oct', k: Buffer.from(env.get('JWT_SECRET')).toString('base64url'), alg: 'HS256' };
  const pub = { kty: 'EC', kid, use: 'sig', key_ops: ['verify'], alg: 'ES256', ext: true, crv: jwk.crv, x: jwk.x, y: jwk.y };
  const es256 = (payload) => {
    const data = `${b64u({ alg: 'ES256', typ: 'JWT', kid })}.${b64u(payload)}`;
    const sig = crypto.sign('SHA256', Buffer.from(data), { key: privateKey, dsaEncoding: 'ieee-p1363' }).toString('base64url');
    return `${data}.${sig}`;
  };
  for (const [k, v] of [
    ['JWT_KEYS', JSON.stringify([{ ...pub, key_ops: ['sign', 'verify'], d: jwk.d }, oct])],
    ['JWT_JWKS', JSON.stringify({ keys: [pub, oct] })],
    ['ANON_KEY_ASYMMETRIC', es256({ role: 'anon', iss: 'supabase', iat, exp })],
    ['SERVICE_ROLE_KEY_ASYMMETRIC', es256({ role: 'service_role', iss: 'supabase', iat, exp })],
  ]) {
    env.set(k, v);
    generated.set(k, true);
  }
}

// Opaque API keys (publishable = public browser key, secret = server-only).
const opaque = (prefix) => {
  const intermediate = prefix + crypto.randomBytes(17).toString('base64url').slice(0, 22);
  const checksum = crypto.createHash('sha256').update(`supabase-self-hosted|${intermediate}`).digest('base64url').slice(0, 8);
  return `${intermediate}_${checksum}`;
};
set('SUPABASE_PUBLISHABLE_KEY', opaque('sb_publishable_'));
set('SUPABASE_SECRET_KEY', opaque('sb_secret_'));

// Write .env following the example's layout; keep extra keys the operator added.
const seen = new Set();
const out = exampleText.split(/\r?\n/).map((line) => {
  const m = /^([A-Z0-9_]+)=/.exec(line);
  if (!m) return line;
  seen.add(m[1]);
  return `${m[1]}=${env.get(m[1])}`;
});
for (const [k, v] of existing) if (!seen.has(k)) out.push(`${k}=${v}`);
fs.writeFileSync(envPath, out.join('\n'), { mode: 0o600 });
console.log(`setup: generated ${generated.size ? [...generated.keys()].join(', ') : 'nothing (all values present)'}`);
