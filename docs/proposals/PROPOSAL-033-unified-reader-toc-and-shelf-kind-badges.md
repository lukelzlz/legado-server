# PROPOSAL-033 · 阅读器目录统一底栏 + 书架类型角标 + 底部按钮动态均分

- **状态**：Implemented（本地已实现并通过单测；待验收）
- **关联**：[ACCEPT-032](../acceptance/ACCEPT-032-clear-cache-and-reader-ui-batch.md) · [SESSION-049](../sessions/SESSION-049-cache-layout-i18n-and-reader-toc-batch.md) · [PROPOSAL-029](PROPOSAL-029-audiobook-support-and-cache-playback.md)（有声书播放器版式） · [PROPOSAL-028](PROPOSAL-028-manga-support-and-content-addressed-cache.md)（漫画阅读器）
- **实现提交**：`95895ab`（漫画目录抽屉缓存栏） · `cf80389`（有声书底部功能条动态均分） · `7029293`（小说角标 + 徽标英日文修复）

---

## 1. 需求来源（用户原话）

> 「在漫画的目录界面添上缓存栏，缓存到浏览器……**像小说的那个一样**」

> 「把有声的 ui 优化，底下四个按钮动态均分」

> 「并且给小说右上角也添上标签」

---

## 2. 现状与缺口（改动前）

| 环节 | 实际状态 |
| :--- | :--- |
| 漫画离线入口 | 藏在**设置抽屉**里（要开设置 → 找「离线缓存」），与「目录」这一最常用的抽屉分离；小说阅读器的缓存入口在**目录底栏** |
| 有声书底部功能条 | `.audiobook-bottom-controls` 写死 `grid-template-columns: repeat(5, 1fr)`，而「缓存」按钮已被另一个 PR 迁进目录抽屉 ⇒ **只剩 4 个按钮却仍按 5 等分**，第 5 列空着、四个按钮挤在左侧 4/5，右侧一条空档 |
| 书架角标 | 只有 `漫画` / `听书` 两种；**小说没有角标**；且 `shelf.comicTag` / `shelf.audioTag` **不在任何 locale 文件里**，只靠 `t(key,'漫画')` 的中文默认值兜底 ⇒ **英/日用户看到的徽标一直是中文** |
| 可测性 | 角标是 `main.tsx` 里两行内联条件，而 `main.tsx` **无法在 Node 里 import**，渲染断言无处可打 |

---

## 3. 目标

1. 漫画的**目录抽屉**底部加缓存栏（本章 / 后 50 章 / 后 100 章 / 全本），
   与小说的目录底栏**同构**，并把设置抽屉里那一节**搬走**（不是复制）。
2. 有声书底部功能条改成「**有几个按钮就均分几份**」，不再写死列数。
3. 书架卡片右上角补「小说」角标（漫画 / 听书 / 小说**三者互斥**，最多一个角标）。
4. 修掉 `漫画` / `听书` 徽标在英日界面显示中文的**用户可见 bug**。

## 4. Non-Goals

- **不做漫画换源**：本轮只接「缓存栏」这一半（换源是独立任务，未接线是**刻意**的）。
- **不新写任何接口**：复用 `api.mangaPrefetch` → `POST /api/books/images/prefetch`。
- **不在测试里断言按钮个数**：按钮个数由"缓存按钮是否已被迁走"决定，断言数量会让测试与另一个 PR 耦合
  ⇒ 断言的是「**动态均分机制存在**」，不是「恰好 4 份」。
- **不改漫画/有声书的阅读内核**：本轮全部是入口位置与排版的调整。

---

## 5. 方案

### 5.1 漫画目录抽屉的缓存底栏（`95895ab`）

`MangaReaderScreen` 的 `reader-drawer-left` 内、`VirtualChapterList` **之后**新增：

