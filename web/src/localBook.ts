/**
 * 本地导入书的**唯一判定点**（前端侧）。
 *
 * 判据与服务端 `BookKind.isLocalRef` / `ShelfKind.isLocalBook` 的三条身份判据逐字对齐：
 * `sourceId === 'loc_book'`（本服务自己导入时写的虚拟书源）或 `bookUrl` 以 `local://` 开头。
 *
 * ## 为什么需要它（issue #66 用户要求）
 * 「**本地书根本不要有清除缓存的按钮**」「缓存按钮也不该有」。
 * 本地书的正文是用户上传的原文件解析后落库的，**线上没有"下次阅读会重新下载"这一步**
 * （删了只能靠服务端「重新解析本地文件」从留存的原文件重建）。所以凡是"缓存 / 清除缓存"
 * 的动作，对本地书一律**不渲染**（不是禁用），这才是用户要的语义。
 *
 * 它被三处共用，因此独立成纯模块（可测、不依赖 React）：
 * ① `main.tsx` 的书籍管理弹窗 / 角标接线；② `shelfModalStack.ts` 拒绝把本地书送进清缓存范围弹窗；
 * ③ `ReaderScreen.tsx` 的目录抽屉缓存区隐藏。
 */
export function isLocalBook(item: { sourceId: string; bookUrl: string }): boolean {
  return item.sourceId === 'loc_book' || item.bookUrl.startsWith('local://')
}
