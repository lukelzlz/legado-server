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
  isBackupArchive,
} from '../src/WebDavSettingsPage'
import { encodeWebDavPath, joinWebDavPath, webDavFileUrl } from '../src/api'

test('WebDavSettingsPage - static rendering exposes status and file manager', () => {
  const html = renderToStaticMarkup(React.createElement(WebDavSettingsPage))

  assert.ok(html.includes('WebDAV'), 'page should contain the WebDAV title')
  assert.ok(html.includes('文件服务'), 'page should contain the section kicker')
  assert.ok(html.includes('服务状态'), 'page should contain the status card')
  assert.ok(html.includes('运行中'), 'page should report the service as running')
  assert.ok(html.includes('/webdav'), 'page should show the webdav endpoint')
  assert.ok(html.includes('HTTP Basic'), 'page should describe the auth scheme')
  assert.ok(html.includes('用户名任意填写，密码即当前登录密码'), 'page should explain credentials')
  assert.ok(html.includes('文件管理'), 'page should contain the file manager section')
  assert.ok(html.includes('上传文件'), 'page should offer uploading')
  assert.ok(html.includes('新建文件夹'), 'page should offer creating folders')
  assert.ok(html.includes('正在读取目录…'), 'page should render a loading state before data arrives')
  assert.ok(html.includes('client_max_body_size'), 'page should warn about reverse proxy body limits')
})

test('WebDavSettingsPage - client guide section is gone', () => {
  const html = renderToStaticMarkup(React.createElement(WebDavSettingsPage))

  // 「客户端接入」整段已按要求删除，连带这些指引文案都不该再出现在页面上
  assert.equal(html.includes('客户端接入'), false, 'client guide section should be removed')
  assert.equal(html.includes('Windows 资源管理器'), false, 'windows guide should be gone')
  assert.equal(html.includes('macOS Finder'), false, 'macos guide should be gone')
  assert.equal(html.includes('rclone'), false, 'rclone guide should be gone')
})

test('WebDavSettingsPage - backup export box and button are present', () => {
  const html = renderToStaticMarkup(React.createElement(WebDavSettingsPage))

  assert.ok(html.includes('备份导出'), 'page should contain the export section')
  assert.ok(html.includes('导出路径'), 'export box should offer a path field')
  assert.ok(html.includes('设备名后缀'), 'export box should offer a device-name field')
  assert.ok(html.includes('导出备份'), 'file manager should offer an export button')
  assert.ok(html.includes('backup2026-09-30-web.zip'), 'hint should show the resulting file name shape')
  // 进度同步的「重新读取」按钮已删除（每次进页都会自动读取，手动刷新没有意义）
  assert.equal(html.includes('重新读取'), false, 'the useless refresh button should be removed')
})

test('WebDavSettingsPage - automatic export is a flush-left master switch plus three indented options', () => {
  const html = renderToStaticMarkup(React.createElement(WebDavSettingsPage))

  // 顶格的总开关
  assert.ok(html.includes('自动导出'), 'master switch should be rendered')
  assert.ok(html.includes('总开关'), 'master switch should be explained as the master switch')
  // 它下面三个**缩进的**子选项
  assert.ok(html.includes('export-auto-children'), 'the three sub-options should live in the indented container')
  assert.ok(html.includes('定时导出'), 'scheduled export option should be rendered')
  assert.ok(html.includes('关闭网页时自动导出'), 'page-close trigger should be rendered')
  assert.ok(html.includes('关闭书籍时自动导出'), 'book-close trigger should be rendered')
  // 术语统一成「自动导出」，「特定情况导出」这个分组标题已按要求删除
  assert.equal(html.includes('自动备份'), false, 'wording should be 自动导出, not 自动备份')
  assert.equal(html.includes('特定情况导出'), false, 'the group label should be removed')
  // 时间输入 24 小时制：占位为 03:00（真实默认值由服务端下发），并给出 HH:mm 提示
  assert.ok(html.includes('placeholder="03:00"'), 'scheduled time should hint the 03:00 default')
  assert.ok(html.includes('24 小时制'), 'the time field should say it is a 24-hour clock')
  // 两个字段各自一个保存按钮 ⇒ 加上定时时间共三个保存
  const saveButtons = (html.match(/>保存</g) || []).length
  assert.ok(saveButtons >= 3, `expected at least 3 save buttons, got ${saveButtons}`)
})

