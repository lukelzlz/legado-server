import test from 'node:test'
import assert from 'node:assert/strict'
import { toast, ToastItem } from '../src/Toast.tsx'

test('toast store - full method suite and timeout dismiss', async () => {
  // Clear any pre-existing toasts from earlier test suites
  const initial = (toast as any).toasts as ToastItem[]
  initial.slice().forEach(t => toast.dismiss(t.id))

  const captured: ToastItem[][] = []
  const unsubscribe = toast.subscribe(items => {
    captured.push(items)
  })

  try {
    const id1 = toast.success('成功提示', 50)
    assert.ok(id1)
    const id2 = toast.info('普通消息', 50)
    assert.ok(id2)
    const id3 = toast.error('错误警告', 50)
    assert.ok(id3)
    const id4 = toast.warning('注意警告', 50)
    assert.ok(id4)

    // Manual dismiss id1
    toast.dismiss(id1)

    // Wait for timeout auto-dismiss of id2, id3, id4 (50ms)
    await new Promise(resolve => setTimeout(resolve, 100))

    const latest = captured.at(-1) || []
    assert.equal(latest.length, 0)
  } finally {
    unsubscribe()
  }
})
