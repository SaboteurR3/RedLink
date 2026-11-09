// A webhook receiver for trying RedLink's webhooks locally: prints every delivery and verifies its
// signature the way a real receiver must.
//
//   node scripts/webhook-receiver.mjs                          # listen on http://localhost:9000
//   WEBHOOK_SECRET=whsec_... node scripts/webhook-receiver.mjs # also verify X-RedLink-Signature
//   STATUS=503 node scripts/webhook-receiver.mjs               # answer 503: watch the retries and backoff
//   STATUS=410 node scripts/webhook-receiver.mjs               # answer 410 Gone: RedLink stops sending
//
// The `local` profile allows http://localhost receivers; anywhere else webhooks must be public HTTPS.
import { createHmac, timingSafeEqual } from 'node:crypto';
import { createServer } from 'node:http';

const PORT = Number(process.env.PORT ?? 9000);
const SECRET = process.env.WEBHOOK_SECRET ?? '';
const STATUS = Number(process.env.STATUS ?? 204);
/** Same window as RedLink's own signature check: an older timestamp may be a replayed request. */
const TOLERANCE_SECONDS = 300;

createServer((req, res) => {
  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', () => {
    // Verify over the exact bytes received, before any JSON parsing.
    const body = Buffer.concat(chunks);
    const event = req.headers['x-redlink-event'];
    const delivery = req.headers['x-redlink-delivery'];
    const verdict = SECRET ? verify(req.headers['x-redlink-signature'], body) : 'not checked (set WEBHOOK_SECRET)';

    console.log(`\n── ${new Date().toISOString()}  ${req.method} ${req.url}`);
    console.log(`   event:     ${event}`);
    console.log(`   delivery:  ${delivery}   (the same id again = a retry of the same notification)`);
    console.log(`   signature: ${verdict}`);
    try {
      console.log(JSON.stringify(JSON.parse(body.toString('utf8')), null, 2).replace(/^/gm, '   '));
    } catch {
      console.log(`   ${body.toString('utf8')}`);
    }

    const status = SECRET && !verdict.startsWith('valid') ? 401 : STATUS;
    console.log(`   → answering ${status}`);
    res.writeHead(status).end();
  });
}).listen(PORT, () => console.log(`Webhook receiver on http://localhost:${PORT}  (answers ${STATUS})`));

/** X-RedLink-Signature: t=<unix seconds>,v1=<base64url HMAC-SHA256 of "<t>." + body, key = the secret>. */
function verify(header, body) {
  if (!header) return 'MISSING';
  const parts = Object.fromEntries(header.split(',').map((p) => p.trim().split('=', 2)));
  const t = Number(parts.t);
  if (!Number.isInteger(t) || !parts.v1) return 'MALFORMED';
  const age = Math.abs(Date.now() / 1000 - t);
  if (age > TOLERANCE_SECONDS) return `TOO OLD (${Math.round(age)} s): possibly replayed`;
  const expected = createHmac('sha256', SECRET).update(`${t}.`).update(body).digest();
  const given = Buffer.from(parts.v1, 'base64url');
  // Constant-time comparison, so the response time doesn't reveal how much of a guess was right.
  return given.length === expected.length && timingSafeEqual(given, expected) ? 'valid ✔' : 'INVALID ✘ (wrong secret or changed body)';
}