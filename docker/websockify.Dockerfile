# WebSocket <-> TCP bridge to the repeater's viewer port, plus the official noVNC web viewer (ADR 0010).
# Caddy maps /remote/vnc/ to it only for signed-in console sessions (forward_auth): the viewer page is
# /remote/vnc/vnc.html and its WebSocket /remote/vnc/websockify. noVNC is the unmodified upstream release
# (MPL-2.0), pinned by sha256.
FROM python:3.12-alpine
ARG NOVNC_VERSION=1.6.0
ARG NOVNC_SHA256=5066103959ef4e9b10f37e5a148627360dd8414e4cf8a7db92bdbd022e728aaa
RUN pip install --no-cache-dir websockify==0.13.0 \
 && wget -q -O /tmp/novnc.tgz "https://github.com/novnc/noVNC/archive/refs/tags/v${NOVNC_VERSION}.tar.gz" \
 && echo "${NOVNC_SHA256}  /tmp/novnc.tgz" | sha256sum -c - \
 && mkdir /novnc && tar xzf /tmp/novnc.tgz -C /novnc --strip-components=1 && rm /tmp/novnc.tgz \
 && adduser -D -H -s /sbin/nologin websockify
USER websockify
EXPOSE 6080
CMD ["websockify", "--web", "/novnc", "6080", "vnc-repeater:5900"]
