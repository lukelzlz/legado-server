import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import '../src/i18n'
import { ButtonSpinner } from '../src/ButtonSpinner.tsx'

/**
 * 通用加载动画与防连击保护 (Issue #67) 的回归契约测试。
 *
 * 核心验证：
 * 1. ButtonSpinner 渲染规范与无障碍属性 (role="status", aria-label="loading")；
 * 2. styles.css 中的 .btn-spinner 与 .is-loading / aria-busy 防连击样式守卫；
 * 3. 换源、清除缓存、书籍编辑保存、书源登录、网络导入等关键场景的 Spinner 与禁用保护。
 */

const here = path.dirname(fileURLToPath(import.meta.url))
const sourceOf = (file: string) => fs.readFileSync(path.join(here, '..', 'src', file), 'utf8')
const stylesCss = () => sourceOf('styles.css')

test('loading states - ButtonSpinner 渲染契约与无障碍属性', () => {
  const htmlDefault = renderToStaticMarkup(React.createElement(ButtonSpinner))
  assert.ok(htmlDefault.includes('class="btn-spinner"'), '默认必须包含 btn-spinner class')
  assert.ok(htmlDefault.includes('role="status"'), '必须声明 role="status"')
  assert.ok(htmlDefault.includes('aria-label="loading"'), '默认 aria-label 应为 loading')

  const htmlSmall = renderToStaticMarkup(React.createElement(ButtonSpinner, { size: 'sm', label: '加载中' }))
  assert.ok(htmlSmall.includes('btn-spinner-sm'), '小号 spinner 必须携带 btn-spinner-sm class')
  assert.ok(htmlSmall.includes('aria-label="加载中"'), '自定义 label 必须生效')

  const htmlCustomNumber = renderToStaticMarkup(React.createElement(ButtonSpinner, { size: 16 }))
  assert.ok(htmlCustomNumber.includes('width:16px'), '指定像素尺寸必须写入内联 style')
  assert.ok(htmlCustomNumber.includes('height:16px'), '指定像素尺寸必须写入内联 style')
})

test('loading states - CSS 样式规范：btn-spinner 旋转动画与 prefers-reduced-motion 守卫', () => {
  const css = stylesCss()

  const spinner = /\.btn-spinner \{([^}]*)\}/.exec(css)
  assert.ok(spinner, '必须定义 .btn-spinner 基础类名')
  const spinnerRules = spinner![1]
  assert.ok(spinnerRules.includes('currentColor'), 'Spinner 边框颜色必须继承 currentColor，适配所有按钮主题色')
  assert.ok(spinnerRules.includes('btnSpin'), '必须绑定 btnSpin 动画')

  assert.ok(css.includes('@keyframes btnSpin'), '必须声明 @keyframes btnSpin')

  const motionReduced = /@media \(prefers-reduced-motion: reduce\) \{[\s\S]*?\.btn-spinner \{([^}]*)\}/.exec(css)
  assert.ok(motionReduced, '必须在 prefers-reduced-motion 下为 .btn-spinner 配置减速或减动效规则')

  // 必须声明防连击交互锁：pointer-events: none 与 cursor: wait
  const loadingLock = /\.primary-button\.is-loading,[\s\S]*?button\[aria-busy="true"\] \{([^}]*)\}/.exec(css)
  assert.ok(loadingLock, '必须为各类按钮的 .is-loading / [aria-busy="true"] 声明防连击锁')
  const lockRules = loadingLock![1]
  assert.ok(lockRules.includes('pointer-events: none'), 'loading 按钮必须包含 pointer-events: none 防止重复点击')
  assert.ok(lockRules.includes('cursor: wait'), 'loading 按钮鼠标光标必须为 wait')
})

test('loading states - 换源弹窗 (sourceSwitch.tsx) 切换时按钮转圈与防连击互斥锁', () => {
  const src = sourceOf('sourceSwitch.tsx')

  assert.ok(src.includes("import { ButtonSpinner } from './ButtonSpinner'"), '换源模块必须引入 ButtonSpinner')
  assert.ok(src.includes('isSwitching ? \'is-loading\' : \'\''), '切换中的候选按钮必须添加 is-loading class')
  assert.ok(src.includes('aria-busy={isSwitching}'), '切换中的候选按钮必须声明 aria-busy')
  assert.ok(src.includes('isSwitching && <ButtonSpinner size={12} />'), '切换中必须渲染 ButtonSpinner')
  assert.ok(src.includes('disabled={isSwitching || switchingSourceId !== null}'), '任一源切换中必须禁用所有候选的切换按钮，防止多次并发切源')
})