```jsx
<footer className="drawer-footer">
  <div className="cache-range-btn-grid">
    <button type="button" className="cache-range-btn" disabled={offlineRunning}
            title={t('manga.offlineCurrentTitle')} onClick={() => requestOffline(0)}>{t('manga.offlineCurrent')}</button>
    <button ... onClick={() => requestOffline(50)}>{t('reader.cacheNext50')}</button>
    <button ... onClick={() => requestOffline(100)}>{t('reader.cacheNext100')}</button>
    <button ... onClick={() => requestOffline(chapters.length)}>{t('reader.cacheAll')}</button>
  </div>
  <small className="setting-hint">{offlineRunning ? t('manga.offlineRunning') : t('manga.offlineHint', {...})}</small>
</footer>
```

- 放在列表**之后** = 底部固定，不用滚动就能点；
- 点击**不关抽屉**（footer 内不出现 `setActiveDrawer`）—— 这样后台缓存跑起来时
  列表里的绿点当场可见，正是用户要的观感；
- 四个按钮全部显式 `type="button"`、`offlineRunning` 时 `disabled`、点击不关抽屉；
- **「离线缓存」整节从设置抽屉搬走**（搬不是复制，避免同一功能两个入口）；
- **绿点图例必须留着** —— 它是"哪些章已可离线读"的唯一解释；
- 整本缓存走 `requestOffline(chapters.length)` ⇒ 服务端 `whole=true` ⇒ **pin 免 GC**。

`styles.css` 顺带：`.cache-range-btn:hover` → `.cache-range-btn:hover:not(:disabled)`
（禁用态不该有 hover 高亮），新增 `.cache-range-btn:disabled { opacity: .55; cursor: not-allowed; }`。

### 5.2 有声书底部功能条动态均分（`cf80389`）

```css
/* 改动前 */ .audiobook-bottom-controls { display: grid; grid-template-columns: repeat(5, 1fr); ... }
/* 改动后 */ .audiobook-bottom-controls { display: flex; ... }              /* gap/padding/border-top/background 原样未动 */
.audiobook-feature { flex: 1 1 0; min-width: 0; ... }                       /* 其余 0 改动 */
.audiobook-feature > span { max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
```

**为什么不用 `grid-auto-columns: 1fr`**：它的实际语义是 `minmax(auto, 1fr)`，
那个 `auto` 最小值**与内容相关**，长文案仍可能把某一份撑宽（隐性失效）；
`flex-basis: 0` + `min-width: 0` 让每份的基准宽度与内容无关，均分是**硬保证**。

**溢出保护为什么加在子 `span` 上而不是按钮基类**：flex 容器自身的 `text-overflow` 不生效，
且图标/数值与文案**两个** `span` 都需要被锁在按钮内。

**复用性核查**：`.audiobook-feature` 全仓**仅在底部功能条使用**（播放设置抽屉用的是
`setting-section` / `cache-range-btn`），故直接改基类；并加了一条反向守卫断言，
将来一旦别处复用该类测试立刻红。

### 5.3 书架类型角标抽成唯一产出点（`7029293`）

新增 `web/src/ShelfKindTag.tsx`（20 行，纯展示、零依赖）：

```jsx
export function ShelfKindTag({ bookKind }: { bookKind?: string }) {
  const { t } = useTranslation()
  return (<>
    {bookKind === 'comic' && <span className="shelf-card-tag-comic">{t('shelf.comicTag', '漫画')}</span>}
    {bookKind === 'audio' && <span className="shelf-card-tag-audio">{t('shelf.audioTag', '听书')}</span>}
    {bookKind !== 'comic' && bookKind !== 'audio' && <span className="shelf-card-tag-novel">{t('shelf.novelTag', '小说')}</span>}
  </>)
}
```

- `bookKind` 由服务端下发（默认 `'novel'`）：不是漫画也不是听书即按小说显示；
- 三者**互斥** ⇒ 任一卡片右上角**最多只有一个**角标（避免两个条件同时命中而叠标签）；
- `main.tsx` 原先两行内联条件 → 一行 `<ShelfKindTag bookKind={item.bookKind} />`；
- `styles.css` 新增 `.shelf-card-tag-novel`，几何与 comic/audio **逐字一致**，
  背景深绿 `rgba(21, 128, 61, .92)`（漫画 `rgba(180, 83, 9, .92)` / 听书 `rgba(29, 78, 216, .92)`，
  均为 `top: 8px; right: 8px`）。