test('WebDavSettingsPage - exposes a local book import entry limited to TXT and EPUB', () => {
  const html = renderToStaticMarkup(React.createElement(WebDavSettingsPage))

  assert.ok(html.includes('本地书籍'), 'page should contain the local book section')
  assert.ok(html.includes('导入本地书籍'), 'page should offer importing local books')
  assert.ok(html.includes('TXT'), 'page should name the TXT format')
  assert.ok(html.includes('EPUB'), 'page should name the EPUB format')
  // 文件选择器必须限定格式；用户可以手动切到「所有文件」，所以真正的防线在提交前过滤
  assert.ok(html.includes('accept=".txt,.text,.epub"'), 'the file picker should restrict the extensions')
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

test('WebDavSettingsPage - size formatting follows binary units', () => {
  assert.equal(formatDavSize(0), '0 B')
  assert.equal(formatDavSize(512), '512 B')
  assert.equal(formatDavSize(1024), '1.0 KiB')
  assert.equal(formatDavSize(1536), '1.5 KiB')
  assert.equal(formatDavSize(1024 * 1024), '1.0 MiB')
  assert.equal(formatDavSize(5 * 1024 * 1024 * 1024), '5.0 GiB')
  assert.equal(formatDavSize(Number.NaN), '0 B')
})

test('WebDavSettingsPage - modification time switches to a 24-hour clock after a day', () => {
  // 一律用**本地时间**构造期望值：绝对时刻那档按本地时分渲染，用 Date.UTC 会让断言随时区漂移
  const now = new Date(2026, 8, 17, 12, 0, 0).getTime()
  assert.equal(formatDavTime(0, now), '-')
  assert.equal(formatDavTime(now - 5_000, now), '刚刚')
  assert.equal(formatDavTime(now - 5 * 60_000, now), '5 分钟前')
  assert.equal(formatDavTime(now - 3 * 3_600_000, now), '3 小时前')
  // 满 24 小时就不再是「N 天前」，改成 24 小时制的绝对时刻
  assert.equal(formatDavTime(now - 23 * 3_600_000, now), '23 小时前')
  assert.equal(formatDavTime(now - 25 * 3_600_000, now), '2026-09-16 11:00')
  assert.equal(formatDavTime(new Date(2026, 0, 2, 3, 4, 5).getTime(), now), '2026-01-02 03:04')
  // 下午不会出现 12 小时制的 AM/PM，也不会把 15 点写成 3 点
  assert.equal(formatDavTime(new Date(2026, 0, 2, 15, 30, 0).getTime(), now), '2026-01-02 15:30')
  // 零点补零，避免出现 2026-01-02 3:04 这种不对齐的写法
  assert.equal(formatDavTime(new Date(2026, 0, 2, 0, 5, 0).getTime(), now), '2026-01-02 00:05')
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

test('WebDavSettingsPage - only zip archives expose the backup import entry', () => {
  assert.ok(isBackupArchive('backup2026-07-12-rk3399pro_pcie.zip'), 'legado backup should be importable')
  assert.ok(isBackupArchive('BACKUP.ZIP'), 'detection should ignore case')
  assert.equal(isBackupArchive('book.txt'), false)
  assert.equal(isBackupArchive('archive.zip.txt'), false)
})

test('WebDavSettingsPage - file paths are encoded for urls and joined from the browser', () => {
  assert.equal(encodeWebDavPath('books/我的 书.txt'), 'books/%E6%88%91%E7%9A%84%20%E4%B9%A6.txt')
  assert.equal(encodeWebDavPath('/books//a.txt'), 'books/a.txt')
  assert.equal(webDavFileUrl('books/我的 书.txt'), '/webdav/books/%E6%88%91%E7%9A%84%20%E4%B9%A6.txt')
  assert.equal(joinWebDavPath('', 'a.txt'), 'a.txt')
  assert.equal(joinWebDavPath('books', 'a.txt'), 'books/a.txt')
  assert.equal(joinWebDavPath('books/2026/', 'a.txt'), 'books/2026/a.txt')
})
