# syntax=docker/dockerfile:1
# Build context: frontend/
#   docker build -t redlink-frontend frontend

# --- build -------------------------------------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json .npmrc ./
RUN npm ci --no-audit --no-fund
COPY . .
# Type-checks and bundles; the generated API types (src/api/schema.d.ts) are committed.
RUN npm run build

# --- serve -------------------------------------------------------------------------------------
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 80
