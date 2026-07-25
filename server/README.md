# Legado Server

独立的单用户 Web 服务。首次启动必须设置至少 12 位的 `ADMIN_PASSWORD`；密码只在初始化时写入 Argon2id 哈希，之后可移除该环境变量。

## 运行

```bash
cp .env.example .env
docker compose --env-file .env -f compose.server.yml up -d --build
```

默认仅发布到 `127.0.0.1:8080`。请用 Nginx 或 Caddy 在同源 HTTPS 下反向代理；公网不要直接公开容器端口。

## 重置密码

在停止服务或确保没有并发请求时执行：

```bash
docker compose --env-file .env -f compose.server.yml run --rm legado-server reset-password 'new-password-at-least-12-chars'
```

这会撤销全部浏览器会话。

## 当前实现边界

已提供安全登录、会话、CSRF、防爆破、SQLite 书源 CRUD、JSON 导入导出、版本冲突与结构校验。规则的实际 HTTP 执行、Android 规则分析器移植、SSE 调试详情与阅读预览仍需在 `server-core` 中完成，不能把 Android `app` 模块直接用于服务器。
