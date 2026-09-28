# UltraVNC-style Mode-II repeater for remote control (ADR 0010). Devices (droidVNC-NG) dial OUT to it
# on :5500 with a session id; the console's noVNC reaches it on :5900 through websockify. It only pairs
# the two sides by id and never decodes the stream.
# Source: https://github.com/tenchman/uvncrepeater-ac (GPL-2.0), pinned.
FROM alpine:3.22 AS build
ARG REPEATER_COMMIT=383e7be2d2dabccaaa33b07dae87a75b5fd70913
RUN apk add --no-cache build-base git \
 && git clone https://github.com/tenchman/uvncrepeater-ac.git /src \
 && cd /src && git checkout "$REPEATER_COMMIT" && make release

FROM alpine:3.22
RUN adduser -D -H -s /sbin/nologin uvncrep
COPY --from=build /src/repeater /usr/local/bin/uvncrepeater
COPY docker/uvncrepeater.ini /etc/uvncrepeater.ini
EXPOSE 5500 5900
# The repeater drops to `runasuser` itself after binding.
CMD ["/usr/local/bin/uvncrepeater", "/etc/uvncrepeater.ini"]
