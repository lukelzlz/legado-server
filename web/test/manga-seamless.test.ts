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

test('工具条隐藏 - 加载进度条必须跟着顶栏一起离开画面（issue #65）', () => {
  // ① 回归点：旧实现只写了 `opacity: .4` —— 进度条仍固定在 safe-top + 52px 悬在正文上
  const hidden = ruleBlock('.manga-workspace.toolbars-hidden .manga-progress-bar')
  assert.ok(!/opacity\s*:\s*\.?4\s*;/.test(hidden), '隐藏态不得再只降到 opacity:.4（会悬浮在画面上）')
  assert.match(hidden, /opacity\s*:\s*0\s*;/, '隐藏态必须 opacity:0')
  assert.match(hidden, /pointer-events\s*:\s*none/, '隐藏态必须 pointer-events:none，避免细线拦截点击')
  // ② 必须真的移走，而不是原地留着一条线
  const shift = hidden.match(/translateY\(\s*([^;]+?)\s*\)/)
  assert.ok(shift, '隐藏态必须用 translateY(...) 把进度条上移')
  assert.ok(shift![1].includes('-'), `位移必须是向上的负值（当前：${shift![1]}）`)

  // ③ 恢复时"平滑回到原位"：过渡必须与悬浮顶栏逐字一致（同曲线、同时长）
  const base = ruleBlock('.manga-progress-bar {')
  const header = ruleBlock('.reader-header {')
  const transitionOf = (block: string) =>
    (block.match(/transition\s*:\s*([^;]+);/)?.[1] ?? '').replace(/\s+/g, ' ').trim()
  assert.ok(transitionOf(base), '进度条必须声明 transition，否则唤出工具条时是硬跳')
  assert.equal(
    transitionOf(base),
    transitionOf(header),
    '进度条的 transition 必须与 .reader-header 完全一致（transform + opacity 同曲线）',
  )
})

test('无缝拼接 - 组件确实把这个 class 接在了图片框上', () => {
  // 专门盯住"最后一公里"：图片框的 className 必须由该 helper 决定
  assert.ok(
    componentSource.includes('className={mangaImageFrameClass(loaded.has(item.index))}'),
    '图片框的 className 必须由 mangaImageFrameClass(loaded.has(...)) 决定',
  )
})
