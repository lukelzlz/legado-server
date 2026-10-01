/**
 * 书源导入的**前端**辅助。
 *
 * ⚠️ 这里**刻意不再有 JSON 解析器**：本地文件导入与网络导入现在共用**服务端**的
 * `SourceCodec.parseSourceList`（唯一口径），前端只负责把文件原文读出来交给服务端。
 *
 * 历史教训：前端曾有一份 `parseSourceJsonText`，比服务端多一个「一行一条 JSON（NDJSON）」的兜底，
 * 于是同一份文件「本地导入能用、走网络导入却报无效」。修法是把那份容忍度**上移到服务端**
 * （见 `SourceCodec.parseLineDelimited`），而不是继续维护两份会漂移的实现。
 */

export function sanitizeImageUrl(url: string | null | undefined): string | null {
  if (!url) return null
  const trimmed = url.trim()
  if (!trimmed) return null
  if (trimmed.startsWith('/') && !trimmed.startsWith('//') && !trimmed.includes('\\')) {
    return encodeURI(trimmed)
  }
  try {
    const parsed = new URL(trimmed)
    if (parsed.protocol === 'http:' || parsed.protocol === 'https:') {
      return parsed.href
    }
  } catch {
    return null
  }
  return null
}
