# DallyControl console (`web/`)

The admin console: a React + TypeScript single-page app built with Vite. In production it is baked into the web
image and served by Caddy next to the API (`docker/web.Dockerfile`, `docker/Caddyfile`); the native installer serves
the same build from Tomcat.

## Run

Start the dev stack first ([docs/DEV.md](../docs/DEV.md)), then:

```bash
cd web
npm install
npm run dev                 # http://localhost:5173, hot reload
```

`npm run build` type-checks and writes the production build to `dist/`; `npm run preview` serves that build.

## How the dev server reaches the API

Sign-in is session-cookie based (`JSESSIONID`), so the console has to be same-origin with the API. The Vite dev
server proxies every path the console calls to one target, cookies included:

```
/rest  /files  /agent/ws  /update  /recovery   ->   VITE_DEV_PROXY_TARGET (default http://localhost:8088)
```

The default is the dev stack's Caddy, which routes them exactly as production does: `/rest`, `/files` and
`/agent/ws` to the server, `/update`, `/recovery` and `/files/agent.apk` to the supervisor.

## Environment variables

| Variable | Default | Used by | Purpose |
|---|---|---|---|
| `VITE_API_BASE` | `/rest` | app (runtime) | URL prefix for API calls. Keep `/rest`: an absolute URL makes the calls cross-origin, and the server's `Access-Control-Allow-Origin: *` does not allow the credentialed (cookie) requests the console makes. |
| `VITE_DEV_PROXY_TARGET` | `http://localhost:8088` | `vite.config.ts` | Where the dev server proxies (above). |

See `.env.example`. The enrollment-QR defaults (`VITE_AGENT_*`) are build arguments of the web image; see
`docker/web.Dockerfile`.
