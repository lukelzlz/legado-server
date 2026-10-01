import React from 'react'
import test from 'node:test'
import assert from 'node:assert/strict'
import { renderToStaticMarkup } from 'react-dom/server'
import { AppHeader } from '../src/AppHeader'
import { ExplorePage } from '../src/ExplorePage'
import { defaultReaderSettings } from '../src/readerSettings'
import { api } from '../src/api'

test('AppHeader - renders Explore navigation button', () => {
  const html = renderToStaticMarkup(
    React.createElement(AppHeader, {
      page: 'explore',
      settings: defaultReaderSettings,
      onSettingsChange: () => {},
      onNavigate: () => {},
      onLogout: () => {},
    })
  )

  // 导航项中应包含“发现”
  assert.ok(html.includes('发现') || html.includes('explore'))
  // active 状态
  assert.ok(html.includes('class="active"') && (html.includes('发现') || html.includes('explore')))
})

test('ExplorePage - static rendering contains sidebar and content structures', () => {
  const html = renderToStaticMarkup(
    React.createElement(ExplorePage, {
      onOpen: () => {},
    })
  )

  assert.ok(html.includes('explore-page'))
  assert.ok(html.includes('explore-sidebar'))
  assert.ok(html.includes('explore-content'))
  assert.ok(html.includes('explore-source-filter'))
})

test('api client - exports exploreSources, exploreCategories, exploreBooks methods', () => {
  assert.equal(typeof api.exploreSources, 'function')
  assert.equal(typeof api.exploreCategories, 'function')
  assert.equal(typeof api.exploreBooks, 'function')
})
