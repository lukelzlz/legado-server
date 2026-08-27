import test from 'node:test'
import assert from 'node:assert/strict'
import { matchBestChapter } from '../src/SourceSwitchModal'
import { Chapter } from '../src/api'
import { toast, ToastListener, ToastMessage } from '../src/Toast'

test('SourceSwitch - matchBestChapter - exact title matches', () => {
  const chapters: Chapter[] = [
    { index: 0, title: '第一章 穿越异界', url: 'http://example.com/1' },
    { index: 1, title: '第二章 绝世神功', url: 'http://example.com/2' },
    { index: 2, title: '第三章 家族大比', url: 'http://example.com/3' },
  ]

  const matched = matchBestChapter(chapters, '第二章 绝世神功', 0)
  assert.equal(matched.index, 1)
  assert.equal(matched.title, '第二章 绝世神功')
})

test('SourceSwitch - matchBestChapter - normalized title matching (different chapter format)', () => {
  const chapters: Chapter[] = [
    { index: 0, title: '第1章 穿越异界！', url: 'http://example.com/1' },
    { index: 1, title: '第2章 绝世神功（修）', url: 'http://example.com/2' },
    { index: 2, title: '第3章 家族大比', url: 'http://example.com/3' },
  ]

  // Search with "第二章 绝世神功" should match "第2章 绝世神功（修）" via normalized clean matching
  const matched = matchBestChapter(chapters, '第二章 绝世神功', 0)
  assert.equal(matched.index, 1)
  assert.equal(matched.title, '第2章 绝世神功（修）')
})

test('SourceSwitch - matchBestChapter - fallback to index when title is completely different', () => {
  const chapters: Chapter[] = [
    { index: 0, title: '序章', url: 'http://example.com/0' },
    { index: 1, title: '开启', url: 'http://example.com/1' },
    { index: 2, title: '终曲', url: 'http://example.com/2' },
  ]

  const matched = matchBestChapter(chapters, '第九十九回 飞升大结局', 2)
  assert.equal(matched.index, 2)
  assert.equal(matched.title, '终曲')
})

test('SourceSwitch - matchBestChapter - clamping when target index exceeds chapters length', () => {
  const chapters: Chapter[] = [
    { index: 0, title: '单章', url: 'http://example.com/0' },
  ]

  const matched = matchBestChapter(chapters, '第五十章', 50)
  assert.equal(matched.index, 0)
  assert.equal(matched.title, '单章')
})

test('Toast - pub/sub event lifecycle and id generation', () => {
  let currentList: any[] = []
  const unsubscribe = toast.subscribe(toasts => {
    currentList = toasts
  })

  const id1 = toast.success('缓存完成', 0)
  const id2 = toast.error('网络错误', 0)
  const id3 = toast.warning('书源已失效', 0)
  const id4 = toast.info('正在搜索', 0)

  assert.equal(currentList.length, 4)
  assert.equal(currentList[0].id, id1)
  assert.equal(currentList[0].type, 'success')
  assert.equal(currentList[0].message, '缓存完成')
  assert.equal(currentList[1].id, id2)
  assert.equal(currentList[1].type, 'error')
  assert.equal(currentList[2].id, id3)
  assert.equal(currentList[2].type, 'warning')
  assert.equal(currentList[3].id, id4)
  assert.equal(currentList[3].type, 'info')

  toast.dismiss(id1)
  assert.equal(currentList.length, 3)
  assert.equal(currentList[0].id, id2)

  unsubscribe()
  toast.info('Should not update currentList after unsubscribe', 0)
  assert.equal(currentList.length, 3)
})

