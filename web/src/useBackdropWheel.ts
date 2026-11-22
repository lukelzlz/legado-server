import { useEffect, type RefObject } from 'react'

/**
 * 把「遮罩（磨砂区域）上的滚轮」转发给弹窗内部的可滚动容器。
 *
 * 背景：wheel 事件落在 `.modal-backdrop` 上时，不会自动滚到内部的 `overflow-y:auto`
 * 区域；页面背后又被遮罩挡住 ⇒ 用户感觉「滚轮完全没反应」。
 *
 * 规则：只在 `event.target === backdrop`（指针确实在遮罩上）时转发，
 * 弹窗内部的滚动保持原生行为；必须 `passive: false` 才能 `preventDefault`。
 */
export function useBackdropWheelForward(
  backdropRef: RefObject<HTMLElement | null>,
  scrollRef: RefObject<HTMLElement | null>,
): void {
  useEffect(() => {
    const backdrop = backdropRef.current
    if (!backdrop) return
    const onWheel = (event: WheelEvent) => {
      if (event.target !== backdrop) return
      const list = scrollRef.current
      if (!list) return
      event.preventDefault()
      list.scrollTop += event.deltaY
    }
    backdrop.addEventListener('wheel', onWheel, { passive: false })
    return () => backdrop.removeEventListener('wheel', onWheel)
  }, [backdropRef, scrollRef])
}
