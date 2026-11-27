# Operations image: the monitor (alerts + status page) and the backup/restore tooling. One small image for the two
# optional services (compose profiles `monitor` and `backup`) and for scripts/restore-backup.sh.
#   node                 the monitor (no npm dependencies)
#   postgresql17-client  pg_dump / pg_restore / psql matching the stack's PostgreSQL 17
#   rclone, rsync, ssh   off-site copy (S3-compatible, any rclone remote, or rsync over SSH)
#   openssl              optional encryption of the dumps
FROM node:24-alpine
RUN apk add --no-cache bash coreutils tzdata postgresql17-client rclone rsync openssh-client openssl tar gzip \
 && addgroup -S ops && adduser -S -G ops -h /var/lib/monitor ops \
 && mkdir -p /var/lib/monitor /backups /config && chown ops:ops /var/lib/monitor
COPY ops/ /ops/
RUN chmod +x /ops/*.sh
WORKDIR /ops
# The monitor needs no privileges; the backup service overrides the user (it writes the shared backups volume).
USER ops
EXPOSE 9100
CMD ["node", "/ops/monitor/monitor.js"]
