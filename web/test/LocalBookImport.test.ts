import test from 'node:test'
import assert from 'node:assert/strict'
import { BookshelfItem, LocalBookImportResponse } from '../src/api'
import { LOCAL_BOOK_ACCEPT_ATTR, isSupportedLocalBook, isLocalBookFile, isBackupArchive } from '../src/WebDavSettingsPage'

test('LocalBookImport - api types and local book shelf item identification', () => {
  const localItem: BookshelfItem = {
    sourceId: 'loc_book',
    bookUrl: 'local://123456',
    name: '凡人修仙传',
    author: '忘语',
    tocUrl: 'local://123456/toc',
    coverKey: 'local_cover_hash',
    lastReadAt: Date.now(),
    cachedChapters: 2400,
    totalChapters: 2400,
    cacheState: 'ready',
    completed: false,
  }

  assert.equal(localItem.sourceId, 'loc_book')
  assert.equal(localItem.bookUrl.startsWith('local://'), true)
  assert.equal(localItem.cacheState, 'ready')

  const importResponse: LocalBookImportResponse = {
    total: 2,
    imported: 2,
    failed: 0,
    results: [
      {
        filename: '凡人修仙传.txt',
        success: true,
        bookUrl: 'local://123456',
        name: '凡人修仙传',
        author: '忘语',
        totalChapters: 2400,
      },
      {
        filename: '三体.epub',
        success: true,
        bookUrl: 'local://789012',
        name: '三体',
        author: '刘慈欣',
        totalChapters: 36,
      }
    ]
  }

  assert.equal(importResponse.imported, 2)
  assert.equal(importResponse.failed, 0)
  assert.equal(importResponse.results[0].name, '凡人修仙传')
  assert.equal(importResponse.results[1].author, '刘慈欣')
})

test('LocalBookImport - only TXT and EPUB are accepted', () => {
  // 支持：TXT / EPUB（含 .text 历史命名、大小写随意）
  for (const name of [
    'book.txt', 'book.TXT', 'BOOK.Txt', 'book.text',
    'book.epub', 'book.EPUB', 'A.EpUb',
    '带 空格 的中文书名.txt',
    'a.b.c.txt',            // 多点文件名取最后一段
  ]) {
    assert.equal(isSupportedLocalBook(name), true, `${name} 应被接受`)
  }

  // 拒绝：其它一切格式（尤其 .pdf/.mobi 这些会被后端当成 TXT 硬解析出乱码的）
  for (const name of [
    'book.pdf', 'book.mobi', 'book.azw3', 'book.azw', 'book.fb2', 'book.umd',
    'book.cbz', 'book.cbr', 'book.docx', 'book.doc', 'book.html', 'book.htm',
    'book.zip', 'book.rar', 'book.md', 'book.json', 'book.xml',
    '无扩展名', 'book.', 'book.txt.exe',
  ]) {
    assert.equal(isSupportedLocalBook(name), false, `${name} 应被拒绝`)
  }
})

test('LocalBookImport - accept attribute matches the supported set', () => {
  // `accept` 必须与判定规则一致，否则文件选择器会给出误导性的可选项
  const accepted = LOCAL_BOOK_ACCEPT_ATTR.split(',')
  assert.deepEqual(accepted, ['.txt', '.text', '.epub'])
  for (const ext of accepted) {
    assert.equal(isSupportedLocalBook(`x${ext}`), true, `accept 里的 ${ext} 必须真的被支持`)
  }
})

test('LocalBookImport - batch split keeps valid files and reports the rest', () => {
  // 复刻页面里的分流逻辑：合法项提交、非法项本地拦截并计数
  const all = ['甲.txt', '乙.epub', '丙.pdf', '丁.mobi', '戊.TXT']
  const supported = all.filter(isSupportedLocalBook)
  const rejected = all.filter(name => !isSupportedLocalBook(name))

  assert.deepEqual(supported, ['甲.txt', '乙.epub', '戊.TXT'])
  assert.deepEqual(rejected, ['丙.pdf', '丁.mobi'])
  // 全部非法时必须直接提示、不发请求（避免无意义的往返）
  assert.equal(['a.pdf', 'b.mobi'].filter(isSupportedLocalBook).length, 0)
})

test('LocalBookImport - file manager offers two independent import entries', () => {
  // 文件管理里两种入口靠后缀区分：`.zip` → 备份导入；`.txt/.epub` → 导入书籍
  const files = ['legado-backup.zip', '凡人修仙传.txt', '三体.epub', '说明.pdf', '数据.json']

  const backupEntries = files.filter(isBackupArchive)
  const bookEntries = files.filter(isLocalBookFile)

  assert.deepEqual(backupEntries, ['legado-backup.zip'], '只有 .zip 给「导入」按钮')
  assert.deepEqual(bookEntries, ['凡人修仙传.txt', '三体.epub'], '只有 TXT/EPUB 给「导入书籍」按钮')

  // 两条入口**互斥**：一个文件不该同时出现两个导入按钮
  for (const name of files) {
    assert.equal(
      isBackupArchive(name) && isLocalBookFile(name),
      false,
      `${name} 不应同时命中两种导入入口`,
    )
  }
})

test('LocalBookImport - isLocalBookFile stays in sync with the supported set', () => {
  // 关键约束：文件管理的按钮判定必须与「支持格式」完全一致，
  // 否则会出现「按钮显示了但导入报不支持」的矛盾（或反过来有文件却没有入口）。
  const samples = [
    'a.txt', 'a.TXT', 'a.text', 'a.epub', 'a.EPUB',
    'a.pdf', 'a.mobi', 'a.docx', 'a.zip', '无扩展名',
  ]
  for (const name of samples) {
    assert.equal(
      isLocalBookFile(name),
      isSupportedLocalBook(name),
      `${name} 的两个判定必须一致`,
    )
  }
})

test('LocalBookImport - local book cache badge never displays cache failure', () => {
  // 模拟 cacheBadge 逻辑对本地书的防护
  const cacheBadge = (item: BookshelfItem) => {
    if (item.sourceId === 'loc_book' || item.bookUrl.startsWith('local://')) {
      const count = item.cachedChapters || item.totalChapters || 0
      return count > 0 ? `${count}章已缓存` : null
    }
    if (item.cacheState === 'caching') return '缓存中'
    if (item.cacheState === 'ready') return `${item.cachedChapters}章已缓存`
    if (item.cacheState === 'failed') return '缓存中断'
    return null
  }

  // 哪怕因为历史网络误伤将 cacheState 置为 failed，本地书也决不能展示「缓存中断」
  const damagedLocalBook: BookshelfItem = {
    sourceId: 'loc_book',
    bookUrl: 'local://damaged',
    name: '本地书',
    tocUrl: 'local://damaged/toc',
    lastReadAt: Date.now(),
    cachedChapters: 50,
    totalChapters: 50,
    cacheState: 'failed',
    cacheError: '书源不存在',
    completed: false,
  }

  assert.equal(cacheBadge(damagedLocalBook), '50章已缓存')
})
