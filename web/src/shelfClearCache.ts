/**
 * 「清除缓存」的**作用域模型**（服务器 / 浏览器 / 两者）。
 *
 * ## 为什么单独一个纯模块
 * `ShelfPage`、`BookManageModal`、选择范围的弹窗都住在 `main.tsx` 里，而该模块
 * ① `import './styles.css'`、② 模块顶层直接 `createRoot(document.getElementById('root')!)`，
 * 因此 **Node 侧无法 import 它**（实测 `.css` 报 `Unknown file extension`）。
 * 把「作用域 → 精确要执行的清理步骤」抽成这张纯表，才能让
 * **「选服务器就绝不动本机、选浏览器就绝不碰服务端」这条范围正确性被单测真正执行到**，
 * 而不是只能对着 `main.tsx` 的源码 grep 字符串。
 *
 * ## 为什么是表驱动而不是三个 if 分支
 * 四个清理动作用户只能三选一，最容易出的错就是「改了一条分支忘了另一条」⇒ 串范围
 * （例如点"只清浏览器"却把服务端缓存也删了）。所有调用点都从这张表派生
 * ⇒ 范围只在一处定义，改错一处就会被断言当场抓住。
 */

/** 用户可选的清理范围。 */
export type ClearCacheScope = 'server' | 'browser' | 'both'

/** 单个清理动作。前三个是服务端端点，最后一个是浏览器本机 IndexedDB。 */
export type ClearCacheStep = 'images' | 'audio' | 'content' | 'local'

/** 服务端三步（数组顺序即调用顺序：图片 → 音频 → 正文）。 */
export const SERVER_CLEAR_STEPS: readonly ClearCacheStep[] = ['images', 'audio', 'content']

/** 浏览器本机一步（本机离线正文）。 */
export const BROWSER_CLEAR_STEPS: readonly ClearCacheStep[] = ['local']

/** 作用域 → 精确步骤序列。三个选项互斥，`both` 只是两者相加。 */
export const SCOPE_STEPS: Readonly<Record<ClearCacheScope, readonly ClearCacheStep[]>> = {
  server: SERVER_CLEAR_STEPS,
  browser: BROWSER_CLEAR_STEPS,
  both: [...SERVER_CLEAR_STEPS, ...BROWSER_CLEAR_STEPS],
}

/**
 * 取某个作用域要执行的步骤。
 *
 * 未知作用域 ⇒ **空数组**（宁可不删，也绝不越界删）：
 * 这个函数是唯一决定"删什么"的地方，宁可少删让用户重试，也不能因为拿到脏值就全删。
 */
export function stepsForScope(scope: ClearCacheScope): readonly ClearCacheStep[] {
  return SCOPE_STEPS[scope] ?? []
}

/** 该步骤是否打在服务端（供 UI 描述与测试区分"服务端/本机"两类动作）。 */
export function isServerStep(step: ClearCacheStep): boolean {
  return step !== 'local'
}
