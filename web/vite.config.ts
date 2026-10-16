import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'prompt',
      includeAssets: ['favicon.svg', 'logo.svg', 'pwa-192x192.svg', 'pwa-512x512.svg', 'maskable-icon-512x512.svg'],
      manifest: {
        name: '阅读',
        short_name: '阅读',
        description: 'Legado 现代化 Web 阅读器与服务端',
        theme_color: '#121820',
        background_color: '#121820',
        display: 'standalone',
        orientation: 'any',
        scope: '/',
        start_url: '/',
        icons: [
          {
            src: '/pwa-192x192.svg',
            sizes: '192x192',
            type: 'image/svg+xml',
            purpose: 'any',
          },
          {
            src: '/pwa-512x512.svg',
            sizes: '512x512',
            type: 'image/svg+xml',
            purpose: 'any',
          },
          {
            src: '/maskable-icon-512x512.svg',
            sizes: '512x512',
            type: 'image/svg+xml',
            purpose: 'maskable',
          },
          {
            src: '/logo.svg',
            sizes: 'any',
            type: 'image/svg+xml',
            purpose: 'any',
          },
          {
            src: '/favicon.svg',
            sizes: 'any',
            type: 'image/svg+xml',
            purpose: 'any',
          },
        ],
        shortcuts: [
          {
            name: '我的书架',
            short_name: '书架',
            url: '/',
            icons: [{ src: '/favicon.svg', sizes: 'any', type: 'image/svg+xml' }],
          },
          {
            name: '书源管理',
            short_name: '书源',
            url: '/#sources',
            icons: [{ src: '/favicon.svg', sizes: 'any', type: 'image/svg+xml' }],
          },
          {
            name: '替换净化',
            short_name: '净化',
            url: '/#replace-rules',
            icons: [{ src: '/favicon.svg', sizes: 'any', type: 'image/svg+xml' }],
          },
        ],
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,svg,png,ico,woff,woff2,ttf}'],
        navigateFallback: '/index.html',
        navigateFallbackDenylist: [/^\/api\//],
        runtimeCaching: [
          {
            // ⚠️ 必须用**函数**匹配 `url.pathname`，不能用 `/^\/api\//` 这种 RegExp：
            // Workbox 的 RegExp 是拿**完整 URL**（`http://host/api/...`）去 match 的，
            // 带 `^/` 锚点的模式**永远匹配不上** ⇒ 规则形同虚设。
            // 实测（真实源 + 真实浏览器）：三条运行时规则全都一条缓存都没写进去，
            // 而 sw.js 里规则明明在 —— 这正是 AGENTS.md 的"死配置"家族：不报错、不命中、没人发现。
            urlPattern: ({ url }: { url: URL }) => url.pathname.startsWith('/api/tts/'),
            handler: 'NetworkOnly',
          },
          {
            urlPattern: ({ url }: { url: URL }) => url.pathname.startsWith('/api/search/stream'),
            handler: 'NetworkOnly',
          },
          {
            // 封面接口的真实路径是 /api/covers/<sha256>（见 Routes.kt 的 route("/api") + get("/covers/{key}")）。
            // 此处原先写的是 /api/book/cover —— **该路由不存在**，属于永不命中的死配置，
            // 导致封面既没被缓存、也没被显式排除，语义完全落空（SESSION-027 一并修正）。
            urlPattern: ({ url }: { url: URL }) => url.pathname.startsWith('/api/covers/'),
            handler: 'StaleWhileRevalidate',
            options: {
              cacheName: 'legado-covers',
              expiration: {
                maxEntries: 300,
                maxAgeSeconds: 30 * 24 * 60 * 60,
              },
            },
          },
          {
            urlPattern: ({ url }: { url: URL }) =>
              url.pathname.startsWith('/api/shelf') || url.pathname.startsWith('/api/book/toc'),
            handler: 'NetworkFirst',
            options: {
              cacheName: 'legado-meta-api',
              networkTimeoutSeconds: 3,
              expiration: {
                maxEntries: 200,
                maxAgeSeconds: 7 * 24 * 60 * 60,
              },
            },
          },
          {
            /**
             * 漫画图片（方案 E · P3）。
             *
             * ⚠️ **必须与 `Routes.kt` 里真实注册的直出路由逐字对齐**：真实路径是
             * `route("/api")` + `get("/images/{key}")` ⇒ `/api/images/<sha256>`。
             * 方案 E 原稿写的是 `/api/books/images/...`（那是还没落地的"定位 URL"形态），
             * 照着抄会得到一条**永不命中的死配置**——封面就栽过一次（SESSION-027）。
             *
             * 用 `CacheFirst` 是安全的：图片按内容寻址（key = 字节哈希），内容变了 key 必变，
             * 而且服务端已经声明 `immutable` 长缓存。
             */
            urlPattern: ({ url }: { url: URL }) => url.pathname.startsWith('/api/images/'),
            handler: 'CacheFirst',
            options: {
              cacheName: 'legado-manga',
              expiration: {
                maxEntries: 3000,
                maxAgeSeconds: 180 * 24 * 60 * 60,
              },
              cacheableResponse: { statuses: [0, 200] },
            },
          },
        ],
      },
    }),
  ],
  server: { proxy: { '/api': 'http://localhost:8080' } },
})

