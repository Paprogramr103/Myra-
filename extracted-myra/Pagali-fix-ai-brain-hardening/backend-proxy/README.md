# MYRA Secure Backend Proxy (Production)

Do **not** ship your production `GEMINI_API_KEY` inside the Android APK. Any key stored in or extracted by a mobile client can be inspected on rooted/compromised devices or via runtime memory hooks.

For production deployments, run this WebSocket relay server on Cloud Run, Fly.io, Railway, or your own VPS behind HTTPS/TLS (`wss://`):

## 1. Setup

```bash
cd backend-proxy
npm install
cp .env.example .env
# Edit .env with your server-only GEMINI_API_KEY and a random strong PROXY_AUTH_TOKEN
npm start
```

## 2. Connect MYRA Android App

1. Open **MYRA → Settings**.
2. Under **Connection Mode**, select **Secure Backend Proxy (Production)**.
3. Set **Backend Proxy WebSocket URL** to `wss://your-domain.example.com/ws`.
4. Set **Proxy Bearer Token** to match `PROXY_AUTH_TOKEN`.
5. Tap **SAVE & APPLY CHANGES**.

## 3. Mandatory Google Cloud / AI Studio API Key Hardening

- **Rotate exposed keys immediately:** Any key previously packaged inside an APK or committed anywhere must be deleted/rotated in Google AI Studio / Google Cloud Console.
- **API Restrictions:** Restrict the key strictly to **Generative Language API** only.
- **Quota Caps:** Configure daily/per-minute request & token quotas plus billing budget alerts in Google Cloud Console.
- **Server-side Auth:** Replace static `PROXY_AUTH_TOKEN` with per-user Firebase ID Tokens or short-lived JWTs before opening to public users.
