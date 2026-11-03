# syntax=docker/dockerfile:1
FROM node:22-alpine AS build
WORKDIR /src
RUN corepack enable
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm build

FROM nginx:1.27-alpine
RUN apk add --no-cache openssl && rm -f /etc/nginx/conf.d/default.conf
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY docker-entrypoint.d/10-nexora-certs.sh /docker-entrypoint.d/10-nexora-certs.sh
RUN chmod +x /docker-entrypoint.d/10-nexora-certs.sh
COPY --from=build /src/dist /usr/share/nginx/html
ENV NEXORA_PUBLIC_HOST=localhost:8443
# only substitute our variable, never nginx's own $variables
ENV NGINX_ENVSUBST_FILTER=NEXORA_
EXPOSE 80 443
