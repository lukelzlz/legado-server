# ADR-032 · 源码里的字面量 i18n key 必须在**四语言**都存在且非空，并用 parity 断言守住

- **状态**：Accepted（语言键回填与断言均已实现并验证）
- **关联**：[PROPOSAL-022](../proposals/PROPOSAL-022-fullstack-i18n-and-server-locale-persistence.md) · [ADR-022](ADR-022-react-i18next-and-ktor-accept-language-resolution.md) · [ACCEPT-032](../acceptance/ACCEPT-032-clear-cache-and-reader-ui-batch.md) · [SESSION-049](../sessions/SESSION-049-cache-layout-i18n-and-reader-toc-batch.md)
- **实现**：`web/src/i18n/locales/{zh-CN,zh-TW,en-US,ja-JP}.json`（四语各 +65 条）·
  `web/test/i18n-parity.test.ts`（233 行，4 用例）· `web/test/run-all.ts`（登记）
- **提交**：`55a4008`（分支 `feat/i18n-missing-keys`）

> ⚠️ **集成状态提示（核对时务必先看这一段）**
> 本 ADR 描述的断言**目前只存在于 `feat/i18n-missing-keys` 分支**。
> 在集成分支 `all-prs`（HEAD `f0632a9`）上：四语 locale 的回填**已经在**（1345 键 × 四语一致），
> 但 `web/test/i18n-parity.test.ts` 与 `run-all.ts` 的登记**尚未并入**
> （`git ls-tree all-prs web/test/` 里查不到该文件）。合入该分支后本节描述才完全成立。

---

## 1. 背景

全栈 i18n（[PROPOSAL-022](../proposals/PROPOSAL-022-fullstack-i18n-and-server-locale-persistence.md) / [ADR-022](ADR-022-react-i18next-and-ktor-accept-language-resolution.md)）上线后，
前端用 `react-i18next`，写法普遍是：

```tsx
t('shelf.novelTag', '小说')
```

这段代码**永远不会报错**：key 在 locale 文件里缺失时，i18next 会静默回退到第二个参数的
**中文默认值**。结果就是 —— **英文/日文用户看到中文界面**，而且没有任何日志、没有任何异常。

真实案例（本次扫描发现）：

- `shelf.comicTag` / `shelf.audioTag` 在**四个 locale 文件里都不存在**，只靠 `t(key,'漫画')`
  兜底 ⇒ 书架徽标在 en-US / ja-JP 下**一直是中文**（用户可见 bug，已随书架角标修复补齐）；
- `manga.*` 整个命名空间在 base 里**根本没有**，旧设置抽屉的文案一直靠 `defaultValue` 兜底。

一次独立重扫的规模（提交 `55a4008` 当时）：

| 指标 | 数值 |
| :--- | :--- |
| 扫描的源码文件 | 44 个 `web/src/**/*.{ts,tsx}` |
| `t(...)` 调用点 | 1540 |
| 去重后的**字面量** key | 1173 |
| **四语言全缺失** 的 key | **65**（四语各 +65 条，共 260 条新串） |
| 仅部分语言缺失 | 0 |
| 动态拼接 key（无法静态校验） | 2 处 |

> 这 65 个不是"上一轮报的 63 个"照抄 —— 独立重扫后多出的 2 个正是
> `shelf.comicTag` / `shelf.audioTag`（被书架角标改动引用、仍四语言缺失）。

逐命名空间：`manga.*` 36（全新命名空间）、`source.*` 21、`common.*` 4、`library.*` 2、`shelf.*` 2。
7 个默认值是模板字面量（含 `${...}`），在 locale 里统一改写为 `{{...}}` 占位符（与文件既有写法一致）。

---

## 2. 决策

### 2.1 铁律：源码里 `t('literal.key', '默认值')` 的 key **必须在四语言都存在且不是空串**

四语言 = `zh-CN` / `zh-TW` / `en-US` / `ja-JP`。**"存在"不够，还要"非空"** ——
空串同样会静默回退或显示空白。

### 2.2 用 parity 断言守住（`web/test/i18n-parity.test.ts`，4 个用例）

| # | 用例 | 断言 |
| :--- | :--- | :--- |
| ① | every literal `t()` key in `web/src` exists in all 4 locales | 缺失即列出 `${loc}  ${key}   (used at ${file}:${line})` |
| ② | every referenced key resolves to a **non-empty string** in all 4 locales | 空串（trim 后长度为 0）即失败 |
| ③ | **the scan actually sees a meaningful number of keys** | `USED_KEYS.size > 1000` —— **扫描器自检，防空过** |
| ④ | **no locale may contain a key another locale is missing** | 反向 parity：只在部分语言存在的 key ⇒ 其余语言会回退到 zh-CN（用户看到中文） |

### 2.3 locale 文件是 **import** 进来的，不是从磁盘重读

```ts
import zhCN from '../src/i18n/locales/zh-CN.json'
```

⇒ 断言校验的**正是应用实际打包的那份资源**（避免"磁盘上改了、打进 bundle 的还是旧的"）。

### 2.4 扫描器必须**括号/字符串/注释感知**

`collectLiteralKeys()` 的实现在 `web/test/i18n-parity.test.ts`：

- `matchParen(text, open)`（47 行）：从 `t(` 的 `(` 起找**配对的 `)`**，
  **忽略字符串、模板字面量与注释里的括号**；
