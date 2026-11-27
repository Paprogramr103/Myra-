/**
 * MYRA Production Backend WebSocket Proxy for Gemini Live API (v1beta BidiGenerateContent)
 *
 * Security model:
 * - GEMINI_API_KEY lives exclusively in server-side environment variables (never inside the APK).
 * - Clients authenticate with a Bearer token (PROXY_AUTH_TOKEN) checked in constant time.
 * - Deploy behind HTTPS/TLS termination (Cloud Run, Nginx, Caddy) so Android connects over wss://.
 * - Redacts ?key= and Bearer tokens from all server logs.
 */

const crypto = require('crypto');
const http = require('http');
const { WebSocketServer, WebSocket } = require('ws');

const GEMINI_API_KEY = (process.env.GEMINI_API_KEY || '').trim();
const PROXY_AUTH_TOKEN = (process.env.PROXY_AUTH_TOKEN || '').trim();
const PORT = Number(process.env.PORT || 8080);
const MAX_PAYLOAD_BYTES = 6 * 1024 * 1024; // Supports compressed JPEG frames from Camera Vision.
const MAX_MESSAGES_PER_MIN = Number(process.env.MAX_MESSAGES_PER_MIN || 180);

const UPSTREAM_BASE =
  'wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent';

if (!GEMINI_API_KEY) {
  console.error('[MYRA Proxy] ERROR: GEMINI_API_KEY environment variable is required.');
  process.exit(1);
}

if (Buffer.byteLength(PROXY_AUTH_TOKEN, 'utf8') < 32) {
  console.error('[MYRA Proxy] ERROR: PROXY_AUTH_TOKEN is required and must be at least 32 bytes. Generate a strong random token before starting the service.');
  process.exit(1);
}

function redact(input) {
  return String(input || '')
    .replace(/([?&]key=)[^&\s]+/g, '$1[REDACTED]')
    .replace(/(Bearer\s+)\S+/gi, '$1[REDACTED]');
}

function timingSafeTokenMatch(expected, actual) {
  const a = Buffer.from(expected, 'utf8');
  const b = Buffer.from(actual, 'utf8');
  if (a.length !== b.length) return false;
  return crypto.timingSafeEqual(a, b);
}

function isAuthorized(req) {
  const authHeader = String(req.headers.authorization || '');
  if (!authHeader.startsWith('Bearer ')) return false;
  const token = authHeader.slice('Bearer '.length).trim();
  return timingSafeTokenMatch(PROXY_AUTH_TOKEN, token);
}

const server = http.createServer((req, res) => {
  if (req.url === '/healthz') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, service: 'myra-gemini-live-proxy' }));
    return;
  }
  res.writeHead(426, { 'Content-Type': 'text/plain' });
  res.end('Upgrade required: connect via WebSocket (wss://) on /ws');
});

const wss = new WebSocketServer({
  noServer: true,
  maxPayload: MAX_PAYLOAD_BYTES,
});

server.on('upgrade', (req, socket, head) => {
  const urlPath = (req.url || '').split('?')[0];
  if (urlPath !== '/ws' && urlPath !== '/') {
    socket.write('HTTP/1.1 404 Not Found\r\n\r\n');
    socket.destroy();
    return;
  }
  if (!isAuthorized(req)) {
    socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (clientWs) => {
    wss.emit('connection', clientWs, req);
  });
});

wss.on('connection', (clientWs, req) => {
  const clientIp = req.socket.remoteAddress || 'unknown';
  let windowStart = Date.now();
  let msgCount = 0;

  const upstreamUrl = `${UPSTREAM_BASE}?key=${encodeURIComponent(GEMINI_API_KEY)}`;
  const upstreamWs = new WebSocket(upstreamUrl);
  const pendingFrames = [];

  upstreamWs.on('open', () => {
    while (pendingFrames.length > 0 && upstreamWs.readyState === WebSocket.OPEN) {
      const frame = pendingFrames.shift();
      upstreamWs.send(frame.data, { binary: frame.isBinary });
    }
  });

  upstreamWs.on('message', (data, isBinary) => {
    if (clientWs.readyState === WebSocket.OPEN) {
      clientWs.send(data, { binary: isBinary });
    }
  });

  upstreamWs.on('close', (code, reason) => {
    if (clientWs.readyState === WebSocket.OPEN) {
      clientWs.close(code >= 1000 && code <= 4999 ? code : 1000, redact(reason.toString()));
    }
  });

  upstreamWs.on('error', (err) => {
    console.error(`[MYRA Proxy] Upstream error for ${clientIp}: ${redact(err.message)}`);
    if (clientWs.readyState === WebSocket.OPEN) {
      clientWs.close(1011, 'Upstream Gemini Live connection error');
    }
  });

  clientWs.on('message', (data, isBinary) => {
    const now = Date.now();
    if (now - windowStart >= 60_000) {
      windowStart = now;
      msgCount = 0;
    }
    msgCount += 1;
    if (msgCount > MAX_MESSAGES_PER_MIN) {
      clientWs.close(1008, 'Rate limit exceeded');
      upstreamWs.close(1000, 'Client rate limited');
      return;
    }

    if (upstreamWs.readyState === WebSocket.OPEN) {
      upstreamWs.send(data, { binary: isBinary });
    } else if (upstreamWs.readyState === WebSocket.CONNECTING) {
      if (pendingFrames.length < 32) {
        pendingFrames.push({ data, isBinary });
      }
    }
  });

  clientWs.on('close', () => {
    if (upstreamWs.readyState === WebSocket.OPEN || upstreamWs.readyState === WebSocket.CONNECTING) {
      upstreamWs.close(1000, 'Client disconnected');
    }
  });

  clientWs.on('error', (err) => {
    console.error(`[MYRA Proxy] Client socket error (${clientIp}): ${redact(err.message)}`);
    if (upstreamWs.readyState === WebSocket.OPEN || upstreamWs.readyState === WebSocket.CONNECTING) {
      upstreamWs.close(1000, 'Client socket error');
    }
  });
});

server.listen(PORT, () => {
  console.log(`[MYRA Proxy] Listening on port ${PORT} (/ws, /healthz)`);
});
