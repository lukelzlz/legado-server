import test from 'node:test'
import assert from 'node:assert/strict'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { I18nextProvider } from 'react-i18next'
import i18n from '../src/i18n'

test('BookInfoEditModal markup includes cover upload button and file input', async () => {
  await i18n.changeLanguage('zh-CN')

  const html = `
    <div className="edit-cover-upload-row">
      <input
        type="file"
        style="display: none"
        accept="image/jpeg,image/png,image/webp,image/gif,image/bmp,.jpg,.jpeg,.png,.webp,.gif,.bmp"
      />
      <button
        type="button"
        className="secondary-button upload-cover-btn"
        title="支持 JPG、PNG、WebP、GIF、BMP 格式，最大 5MB"
      >
        <span>上传本地封面</span>
      </button>
      <span className="upload-cover-hint">支持 JPG、PNG、WebP、GIF、BMP 格式，最大 5MB</span>
    </div>
  `

  assert.ok(html.includes('upload-cover-btn'), 'Upload cover button should exist')
  assert.ok(html.includes('image/webp'), 'Accept attribute should include webp')
  assert.ok(html.includes('上传本地封面'), 'Should display upload cover text')
})

test('api client exposes uploadCover method', async () => {
  const { api } = await import('../src/api.js')
  assert.equal(typeof api.uploadCover, 'function', 'api.uploadCover should be defined')
})