**为什么必须抽成独立模块**：`main.tsx` 末尾有 `createRoot(...)`、顶部 `import './styles.css'`，
在 Node 测试环境**无法 import**，且 `ShelfPage` 未导出、数据靠 `useEffect` 填充 ⇒
要给出**真实渲染断言**就必须抽出组件（否则退化为 JSX 源码级解析）。

**顺带修一个用户可见 bug**：`shelf.comicTag` / `shelf.audioTag` 此前不在任何 locale 文件里，
只靠 `t(key,'漫画')` 的中文默认值兜底 ⇒ **en-US / ja-JP 用户看到的徽标一直是中文**。
现补齐四语：

| key | zh-CN | zh-TW | en-US | ja-JP |
| :--- | :--- | :--- | :--- | :--- |
| `shelf.comicTag` | 漫画 | 漫畫 | Comic | 漫画 |
| `shelf.audioTag` | 听书 | 聽書 | Audio | 朗読 |
| `shelf.novelTag` | 小说 | 小說 | Novel | 小説 |

### 5.4 漫画/有声书缓存栏的 i18n

`95895ab` 新增 / 复用的 key（四语齐全）：

| key | zh-CN | en-US |
| :--- | :--- | :--- |
| `manga.offlineCurrent` | 本章 | This chapter |
| `manga.offlineCurrentTitle` | 缓存当前这一章的全部图片 | Cache every image of the current chapter |
| `manga.offlineAllTitle` | 缓存整本漫画（GC 永不自动淘汰） | Cache the whole manga (never auto-evicted by GC) |
| `manga.offlineHint` | 已离线 {{count}} 章 · 绿点表示整章可离线阅读 | {{count}} chapters offline · … |
| `manga.offlineRunning` | 正在后台缓存，目录里绿点表示该章已可离线阅读 | Caching in the background; … |

另**复用** `reader.cacheNext50(+Title)` / `reader.cacheNext100(+Title)` / `reader.cacheAll`（5 个）。

---

## 6. User Stories

1. 作为漫画读者，我打开**目录抽屉**就能看到缓存栏，点「全本缓存」后**抽屉不关**、
   列表里的绿点**当场变绿**，我知道哪些章已经可以离线读了。
2. 作为漫画读者，我在**设置抽屉里再也看不到**重复的「离线缓存」一节（同一功能只有一个入口）。
3. 作为听书用户，我的底部功能条**四个按钮铺满整行**，右侧不再留空档；文案再长也被截断在按钮内，
   不会把某一份撑宽。
4. 作为读者，我书架上每本书的右上角都有且**只有一个**类型角标：漫画 / 听书 / 小说。
5. 作为英文（或日文）用户，我看到的角标是 `Comic` / `Audio` / `Novel`（`漫画` / `朗読` / `小説`），
   **不再是中文**。

---

## 7. 验收要点

见 [ACCEPT-032](../acceptance/ACCEPT-032-clear-cache-and-reader-ui-batch.md)。核心可观察行为：

- 漫画左抽屉内**恰好一个** `.drawer-footer`、且在章节列表**之后**、四个按钮全 `type="button"`；
- **全页 `.cache-range-btn` 恰好 4 个**（证明「离线缓存」是从设置抽屉**搬走**而不是复制）；
- 设置抽屉段里**不再出现**离线缓存，绿点提示**仍在**；
- 有声书底部功能条**每个直接子元素都是带 `.audiobook-feature` 的 button**，
  且 CSS 里**不再出现** `repeat(` / `grid-template-columns`；
- 切换语言到 en-US / ja-JP 后，书架徽标与漫画缓存栏文案**全部为对应语言**。
