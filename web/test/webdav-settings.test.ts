import test from 'node:test'
import assert from 'node:assert/strict'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { AppHeader } from '../src/AppHeader'
import { defaultReaderSettings } from '../src/readerSettings'
import {
  WebDavSettingsPage,
  davBreadcrumbs,
  formatDavSize,
  formatDavTime,
  webDavClientGuides,
} from '../src/WebDavSettingsPage'
import { encodeWebDavPath, joinWebDavPath, webDavFileUrl } from '../src/api'

test('WebDavSettingsPage - static rendering exposes status, guides and file manager', () => {
  const html = renderToStaticMarkup(React.createElement(WebDavSettingsPage))

  assert.ok(html.includes('WebDAV'), 'page should contain the WebDAV title')
  assert.ok(html.includes('文件服务'), 'page should contain the section kicker')
  assert.ok(html.includes('服务状态'), 'page should contain the status card')
  assert.ok(html.includes('运行中'), 'page should report the service as running')
  assert.ok(html.includes('/webdav'), 'page should show the webdav endpoint')
  assert.ok(html.includes('HTTP Basic'), 'page should describe the auth scheme')
  assert.ok(html.includes('用户名任意填写，密码即当前登录密码'), 'page should explain credentials')
  assert.ok(html.includes('客户端接入'), 'page should contain the client guide section')
  assert.ok(html.includes('Windows 资源管理器'), 'page should guide Windows users')
  assert.ok(html.includes('macOS Finder'), 'page should guide macOS users')
  assert.ok(html.includes('rclone'), 'page should guide rclone users')
  assert.ok(html.includes('Legado App 备份'), 'page should guide Legado App backups')
  assert.ok(html.includes('文件管理'), 'page should contain the file manager section')
  assert.ok(html.includes('上传文件'), 'page should offer uploading')
  assert.ok(html.includes('新建文件夹'), 'page should offer creating folders')
  assert.ok(html.includes('正在读取目录…'), 'page should render a loading state before data arrives')
  assert.ok(html.includes('client_max_body_size'), 'page should warn about reverse proxy body limits')
})

test('WebDavSettingsPage - header exposes a WebDAV navigation entry', () => {
  const html = renderToStaticMarkup(
    React.createElement(AppHeader, {
      page: 'webdav',
      settings: { ...defaultReaderSettings },
      onSettingsChange: () => undefined,
      onNavigate: () => undefined,
      onLogout: () => undefined,
    }),
  )
  assert.ok(html.includes('文件'), 'nav should contain the file service tab')
  assert.ok(html.includes('WebDAV 文件服务'), 'nav tab should describe the WebDAV service')
  assert.ok(html.includes('class="active"'), 'active nav tab should be highlighted for the webdav page')
})

test('WebDavSettingsPage - webdav client guides are derived from the current origin', () => {
  const guides = webDavClientGuides('https://reader.example.com', '/webdav')
  assert.equal(guides.length, 4)
  const url = 'https://reader.example.com/webdav'
  assert.ok(guides.every(guide => guide.command.includes(url)), 'every guide should reference the endpoint')
  const rclone = guides.find(guide => guide.id === 'rclone')
  assert.ok(rclone, 'rclone guide should exist')
  assert.ok(rclone.command.includes('rclone obscure'), 'rclone guide should mask the password')
  assert.ok(rclone.command.includes("legado:books/"), 'rclone guide should show a copy example')
})

test('WebDavSettingsPage - size formatting follows binary units', () => {
  assert.equal(formatDavSize(0), '0 B')
  assert.equal(formatDavSize(512), '512 B')
  assert.equal(formatDavSize(1024), '1.0 KiB')
  assert.equal(formatDavSize(1536), '1.5 KiB')
  assert.equal(formatDavSize(1024 * 1024), '1.0 MiB')
  assert.equal(formatDavSize(5 * 1024 * 1024 * 1024), '5.0 GiB')
  assert.equal(formatDavSize(Number.NaN), '0 B')
})

test('WebDavSettingsPage - modification time falls back to relative wording', () => {
  const now = Date.UTC(2026, 8, 17, 12, 0, 0)
  assert.equal(formatDavTime(0, now), '-')
  assert.equal(formatDavTime(now - 5_000, now), '刚刚')
  assert.equal(formatDavTime(now - 5 * 60_000, now), '5 分钟前')
  assert.equal(formatDavTime(now - 3 * 3_600_000, now), '3 小时前')
  assert.equal(formatDavTime(now - 2 * 86_400_000, now), '2 天前')
  assert.equal(formatDavTime(Date.UTC(2026, 0, 2, 3, 4, 5), now), '2026-01-02')
})

test('WebDavSettingsPage - breadcrumbs describe the current directory chain', () => {
  assert.deepEqual(davBreadcrumbs(''), [{ name: '根目录', path: '' }])
  assert.deepEqual(davBreadcrumbs('books'), [
    { name: '根目录', path: '' },
    { name: 'books', path: 'books' },
  ])
  assert.deepEqual(davBreadcrumbs('books/2026/novels'), [
    { name: '根目录', path: '' },
    { name: 'books', path: 'books' },
    { name: '2026', path: 'books/2026' },
    { name: 'novels', path: 'books/2026/novels' },
  ])
})

test('WebDavSettingsPage - file paths are encoded for urls and joined from the browser', () => {
  assert.equal(encodeWebDavPath('books/我的 书.txt'), 'books/%E6%88%91%E7%9A%84%20%E4%B9%A6.txt')
  assert.equal(encodeWebDavPath('/books//a.txt'), 'books/a.txt')
  assert.equal(webDavFileUrl('books/我的 书.txt'), '/webdav/books/%E6%88%91%E7%9A%84%20%E4%B9%A6.txt')
  assert.equal(joinWebDavPath('', 'a.txt'), 'a.txt')
  assert.equal(joinWebDavPath('books', 'a.txt'), 'books/a.txt')
  assert.equal(joinWebDavPath('books/2026/', 'a.txt'), 'books/2026/a.txt')
})
