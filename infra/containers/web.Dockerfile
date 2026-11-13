# Multi-arch SPA build served by an unprivileged nginx edge (no authentication decisions).
FROM node:24-alpine@sha256:8a32c8cf649a49915a66e81e7a609223c708d0adabf288ee3f4077d765542f85 AS build
WORKDIR /src
COPY package.json package-lock.json tsconfig.base.json ./
COPY packages/api-client/package.json packages/api-client/package.json
COPY apps/web/package.json apps/web/package.json
RUN npm ci --no-audit --no-fund
COPY packages packages
COPY apps apps
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.29-alpine@sha256:0c79d56aee561a1d81c63f00eee5fb5fe29279560cdc55e91425133104c7fbe6
COPY --from=build /src/apps/web/dist /usr/share/nginx/html
COPY infra/edge/default.conf /etc/nginx/templates/default.conf.template
COPY infra/edge/security-headers.conf /etc/nginx/snippets/security-headers.conf
USER 101
EXPOSE 8080