- `splitArgs(argText)`（78 行）：按**顶层逗号**切参数（字符串/方括号/花括号感知）；
- `literalString(src)`（113 行）：只接受单/双引号字面量，**模板字面量不是字面量**；
- 只统计**首参数是带点号的引号字符串**的调用（`/^[A-Za-z0-9_$-]+(\.[A-Za-z0-9_$-]+)+$/`）。

这样 `t('shelf.novelTag', { count }, '小说')` / `t('shelf.novelTag', { defaultValue: '小说', count })`
/ `t("shelf.novelTag")` 都能被正确识别，而 `t(\`tts.voice_${id}\`)` 被跳过。

### 2.5 动态拼接 key 的边界：**明确不校验**，但必须自带 fallback

`t(\`tts.voice_${v.id}\`)` 这类 key 是运行期决定的，静态无法验证；**把它们算进来会产生假失败**。
当前的边界写进测试文件注释：

> 今天**恰好 2 处**，都在 `TtsSettingsModal.tsx`（`tts.voice_${v.id}` / `tts.locale_${v.lang}`，
> 同一行 244），**两处都有真实的 fallback**（第二参数是引擎名 / 语言名）。
> 若将来引入新的动态 key，**必须给它一个合理的 inline 默认值**，因为这个测试不会覆盖它。

### 2.6 扫描器自检（防空过）是**独立用例**，不是附带断言

`assert.ok(USED_KEYS.size > 1000, ...)` 单列一条用例。理由：
若正则坏掉（匹配不到任何东西），① ② 两条会**空过**（`missing` 与 `empty` 都是空数组 ⇒ 全绿），
CI 会显示"i18n 很健康"，而实际上什么都没检查。
**凡是"扫源码 + 断言集合为空"的测试，都必须自带一条"扫描到了有意义的量"的自检。**

### 2.7 插入纪律

补 key 时贴邻相关已有键插入，**保持 CRLF、2 空格缩进与键顺序，不整文件重排** ——
locale 文件是多人多分支高频改动的文件，整文件重排会把后续 merge 变成噩梦
（本批就真的踩到了，见 [SESSION-049](../sessions/SESSION-049-cache-layout-i18n-and-reader-toc-batch.md)）。

---

## 3. 备选方案与否决理由

| 备选 | 否决理由 |
| :--- | :--- |
| 靠 code review / 肉眼比对 locale | 1173 个 key × 4 语言，人眼不可靠；缺 key **不报错**，只表现为"某语言显示中文"，极易漏 |
| 运行时在 i18next 的 `missingKeyHandler` 上报 | 只能覆盖**用户实际走到的那条路径**；冷门页面/冷门语言长期不暴露；且生产环境不该为了查 key 而打日志噪音 |
| 让 `t()` 的默认值参数**失效**（缺 key 就显示 key 本身） | 会让所有暂未翻译的内容显示成 `shelf.novelTag` 这种机器串，用户体验更差；而且这会把"缺翻译"从"看起来是中文"变成"看起来是 bug"，掩盖问题而不是暴露问题 |
| 只断言"key 存在"，不管空串 | 空串同样静默回退/显示空白，等于没修 |
| 只做正向（源码 → locale），不做反向（locale → 源码） | 反向不等价：某 key 只在 en-US 存在时，正向若恰好没有源码引用就查不出来，而 zh/ja 用户仍会看到中文 |
| 把动态 key 也算进断言 | 运行期 key 静态不可知，必然产生假失败 |
| 不写"扫描器自检" | 正则一坏，整组断言**静默空过**，CI 全绿而防线归零 |

---

## 4. 影响

**正面**：

- "英文界面里冒中文"这一整类**用户可见缺陷**有了一道**自动化**防线，不再依赖人眼；
- 扫描器自检让"防线本身失效"也能被发现；
- 反向 parity 顺手挡住了"只补了一种语言"的半成品提交。

**代价 / 已知限制**：

- 动态拼接 key（当前 2 处）**不在保护范围**内，只能靠约定（必须自带 inline fallback）+ 代码注释；
- 断言是**源码扫描**（文本级），不是类型级：把 key 写在变量里再传给 `t(varName)` 就会**绕过**
  这条防线（当前代码库没有这种写法）。若将来出现，应改为收敛到字面量；
- 每次新增 key 都要**同时**补四份 locale，PR 的机械成本上升（这是刻意的）；
- key 数量到 1173（提交时）后仍在增长，④ 用例的耗时随 key 数线性增长（当前量级可忽略）。

---

## 5. 验证

- `npx tsx web/test/run-all.ts` ⇒ **254/254 pass**（本分支基线 `6602fc9` 实测 250 ⇒ 净增 4，零回归）；
- `npm --prefix web run check` ⇒ exit 0；
- **负向验证（实测非空洞）**：临时删掉 `ja-JP` 的 `manga.retry` / `manga.noChapters` ⇒
  测试 exit 1、**3 个用例失败**，输出精确指出
  `ja-JP  manga.noChapters   (used at MangaReaderScreen.tsx:192)` 等；
- 四语键总量核对：`zh-CN` / `zh-TW` / `en-US` / `ja-JP` 均为 **1345** 个叶子键（键集完全对称）。
