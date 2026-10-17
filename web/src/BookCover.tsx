import React, { useState } from 'react'
import { api } from './api'
import { sanitizeImageUrl } from './sourceImport'

/**
 * 封面地址的**候选链**（按优先级）。
 *
 * 1. `coverKey` ⇒ 本服务的本地副本 `/api/covers/<key>`（无外部依赖、离线可用）；
 * 2. `coverUrl` ⇒ 原始外链（备份导入后补抓失败时唯一可显示的来源）。
 *
 * 为什么必须留外链这一档：备份包**只带 `coverUrl`、不带图片本体**。补抓成功才有本地副本；
 * 失败（图床签名过期/防盗链/超时）时若只认 `coverKey`，整架书都会退化成无法显示。
 */
export function coverSources(item: { coverKey?: string | null; coverUrl?: string | null }): string[] {
  const sources: string[] = []
  if (item.coverKey) sources.push(api.cover(item.coverKey))
  const external = sanitizeImageUrl(item.coverUrl)
  // 同一个地址只试一次（编辑弹窗曾把 `/api/covers/<自己的 key>` 误存回 coverUrl，会重复请求）
  if (external && !sources.includes(external)) sources.push(external)
  return sources
}

/**
 * 书架/搜索结果的封面图：**按候选链逐个回退，全部失败显示文字占位**。
 *
 * 关键点：`<img>` 在 404/403 时浏览器会画裂图图标。之前直接 `src={resolveShelfCover(item)}`，
 * 一旦 `cover_key` 有值但服务端取不到文件（布局迁移遗漏、文件被清、导入补抓失败），
 * 就**不会**回退到 `coverUrl`，也不会回退到占位 ⇒ 用户看到满屏裂图。
 * 这里用 `onError` 逐级切换，最后落到首字占位（与没有封面的书表现一致）。
 */
export function BookCover({
  coverKey,
  coverUrl,
  name,
  className,
  fallbackClassName,
  referrerPolicy = 'no-referrer',
}: {
  coverKey?: string | null
  coverUrl?: string | null
  name: string
  /** `<img>` 的 class（与占位 `<span>` 的样式通常不同，故分成两个参数） */
  className?: string
  /** 全部候选都失败时那个文字占位的 class */
  fallbackClassName?: string
  referrerPolicy?: 'no-referrer' | 'origin'
}) {
  const sources = coverSources({ coverKey, coverUrl })
  const [index, setIndex] = useState(0)
  if (index >= sources.length) {
    return <span className={fallbackClassName}>{name.slice(0, 1)}</span>
  }
  return (
    <img
      className={className}
      src={sources[index]}
      alt=""
      referrerPolicy={referrerPolicy}
      onError={() => setIndex(current => current + 1)}
    />
  )
}
