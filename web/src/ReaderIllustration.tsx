import React, { useCallback, useState } from 'react'
import { illustrationProxyUrl, type IllustrationContext } from './inlineIllustrations'

/**
 * 阅读器正文插图的**渲染组件**（图片本体 + 点击放大灯箱）。
 *
 * 为什么单独成文件（而不是塞进 `ReaderScreen`）：`ReaderScreen.tsx` 与 `main.tsx` 一样在
 * Node 里无法整块 import（`styles.css` 的 CSS import 会直接抛 `Unknown file extension`），
 * 那样"渲染断言"就只能退化成 JSX 源码 grep（仓库既有教训：`ShelfKindTag.tsx` 就是为此抽出来的）。
 *
 * ## 三条必须钉死的属性
 * - `loading="lazy"`：一章几十张插图时不要一次性抢首屏带宽；
 * - `referrerPolicy="no-referrer"`：**绝不把本站地址/用户信息带给图床**（字节由服务端代取）；
 * - `src` 永远是**本站代理地址**（`/api/books/images/illustration`）：直连上游必裂图。
 *   这三条在 `web/test/inline-illustrations.test.ts` 里逐条断言。
 *
 * ## 点击为什么用 `<button>` 包住图片
 * ① 键盘可达（`Enter`/`Space`）；
 * ② `isInteractiveReaderTarget()` 已经把 `button` 视为交互元素 ⇒ **点插图不会误触发翻页**，
 *    不需要再给阅读器手势逻辑加特例。
 */

export type IllustrationButtonProps = {
  type: 'button'
  className: string
  'data-reader-illustration': string
  'aria-label': string
  onClick: (event: React.MouseEvent<HTMLButtonElement>) => void
  onPointerDown: (event: React.PointerEvent<HTMLButtonElement>) => void
}

export type IllustrationImageProps = {
  className: string
  src: string
  alt: string
  loading: 'lazy'
  decoding: 'async'
  /**
   * 用 React 的正式属性名 `referrerPolicy`（写小写会触发 React 的 "Invalid DOM property" 告警）。
   * React 19 会把它原样输出成 `referrerPolicy="no-referrer"`；HTML 属性名大小写不敏感，
   * 浏览器按标准的 `referrerpolicy` 生效 ⇒ 图床拿不到本站地址与用户信息。
   */
  referrerPolicy: 'no-referrer'
  draggable: boolean
  onLoad?: () => void
  onError?: () => void
}

/** 点击插图 ⇒ 打开灯箱（`src` 是**代理地址**：灯箱里也绝不能直连上游）。 */
export function buildIllustrationButtonProps(
  src: string,
  alt: string,
  onOpen: (src: string, alt: string) => void,
  label: string,
): IllustrationButtonProps {
  return {
    type: 'button',
    className: 'reader-illustration',
    'data-reader-illustration': 'true',
    'aria-label': alt || label,
    onClick: event => {
      // 阻止冒泡：段落上的 TTS 点选与阅读器手势都不该被"点图"触发
      event.stopPropagation()
      onOpen(src, alt)
    },
    onPointerDown: event => event.stopPropagation(),
  }
}

/** 图片本体的属性（懒加载 + 不带 Referer + 不可拖拽）。 */
export function buildIllustrationImgProps(
  src: string,
  alt: string,
  onLoad?: () => void,
  onError?: () => void,
): IllustrationImageProps {
  return {
    className: 'reader-illustration-img',
    src,
    alt,
    loading: 'lazy',
    decoding: 'async',
    referrerPolicy: 'no-referrer',
    draggable: false,
    onLoad,
    onError,
  }
}

export type IllustrationViewArgs = {
  /** 代理地址（由 `illustrationProxyUrl` 产出）。 */
  imageSrc: string
  alt: string
  /** 加载失败 ⇒ 渲染占位块（**绝不显示浏览器的裂图图标**）。 */
  failed: boolean
  openLabel: string
  failedLabel: string
  onOpen: () => void
  onError: () => void
  onLoad?: () => void
}

