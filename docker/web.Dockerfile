# Edge image: builds the React SPA, then serves it from Caddy (which also reverse-proxies the API).
# The SPA calls the API at the same origin (apiClient base "/rest"), so it's deployment-agnostic —
# no server URL is baked into the web bundle.

FROM node:22-alpine AS web
WORKDIR /web
# Release CI passes the agent's package + signing checksum (+ optional APK URL) so the in-product
# enrollment QR matches the signed release APK. Defaults (in provisioning.ts) cover the debug build.
ARG VITE_AGENT_PACKAGE
ARG VITE_AGENT_CHECKSUM
ARG VITE_AGENT_APK_URL
# Build version, baked so the open console can detect it lags a freshly-deployed one (ReloadPrompt).
ARG VITE_APP_VERSION=dev
ENV VITE_AGENT_PACKAGE=$VITE_AGENT_PACKAGE \
    VITE_AGENT_CHECKSUM=$VITE_AGENT_CHECKSUM \
    VITE_AGENT_APK_URL=$VITE_AGENT_APK_URL \
    VITE_APP_VERSION=$VITE_APP_VERSION
COPY web/package*.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# An exact Caddy release, not caddy:2-alpine: a Caddy release can change how docker/Caddyfile parses (v0.3.1's
# Cloudflare-mode outage was a Caddyfile that stopped parsing). Dependabot proposes each new release in its weekly
# docker-images PR, and T0's edge check (scripts/edge-check.sh, which reads the image from this line) validates the
# Caddyfile in every hosting mode against it before it can merge. The tag itself is still rebuilt upstream for
# Alpine fixes.
FROM caddy:2.11.4-alpine
# Run Caddy unprivileged. Binding :80/:443 comes from the container's network namespace
# (sysctl net.ipv4.ip_unprivileged_port_start=0 in compose), not a file capability on the binary: the containers
# run with no-new-privileges and all capabilities dropped, and a binary carrying a file capability outside the
# bounding set cannot even be executed. /data (certs) and /config are mounted volumes that older deployments created
# root-owned, so a tiny root entrypoint fixes their ownership and then su-execs to "caddy".
RUN apk add --no-cache su-exec libcap \
 && addgroup -S caddy && adduser -S -G caddy -h /data caddy \
 && (setcap -r /usr/bin/caddy || true) \
 && [ -z "$(getcap /usr/bin/caddy)" ] \
 && apk del libcap
COPY --from=web /web/dist /srv
COPY docker/Caddyfile /etc/caddy/Caddyfile
COPY docker/web-entrypoint.sh /web-entrypoint.sh
RUN chmod +x /web-entrypoint.sh && chown -R caddy:caddy /srv /etc/caddy /data /config
EXPOSE 80 443
ENTRYPOINT ["/web-entrypoint.sh"]
CMD ["caddy", "run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"]
