# 工作记忆：Issue #82 搜索/发现页开书类型链路对抗审查

- **会话编号**：`SESSION-053`
- **日期**：2026-10-22
- **相关 Issue**：[Issue #82：搜索页开书缺书籍类型](https://github.com/lukelzlz/legado-server/issues/82)
- **分支**：`main`（审查当前工作区未提交改动）

---

## 1. 审查目标

确认搜索结果、发现页、详情页和换源流程能否完整传递 `bookKind`，使漫画进入 `MangaReaderScreen`、有声书进入 `AudiobookPlayerScreen`，同时不改变小说和书架既有行为。

## 2. 红蓝对抗发现

### 已确认的正确链路

- 服务端 `SearchResult` 与 `BookDetails` 暴露可选 `bookKind`。
- `RuleRunner` 的声明式、发现页和 JS 书源搜索/详情结果均补齐类型。
- 类型判定沿用既有优先级：已有书架类型 → 书籍 URL 中的 `tab` → 书源 `bookSourceType`。
- 前端 `searchStore.loadSourceBook`、占位详情、`sourceInspector` 与书架离线回退均保留该字段。
- 发现页复用 `loadSourceBook`，无需另起一套类型推断逻辑。

### 审查中发现并修复的问题

换源编排 `switchBookSource` 原先始终沿用旧书的 `current.bookKind`。当详情接口未返回类型、但候选搜索结果带有 `bookKind` 时，换源后的 `OpenBook` 仍可能丢失类型，导致漫画/有声书重新落入文字阅读器。

修复为：

```text
details.bookKind → chosen.result.bookKind → current.bookKind
```

并增加回归断言覆盖“旧书为漫画、候选源为有声书、详情缺省类型”的边界。

## 3. 对抗边界测试

- 书源声明 `bookSourceType=2`：搜索结果与详情均返回 `comic`。
- 聚合源 `data:;base64,...` 载荷包含 `tab=漫画`：书籍自身类型覆盖源兜底。
- 书源声明 `bookSourceType=1`：搜索结果与详情均返回 `audio`。
- 搜索详情缺省 `bookKind` 时，前端回退使用搜索结果字段。
- 换源详情缺省 `bookKind` 时，前端回退使用候选源字段。
- 未分类类型继续保持可选空值，前端按普通文字阅读器处理。

## 4. 实际验证

- `npm --prefix web run check`：通过。
- `npx tsx web/test/run-all.ts`：**376/376 通过**。
- `./gradlew :server:test`：`BUILD SUCCESSFUL`。
- `./gradlew :server:test --tests io.legado.server.SearchRoutesTest`：通过。
- `npx tsx web/test/source-switch-unified.test.ts`：**21/21 通过**。
- `npx tsx web/test/search-store-lifecycle.test.ts`：**7/7 通过**。
- `git diff --check`：通过。
- Docker 联调未执行：当前 Docker daemon 未运行，连接 `/var/run/docker.sock` 失败。

## 5. 结论

本次审查未发现未解决的高风险缺陷。Issue #82 当前达到 **Tested**，但仍未执行 Docker 部署、用户实机验收、Git 提交或推送。
