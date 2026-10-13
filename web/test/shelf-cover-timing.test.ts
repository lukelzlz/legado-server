import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

// 书架封面的「获取时机」前端契约。
//
// 用户明确要求：封面只在 ①刚导入 ②加入书架 ③手动修改 这三种时机获取；
// **打开书阅读后返回书架不许刷新封面**。曾经违反这条的写法是：
//   openReader() 里无条件调 api.addToBookshelf(...)，且它算出的封面地址
//   对「只靠外链、没有本地副本」的书是 undefined ⇒ 服务端把 cover_url 抹成 NULL
//   ⇒ 该书的封面永久消失（实测 2026-10-03 弄丢 2 本书的封面）。
//
// 服务端侧的语义由 ShelfCoverPreservationTest（Kotlin）锁定；这里锁定前端不再发出这种请求。

const source = readFileSync(new URL('../src/main.tsx', import.meta.url), 'utf8')

/** 截取一个具名箭头函数/方法体（按花括号配平），避免拿整份文件做字符串包含式断言。 */
function extractFunctionBody(text: string, signature: string): string | null {
  const start = text.indexOf(signature)
  if (start < 0) return null
  const open = text.indexOf('{', start)
  if (open < 0) return null
  let depth = 0
  for (let i = open; i < text.length; i++) {
    const ch = text[i]
    if (ch === '{') depth++
    else if (ch === '}') {
      depth--
      if (depth === 0) return text.slice(open, i + 1)
    }
  }
  return null
}

describe('ShelfCover - 打开阅读不得写书架', () => {
  it('openReader 不再调用 addToBookshelf', () => {
    const body = extractFunctionBody(source, 'const openReader = (')
    assert.ok(body, '找不到 openReader 函数体')
    // 只匹配真正的调用形式（api.addToBookshelf(...)），注释里提到这个方法名不算
    assert.equal(
      /api\s*\.\s*addToBookshelf\s*\(/.test(body!),
      false,
      'openReader 里又出现了 api.addToBookshelf( —— 开书会写书架行，会把只靠外链的书的 cover_url 抹掉',
    )
  })

  it('开书架那条路径会把本条 coverUrl 作为显示回退', () => {
    const body = extractFunctionBody(source, 'const openShelfItem = async (')
    assert.ok(body, '找不到 openShelfItem 函数体')
    assert.ok(
      body!.includes('item.coverUrl'),
      'openShelfItem 的 fallbackCover 必须带上 item.coverUrl：没有本地副本的书只有外链可显示',
    )
  })

  it('编辑弹窗提交时必须区分「没动过」与「清除」', () => {
    // 缺省 ⇒ undefined（服务端保留原值）；显式清除 ⇒ 空串（服务端清空）。
    // 两者混为一谈就会出现「只改书名点保存把封面抹掉」或「清除按钮点了没反应」。
    assert.ok(
      source.includes("coverUrl === null ? undefined : coverUrl === '' ? '' : externalCover"),
      'updateBookshelfInfo 的 coverUrl 三态未区分：应为 null⇒undefined、""⇒""、其它⇒externalCover',
    )
  })
})