test('loading states - 清除缓存范围弹窗 (main.tsx) 选中清理项转圈与互斥禁用', () => {
  const src = sourceOf('main.tsx')

  assert.ok(src.includes('aria-busy={busyScope === \'server\'}'), '选中的清除范围项必须增加 aria-busy')
  assert.ok(src.includes('busyScope === \'server\' ? <ButtonSpinner size={14} /> : <Icon name="arrowRight" />'), '选中的范围项右侧箭头必须替换为 ButtonSpinner')
  assert.ok(src.includes('const [clearingScope, setClearingScope] = useState<ClearCacheScope | null>(null)'), '必须跟踪当前正在清除的 scope')
})

test('loading states - 本地书重新解析与重洗正文 (main.tsx) 转圈与禁用保护', () => {
  const src = sourceOf('main.tsx')

  assert.ok(src.includes('recleaning ? <ButtonSpinner size={14} /> : <Icon name="arrowRight" />'), '重新清洗中右侧箭头必须替换为 ButtonSpinner')
  assert.ok(src.includes('reparsing ? <ButtonSpinner size={14} /> : <Icon name="arrowRight" />'), '重新解析中右侧箭头必须替换为 ButtonSpinner')
  assert.ok(src.includes('recleaning ? \'is-loading\' : \'\''), '重新清洗行必须携带 is-loading')
  assert.ok(src.includes('reparsing ? \'is-loading\' : \'\''), '重新解析行必须携带 is-loading')
})

test('loading states - 书籍编辑保存、联网搜索与封面上传 (main.tsx) 转圈与防连击', () => {
  const src = sourceOf('main.tsx')

  assert.ok(src.includes('saving && <ButtonSpinner size={14} />'), '保存修改中必须显示 ButtonSpinner')
  assert.ok(src.includes('saving ? \'is-loading\' : \'\''), '保存修改中必须附带 is-loading')
  assert.ok(src.includes('uploadingCover ? <ButtonSpinner size={12} /> : <Icon name="upload" />'), '封面上传中必须显示 ButtonSpinner')
  assert.ok(src.includes('uploadingCover ? \'is-loading\' : \'\''), '封面上传按钮必须附带 is-loading')
})

test('loading states - 书源登录弹窗 (SourceLoginModal.tsx) 动作执行转圈与锁定', () => {
  const src = sourceOf('SourceLoginModal.tsx')

  assert.ok(src.includes("import { ButtonSpinner } from './ButtonSpinner'"), '书源登录必须引入 ButtonSpinner')
  assert.ok(src.includes('isThisAction && <ButtonSpinner size={12} />'), '被触发的 action 按钮必须展示 ButtonSpinner')
  assert.ok(src.includes('isThisAction ? \'is-loading\' : \'\''), '被触发的 action 按钮必须附带 is-loading')
  assert.ok(src.includes('executing && executingAction === \'login(true)\' ? <ButtonSpinner size={12} /> : \'✓\''), '保存并登录执行中必须展示 ButtonSpinner')
})

test('loading states - 网络书源导入 (NetworkImportModal.tsx) 拉取与确认转圈与禁用', () => {
  const src = sourceOf('NetworkImportModal.tsx')

  assert.ok(src.includes("import { ButtonSpinner } from './ButtonSpinner'"), '网络导入必须引入 ButtonSpinner')
  assert.ok(src.includes('fetching && <ButtonSpinner size={12} />'), '拉取中必须展示 ButtonSpinner')
  assert.ok(src.includes('committing && <ButtonSpinner size={12} />'), '确认导入中必须展示 ButtonSpinner')
  assert.ok(src.includes('fetching ? \'is-loading\' : \'\''), '拉取按钮必须附加 is-loading')
  assert.ok(src.includes('committing ? \'is-loading\' : \'\''), '确认按钮必须附加 is-loading')
})

test('loading states - 阅读器目录抽屉缓存 (ReaderScreen.tsx) 范围点击即时转圈与防连击', () => {
  const src = sourceOf('ReaderScreen.tsx')

  assert.ok(src.includes("import { ButtonSpinner } from './ButtonSpinner'"), '阅读器必须引入 ButtonSpinner')
  assert.ok(src.includes('requestingRange === \'next50\' && <ButtonSpinner size={12} />'), '后50章缓存点击必须即时转圈')
  assert.ok(src.includes('requestingRange === \'all\' && <ButtonSpinner size={12} />'), '全本缓存点击必须即时转圈')
  assert.ok(src.includes('disabled={requestingRange !== null}'), '发起范围缓存中必须禁用其他按钮防连击')
})
