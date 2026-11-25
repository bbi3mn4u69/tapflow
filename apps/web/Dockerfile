FROM node:24-bookworm-slim@sha256:01d566e8d94d8c619591d78652babfccd3c6992a3dffa9ed5ab39e11cf6c70e9 AS build
WORKDIR /source
RUN npm install --global pnpm@9.12.3
COPY apps/web/package.json apps/web/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY apps/web/ ./
RUN pnpm build

FROM caddy:2.11.9-alpine@sha256:78bd35025c7ba85961dd149ae670315129954e77abe2383ac47c89106b35ade9
RUN addgroup -g 10001 tapflow && adduser -D -u 10001 -G tapflow tapflow && chown -R 10001:10001 /data /config
COPY --from=build /source/dist /srv
COPY infra/caddy/web.Caddyfile /etc/caddy/Caddyfile
ENV PORT=8080
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --retries=3 CMD wget -q -O /dev/null http://127.0.0.1:${PORT:-8080}/healthz
