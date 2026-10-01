import test from 'node:test'
import assert from 'node:assert/strict'
import { changeAppLanguage } from '../src/i18n'

/**
 * 「网络导入」弹窗：结构契约 + 四语渲染。
 *
 * 这里刻意只做**静态渲染**断言：弹窗的拉取/落库行为由服务端用例
 * （`NetworkSourceImportTest` / `NetworkImportRouteTest`）覆盖，
 * 前端这一层要盯的是「结构没退化 + 文案真的跟着语言走」。
 */
test('network import - renders the phone-style panel with title, group picker and actions', async () => {
  const React = await import('react')
  const { renderToStaticMarkup } = await import('react-dom/server')
  const { NetworkImportModal } = await import('../src/NetworkImportModal')

  try {
    await changeAppLanguage('zh-CN')
    const html = renderToStaticMarkup(
      React.createElement(NetworkImportModal, {
        groups: ['大灰狼聚合'],
        onClose: () => {},
        onToast: () => {},
      }),
    )

    // 标题 / 自定义源分组 / ⋮ 菜单（对齐手机端「导入书源」面板）
    assert.ok(html.includes('network-import-modal'), '复用主题变量驱动的弹窗容器类')
    assert.ok(html.includes('导入书源'), '标题应为「导入书源」')
    assert.ok(html.includes('自定义源分组'), '应有「自定义源分组」入口')
    assert.ok(html.includes('network-import-more-btn'), '应有 ⋮ 更多按钮')

    // URL 输入 + 拉取 + 取消/确认
    assert.ok(html.includes('network-import-url-input'), '应有地址输入框')
    assert.ok(html.includes('拉取'), '应有「拉取」按钮')
    assert.ok(html.includes('取消'), '应有取消按钮')
    assert.ok(html.includes('确认'), '应有确认按钮')

    // 尚未拉取 ⇒ 无预览列表，且确认按钮不可用（严禁空提交）
    assert.ok(!html.includes('network-import-list'), '未拉取时不应出现书源列表')
    assert.ok(/network-import-confirm-btn[^>]*disabled/.test(html), '未拉取时确认按钮必须禁用')
  } finally {
    await changeAppLanguage('zh-CN')
  }
})

test('network import - follows the active locale (no Chinese leaks in en-US)', async () => {
  const React = await import('react')
  const { renderToStaticMarkup } = await import('react-dom/server')
  const { NetworkImportModal } = await import('../src/NetworkImportModal')

  try {
    await changeAppLanguage('en-US')
    const html = renderToStaticMarkup(
      React.createElement(NetworkImportModal, { onClose: () => {}, onToast: () => {} }),
    )

    assert.ok(html.includes('Import book sources'), 'en-US 标题')
    assert.ok(html.includes('Custom source group'), 'en-US 分组入口')
    assert.ok(html.includes('Fetch'), 'en-US 拉取按钮')
    assert.ok(html.includes('Confirm'), 'en-US 确认按钮')
    assert.ok(!html.includes('导入书源'), 'en-US 下不应出现中文文案')
  } finally {
    await changeAppLanguage('zh-CN')
  }
})
