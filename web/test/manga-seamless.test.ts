import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import '../src/i18n'
import { mangaImageFrameClass } from '../src/MangaReaderScreen'

const here = path.dirname(fileURLToPath(import.meta.url))
const read = (relative: string) => readFileSync(path.resolve(here, relative), 'utf8')
const css = read('../src/styles.css')
const componentSource = read('../src/MangaReaderScreen.tsx')

/** 取出某个选择器的规则块（第一个匹配），断言时只关心块内声明。 */
function ruleBlock(selector: string): string {
  const index = css.indexOf(selector)
  assert.ok(index >= 0, `styles.css 必须存在规则：${selector}`)
  const open = css.indexOf('{', index)
  const close = css.indexOf('}', open)
  // 必须先剥掉注释：改动说明的注释**会引用旧值**（如 `margin: 0 auto 6px`），
  // 不剥掉就会拿说明文字去断言实现，得到假失败（本次实测踩过）。
  return css.slice(open + 1, close).replace(/\/\*[\s\S]*?\*\//g, '')
}

test('无缝拼接 - 相邻图片框之间不得有任何间隙或色带', () => {
  const frame = ruleBlock('.manga-image-frame {')

  // 回归点：原实现是 `margin: 0 auto 6px` —— 每张图之间固定 6px 缝（白条就是这么来的）
  assert.ok(!/margin\s*:\s*0\s+auto\s+6px/.test(frame), '不得再有 6px 图间距')
  assert.ok(!/margin-bottom\s*:\s*[1-9]/.test(frame), '不得再有正的下外边距')
  // 图框自身铺底色 = 缝隙会显形（容器已有整体背景）
  assert.ok(!/background\s*:\s*var\(--surface\)/.test(frame), '图框自身不得铺不透明底色')
})

test('无缝拼接 - 60vh 占位只在图片加载出来之前生效', () => {
  // 常驻 60vh 会让每张短图上下空出底色 ⇒ 同样是"白条"
  assert.ok(!/min-height/.test(ruleBlock('.manga-image-frame {')), '基础规则不得常驻 min-height')

  const placeholder = ruleBlock('.manga-image-frame:not(.is-loaded)')
  assert.ok(/min-height\s*:\s*60vh/.test(placeholder), '未加载态必须保留 60vh 骨架（避免首屏跳动）')

  const loadedRule = ruleBlock('.manga-image-frame.is-loaded')
  assert.ok(/min-height\s*:\s*0/.test(loadedRule), '加载完成必须收回占位高度')
})

test('无缝拼接 - 加载完成后必须真的带上 is-loaded class', () => {
  // CSS 改对了但组件忘加 class 的话，修复会静默失效（页面毫无变化）—— 两个分支都要钉住
  assert.equal(mangaImageFrameClass(false), 'manga-image-frame')
  assert.equal(mangaImageFrameClass(true), 'manga-image-frame is-loaded')
})

test('无缝拼接 - 组件确实把这个 class 接在了图片框上', () => {
  // 专门盯住"最后一公里"：图片框的 className 必须由该 helper 决定
  assert.ok(
    componentSource.includes('className={mangaImageFrameClass(loaded.has(item.index))}'),
    '图片框的 className 必须由 mangaImageFrameClass(loaded.has(...)) 决定',
  )
})
