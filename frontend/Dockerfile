FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# 비루트 이미지라 8080을 쓴다
FROM nginxinc/nginx-unprivileged:1.29-alpine
# 베이스 이미지에 아직 반영되지 않은 보안 패치를 받는다
USER root
RUN apk upgrade --no-cache
USER 101
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
