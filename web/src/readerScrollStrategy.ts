/**
 * 连续滚动「挂载策略」的运行时选择。
 *
 * ## 背景
 *
 * 同一套滚动引擎，WebKit 与其他引擎需要的**章节挂载行为**不同：
 *
 * - WebKit（iOS Safari / PWA）：在滚动过程中从视口上方摘除章节，会触发 WebKit 私有滚动处理
 *   与滚动位置钳制 —— 实测一帧内文档高度坍缩 8400px、随后单次 `scrollTo` 重定位 8238px。
 *   因此走 `keep`：**只追加不摘章**，跨章根本不改动视口上方的结构（实测跨章那一帧 DOM 变更 0 次、
 *   补偿 0 次，位移正好 = 一步距离）。
 * - Blink（Chrome / Edge / 安卓 WebView）：三章窗口（`window`）表现稳定，保留它把回归面压到最小。
 *
 * ## 为什么判定要确定性
 *
 * 这条判定会决定桌面端用户看到的是「三章窗口」还是「已读章节驻留」，两者观感差别很大
 * （实测：桌面端被误判成 keep 后，段数从 3 涨到 6、不再是三章形态）。
 * 早先版本用「造一个微型滚动容器、移除上方内容、看 scrollTop 会不会被引擎改动」来探测，
 * 结果**两个内核都判成 keep**：实验里外层高度给成了 1000px，可用滚动距离只剩 200px，
 * `scrollTop = 600` 被钳成 200 ⇒ 判为「实验无效」⇒ 兜底返回 keep。教训：兜底方向不能与
 * 「改变既有桌面行为」同向，探测本身也不能依赖脆弱的几何假设。
 *
 * 现在改为**确定性的内核分类**：
 * 1. 有 `Chrome/` 或 `Chromium/` 标记 ⇒ Blink 系（Edge、Chrome、安卓 WebView 都带这个标记，
 *    它们的 UA 里同样有 `AppleWebKit/537.36`，只看 AppleWebKit 会把它们误判成 WebKit）；
 * 2. 否则带 `AppleWebKit/60x`（WebKit 版本号）⇒ WebKit 系（iOS Safari / 桌面 Safari / PWA）；
 * 3. 都认不出 ⇒ 用 `window`（**保守方向**：不改变既有桌面行为；WebKit 若被误判也只是回到旧行为）。
 */

import { DEFAULT_KEEP_BOUNDS, type KeepBounds, type ScrollMountStrategy } from './readerScrollWindow'

export const SCROLL_STRATEGY_STORAGE_KEY = 'legado-reader-scroll-strategy'

/** 内核分类结果：已知 WebKit / 已知 Blink / 认不出。 */
export type ScrollEngineFamily = 'webkit' | 'blink' | 'unknown'

/**
 * 从 UA 判定内核家族。**确定性的字符串判定**，不依赖任何几何假设与运行时状态。
 *
 * 依据（三台真实设备的 UA，已作为测试向量锁定）：
 * - iPhone Safari：`AppleWebKit/605.1.15 … Version/26.6 Mobile/15E148 Safari/604.1`（无 Chrome 标记）
 * - Edge：`AppleWebKit/537.36 … Chrome/154.0.0.0 Safari/537.36 Edg/154.0.0.0`（有 Chrome 标记）
 * - 安卓 WebView：`AppleWebKit/537.36 … Chrome/115.0.5790.168 Mobile Safari/537.36`（有 Chrome 标记）
 */
export function classifyScrollEngine(userAgent: string | undefined): ScrollEngineFamily {
  if (!userAgent) return 'unknown'
  // Blink 系必须先判：它们的 UA 里也带 `AppleWebKit/537.36`
  if (/Chrome\/|Chromium\/|CriOS\//.test(userAgent)) return 'blink'
  if (/AppleWebKit\/\d/.test(userAgent)) return 'webkit'
  return 'unknown'
}

/** 离屏章节的渲染成本靠 `content-visibility: auto` 压掉；不支持时 keep 策略的代价会变大。 */
export function supportsContentVisibility(): boolean {
  if (typeof CSS === 'undefined' || typeof CSS.supports !== 'function') return false
  try {
    return CSS.supports('content-visibility', 'auto')
  } catch {
    return false
  }
}

/**
 * 解析用户/探针通过 localStorage 指定的策略覆盖值。
 * 探针要能在同一台机器的同一内核上把**两条策略**都跑一遍，所以必须可覆盖。
 */
export function readStrategyOverride(): ScrollMountStrategy | null {
  if (typeof localStorage === 'undefined') return null
  try {
    const raw = localStorage.getItem(SCROLL_STRATEGY_STORAGE_KEY)
    return raw === 'keep' || raw === 'window' ? raw : null
  } catch {
    return null
  }
}

/** 当前引擎是否 WebKit —— 用 `navigator.userAgent` 判定（见 [classifyScrollEngine]）。 */
export function detectScrollEngine(): ScrollEngineFamily {
  if (typeof navigator === 'undefined') return 'unknown'
  return classifyScrollEngine(navigator.userAgent)
}

export type ScrollStrategyDecision = {
  strategy: ScrollMountStrategy
  /** 选择该策略的原因，便于自测脚本与排障时自证。 */
  reason: 'override' | 'webkit' | 'blink' | 'unknown-engine'
  engine: ScrollEngineFamily
  keepBounds: KeepBounds
  contentVisibility: boolean
}

/**
 * 决定本次会话使用的挂载策略。
 *
 * WebKit ⇒ `keep`（回避视口上方摘章）；Blink ⇒ `window`（保持既有三章窗口）；
 * 认不出 ⇒ `window`（保守方向：不改变桌面观感）。
 */
export function decideScrollStrategy(
  override: ScrollMountStrategy | null = readStrategyOverride(),
  engine: ScrollEngineFamily = detectScrollEngine(),
): ScrollStrategyDecision {
  if (override) {
    return {
      strategy: override,
      reason: 'override',
      engine,
      keepBounds: DEFAULT_KEEP_BOUNDS,
      contentVisibility: supportsContentVisibility(),
    }
  }
  return {
    strategy: engine === 'webkit' ? 'keep' : 'window',
    reason: engine === 'webkit' ? 'webkit' : engine === 'blink' ? 'blink' : 'unknown-engine',
    engine,
    keepBounds: DEFAULT_KEEP_BOUNDS,
    contentVisibility: supportsContentVisibility(),
  }
}
