export function extractSourcesFromRaw(raw: unknown): unknown[] {
  if (!raw) return []
  if (Array.isArray(raw)) return raw
  if (typeof raw === 'object') {
    const obj = raw as Record<string, unknown>
    if (Array.isArray(obj.data)) return obj.data
    if (Array.isArray(obj.sources)) return obj.sources
    if (Array.isArray(obj.bookSources)) return obj.bookSources
    if (Array.isArray(obj.list)) return obj.list
    return [obj]
  }
  return []
}

export function parseSourceJsonText(rawText: string): unknown[] {
  const clean = rawText.replace(/^\uFEFF/, '').trim()
  if (!clean) return []
  let parsed: unknown
  try {
    parsed = JSON.parse(clean)
  } catch {
    const lines = clean.split('\n').map(l => l.trim()).filter(Boolean)
    const lineItems: unknown[] = []
    for (const line of lines) {
      try {
        lineItems.push(JSON.parse(line))
      } catch {
        // ignore non-json line
      }
    }
    if (lineItems.length > 0) {
      parsed = lineItems
    } else {
      throw new Error('文件不是有效的 JSON 格式')
    }
  }
  return extractSourcesFromRaw(parsed)
}