/**
 * 插图的**纯视图**（无状态，可直接在 Node 里做静态渲染断言）。
 *
 * 组件只负责"何时算失败"，视图负责"失败时长什么样"。
 */
export function illustrationImageView(args: IllustrationViewArgs): React.ReactElement {
  const buttonProps = buildIllustrationButtonProps(args.imageSrc, args.alt, args.onOpen, args.openLabel)
  if (args.failed) {
    return (
      <span className="reader-illustration is-failed" role="img" aria-label={args.failedLabel} data-reader-illustration="true">
        <span className="reader-illustration-failed-text">{args.failedLabel}</span>
      </span>
    )
  }
  return (
    <button {...buttonProps}>
      <img {...buildIllustrationImgProps(args.imageSrc, args.alt, args.onLoad, args.onError)} />
    </button>
  )
}

export type InlineIllustrationProps = {
  context: IllustrationContext
  /** 上游插图地址（服务端保留在正文里的 `src`）。 */
  upstreamUrl: string
  alt: string
  onOpen: (proxySrc: string, alt: string) => void
  /** i18n 文案由调用方给（组件不引 i18n，才能在 Node 里裸渲染断言）。 */
  openLabel: string
  failedLabel: string
  /** 图片尺寸就绪（滚动模式要靠它触发一次几何补偿，见 ReaderScreen）。 */
  onSettled?: () => void
}

/** 正文里的一张插图：懒加载 + 失败占位 + 点击开灯箱。 */
export function InlineIllustration(props: InlineIllustrationProps): React.ReactElement {
  const [failed, setFailed] = useState(false)
  const imageSrc = illustrationProxyUrl(props.context, props.upstreamUrl)
  const handleError = useCallback(() => setFailed(true), [])
  const handleOpen = useCallback(() => props.onOpen(imageSrc, props.alt), [imageSrc, props.alt, props.onOpen])
  return illustrationImageView({
    imageSrc,
    alt: props.alt,
    failed,
    openLabel: props.openLabel,
    failedLabel: props.failedLabel,
    onOpen: handleOpen,
    onError: handleError,
    onLoad: props.onSettled,
  })
}

export type LightboxBackdropProps = {
  className: string
  role: 'dialog'
  'aria-modal': 'true'
  'data-illustration-lightbox': string
  onClick: (event: React.MouseEvent<HTMLDivElement>) => void
}

/** 灯箱遮罩的属性（点空白处关闭）。 */
export function buildLightboxBackdropProps(onClose: () => void): LightboxBackdropProps {
  return {
    className: 'illustration-lightbox',
    role: 'dialog',
    'aria-modal': 'true',
    'data-illustration-lightbox': 'true',
    onClick: () => onClose(),
  }
}

export type IllustrationLightboxProps = {
  /** 代理地址（点开的就是已在正文里显示的那份字节，不会二次回源）。 */
  src: string
  alt: string
  closeLabel: string
  onClose: () => void
}

/**
 * 插图灯箱：全屏黑底 + `object-fit: contain`。
 *
 * 刻意**不用** `createPortal`：阅读器根节点 `.reader-workspace` 没有 `transform` / `backdrop-filter`，
 * `position: fixed` 不会退化成"相对祖先定位"（仓库既有教训：包含块陷阱）。
 * 不用 Portal 还能让静态渲染断言直接拿到结构。
 */
export function IllustrationLightbox(props: IllustrationLightboxProps): React.ReactElement {
  const backdrop = buildLightboxBackdropProps(props.onClose)
  return (
    <div {...backdrop}>
      <button
        type="button"
        className="illustration-lightbox-close"
        aria-label={props.closeLabel}
        onClick={event => {
          event.stopPropagation()
          props.onClose()
        }}
      >
        ×
      </button>
      <img
        className="illustration-lightbox-image"
        src={props.src}
        alt={props.alt}
        referrerPolicy="no-referrer"
        // 点图片本身不该关闭（只有点周围空白处才关）
        onClick={event => event.stopPropagation()}
      />
    </div>
  )
}
