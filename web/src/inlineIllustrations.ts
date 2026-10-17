/**
 * 小说正文插图（`<img>`）的**前端唯一处理点**：解析、代理地址拼装、TTS 文本剔除。
 *
 * ## 为什么正文里会出现 `<img>` 标签
 * 服务端 `RuleRunner.cleanContent()` 现在会保留**安全的**插图标签（只 http(s)、只 `src`/`alt`），
 * 并让它**独占一行**（见 `IllustrationMarkup`）。前端拿到的是纯文本正文，里夹着这些标签。
 *
 * ## 为什么必须重写成服务端代理地址
 * 直接把上游地址塞进 `<img src>`：① 缺 `Referer`/Cookie ⇒ 防盗链必裂图；
 * ② 断网就看不到；③ 把用户 IP 暴露给几十个图床。代理地址由服务端带书源头去取并落盘
 * （`GET /api/books/images/illustration`）。
 *
 * ## 为什么用**相对**地址
 * 相对路径天然同源（不需要 `location.origin`）：组件在 Node 里做静态渲染断言时也不会炸
 * （仓库既有教训：渲染期取 `location` 会直接抛错）。
 */

/** 生成插图代理地址所需的书籍上下文。 */
export type IllustrationContext = {
  sourceId: string
  bookUrl: string
  chapterUrl: string
  /** 目录里的章节下标（服务端只在拿不到章节标题时用它兜底，缺省 0 也无害）。 */
  chapterIndex?: number
}

/** 一行正文被插图切开后的片段。 */
export type ParagraphSegment =
  | { kind: 'text'; text: string }
  | { kind: 'image'; src: string; alt: string }

/** 插图代理路由（**唯一出处**：前端拼地址、测试断言、文档都引用它）。 */
export const ILLUSTRATION_PROXY_PATH = '/api/books/images/illustration'

const IMG_TAG = /<img\b[^>]*>/gi
const SRC_ATTR = /\bsrc\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>=]+))/i
const ALT_ATTR = /\balt\s*=\s*(?:"([^"]*)"|'([^']*)')/i

/**
 * HTML 属性值解码（只处理服务端会写出的那 5 个实体）。
 *
 * 必须做：服务端把 `&` 转义成 `&amp;`（否则 `?a=1&b=2` 这类地址在 HTML 里不合法），
 * 前端若不解码，上游会收到带 `&amp;` 的 query ⇒ 图床返回 403/404 ⇒ 裂图。
 */
export function decodeHtmlAttribute(value: string): string {
  return value
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
}

/** 只认 http(s)：与 `data:` / `javascript:` 划清界限（服务端已经过滤，前端再兜一层）。 */
export function isProxyableIllustration(url: string): boolean {
  const trimmed = url.trim()
  return /^https?:\/\//i.test(trimmed)
}

/** 从一个 `<img …>` 标签里取出 `src` / `alt`（取不到或不可代理返回 null）。 */
export function parseIllustrationTag(tag: string): { src: string; alt: string } | null {
  const srcMatch = SRC_ATTR.exec(tag)
  if (!srcMatch) return null
  const src = decodeHtmlAttribute((srcMatch[1] ?? srcMatch[2] ?? srcMatch[3] ?? '').trim())
  if (!isProxyableIllustration(src)) return null
  const altMatch = ALT_ATTR.exec(tag)
  const alt = altMatch ? decodeHtmlAttribute((altMatch[1] ?? altMatch[2] ?? '').trim()) : ''
  return { src, alt }
}

/**
 * 把一行正文切成「文字 / 插图」片段。
 *
 * 无插图时返回空数组（调用方照旧渲染纯文本，连切分开销都不付）。
 */
export function splitIllustrationSegments(line: string): ParagraphSegment[] {
  if (!line || !line.includes('<img')) return []
  const raw: ParagraphSegment[] = []
  let cursor = 0
  IMG_TAG.lastIndex = 0
  let match: RegExpExecArray | null
  while ((match = IMG_TAG.exec(line)) !== null) {
    const illustration = parseIllustrationTag(match[0])
    const before = line.slice(cursor, match.index)
    if (before.trim()) raw.push({ kind: 'text', text: before })
    if (illustration) raw.push({ kind: 'image', src: illustration.src, alt: illustration.alt })
    cursor = match.index + match[0].length
  }
  const rest = line.slice(cursor)
  if (rest.trim()) raw.push({ kind: 'text', text: rest })
  if (raw.length === 0) return []
  // 被丢掉的危险插图会把两侧文字拆成两个相邻片段 ⇒ 合并掉，避免渲染出一堆空壳节点
  const merged: ParagraphSegment[] = []
  for (const segment of raw) {
    const last = merged[merged.length - 1]
    if (segment.kind === 'text' && last?.kind === 'text') {
      last.text += segment.text
    } else {
      merged.push(segment.kind === 'text' ? { kind: 'text', text: segment.text } : segment)
    }
  }
  return merged
}

/**
 * 拼插图代理地址（**相对路径**，同源）。
 *
 * 参数全部 `encodeURIComponent`：上游地址里带 `&`、`?`、中文都很常见，
 * 拼错一个字符就变成"请求了另一张图"。
 */
export function illustrationProxyUrl(context: IllustrationContext, upstreamUrl: string): string {
  const params = [
    `sourceId=${encodeURIComponent(context.sourceId)}`,
    `bookUrl=${encodeURIComponent(context.bookUrl)}`,
    `chapterUrl=${encodeURIComponent(context.chapterUrl)}`,
    `chapterIndex=${encodeURIComponent(String(context.chapterIndex ?? 0))}`,
    `url=${encodeURIComponent(upstreamUrl)}`,
  ]
  return `${ILLUSTRATION_PROXY_PATH}?${params.join('&')}`
}

/**
 * 剔除正文里的插图标签（**TTS 用**：朗读绝不能把 `img src=…` 念出来）。
 *
 * 段落下标必须保持不变 —— TTS 的段落高亮按 index 对齐，删段落会让高亮整体错位。
 */
export function stripIllustrationTags(text: string): string {
  if (!text || !text.includes('<img')) return text
  return text.replace(IMG_TAG, ' ').replace(/[ \t]{2,}/g, ' ').trim()
}

/** 整章正文转成 TTS 输入（逐段剔除插图标签，段数不变）。 */
export function stripIllustrations(paragraphs: string[]): string[] {
  return paragraphs.map(stripIllustrationTags)
}
