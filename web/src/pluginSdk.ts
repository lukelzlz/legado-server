import * as React from 'react'
import { useCallback, useContext, useEffect, useMemo, useReducer, useRef, useState, type ReactNode } from 'react'
import { csrfHeaders, request } from './api'
import { pluginUi } from './PluginUi'
import { toast } from './Toast'

/**
 * 插件 SDK 的类型定义与构造逻辑。
 *
 * 宿主在激活插件时把 `PluginSdk` 注入 `activate(sdk)`，插件全部能力都从这个对象获得：
 * 它拿不到宿主内部模块，也无法绕过 `api`/`settings`/`storage` 直接访问其它后端端点。
 */

export type PluginSettingType = 'text' | 'password' | 'number' | 'boolean' | 'select' | 'textarea'

export type PluginSettingOption = { label: string; value: string }

export type PluginSettingField = {
  key: string
  label: string
  type: PluginSettingType
  default?: unknown
  options?: PluginSettingOption[]
  hint?: string
}

/** 插件提供的服务端运行时：`js`（server.js）、`jar`（JVM 插件）、两者兼有；纯前端插件为 `none` */
export type PluginRuntime = 'js' | 'jar' | 'js+jar' | 'none'

/** `GET /api/plugins` 返回的单个插件摘要 */
export type PluginManifestSummary = {
  id: string
  name: string
  version: string
  description?: string
  author?: string
  apiVersion: number
  enabled: boolean
  loaded: boolean
  runtime: PluginRuntime
  hasServer: boolean
  hasWeb: boolean
  permissions: string[]
  settingsSchema: PluginSettingField[]
  error?: string
  directory: string
}

/** 插件页面渲染时收到的 props */
export type PluginPageProps = {
  pageId: string
  pluginId: string
  navigate: (page: string) => void
}

export type PluginNavItem = {
  id: string
  label: string
  icon?: string
  title?: string
  page?: string
}

export type PluginMenuItem = {
  id: string
  label: string
  icon?: string
  onClick: () => void
}

export type PluginPageRegistration = {
  id: string
  title: string
  icon?: string
  render: (props: PluginPageProps) => ReactNode
}

export type PluginSdkApi = {
  get<T>(path: string): Promise<T>
  post<T>(path: string, body?: unknown): Promise<T>
  put<T>(path: string, body?: unknown): Promise<T>
  del<T>(path: string): Promise<T>
  /** 请求插件自己的服务端路由 `/api/plugins/<id>/r/<subPath>` */
  plugin<T>(subPath: string, init?: RequestInit): Promise<T>
  pluginRaw(subPath: string, init?: RequestInit): Promise<Response>
}

export type PluginStorage = {
  get<T = unknown>(key: string): T | null
  set(key: string, value: unknown): void
  remove(key: string): void
}

export type PluginSettingsApi = {
  get<T = Record<string, unknown>>(): Promise<T>
  update<T = Record<string, unknown>>(values: Record<string, unknown>): Promise<T>
}

export type PluginUi = typeof pluginUi

export type PluginSdk = {
  plugin: { id: string; name: string; version: string; description?: string; author?: string; directory: string }
  /** 宿主 React 运行时，插件无需自带 React */
  React: typeof import('react')
  hooks: {
    useState: typeof useState
    useEffect: typeof useEffect
    useMemo: typeof useMemo
    useCallback: typeof useCallback
    useRef: typeof useRef
    useContext: typeof useContext
    useReducer: typeof useReducer
  }
  ui: PluginUi
  api: PluginSdkApi
  toast: { info(message: string): void; success(message: string): void; warning(message: string): void; error(message: string): void }
  storage: PluginStorage
  settings: PluginSettingsApi
  navigate(page: string): void
  registerNavItem(item: PluginNavItem): void
  registerMenuItem(item: PluginMenuItem): void
  registerPage(page: PluginPageRegistration): void
  pages: { library: 'library'; shelf: 'shelf'; sources: 'sources'; subscriptions: 'subscriptions'; plugins: 'plugins' }
}

/** 插件 web.js 的模块形态 */
export type PluginWebModule = {
  activate?: (sdk: PluginSdk) => unknown
  deactivate?: () => void
}

/** 宿主内置页面标识，插件可直接用 `sdk.pages.xxx` 引用 */
export const PLUGIN_PAGE_IDS = {
  library: 'library',
  shelf: 'shelf',
  sources: 'sources',
  subscriptions: 'subscriptions',
  plugins: 'plugins',
} as const

const BUILT_IN_PAGES: readonly string[] = Object.values(PLUGIN_PAGE_IDS)

/** 插件页面的统一标识：`plugin:<pluginId>:<pageId>` */
export const pluginPageKey = (pluginId: string, pageId: string) => `plugin:${pluginId}:${pageId}`

/** 解析 `plugin:<pluginId>:<pageId>`，非插件页面返回 null */
export function parsePluginPageKey(value: string): { pluginId: string; pageId: string } | null {
  if (!value.startsWith('plugin:')) return null
  const rest = value.slice('plugin:'.length)
  const separator = rest.indexOf(':')
  if (separator <= 0 || separator >= rest.length - 1) return null
  return { pluginId: rest.slice(0, separator), pageId: rest.slice(separator + 1) }
}

/**
 * 解析导航目标：
 * - 未指定 `page` 时跳到该插件中同 id 的页面
 * - `plugin:` 前缀与内置页面名按原样使用
 * - 其余视为本插件的页面 id
 */
export function resolvePluginTarget(pluginId: string, item: { id: string; page?: string }): string {
  const target = item.page?.trim()
  if (!target) return pluginPageKey(pluginId, item.id)
  if (target.startsWith('plugin:') || BUILT_IN_PAGES.includes(target)) return target
  return pluginPageKey(pluginId, target)
}

/** 宿主注入回插件的回调，用于导航与注册 */
export type PluginSdkBridge = {
  navigate: (page: string) => void
  registerNavItem: (item: PluginNavItem) => void
  registerMenuItem: (item: PluginMenuItem) => void
  registerPage: (page: PluginPageRegistration) => void
}

const isNonEmptyString = (value: unknown): value is string => typeof value === 'string' && value.trim().length > 0

/** 摘要里的设置项列表；服务端返回 null/缺失时退化为空数组，避免管理页崩溃 */
export const settingsSchemaOf = (plugin: PluginManifestSummary): PluginSettingField[] =>
  Array.isArray(plugin.settingsSchema) ? plugin.settingsSchema : []

export function createPluginSdk(manifest: PluginManifestSummary, bridge: PluginSdkBridge): PluginSdk {
  const pluginId = manifest.id
  const storagePrefix = `legado-plugin:${pluginId}:`
  const settingsPath = `/api/plugins/${encodeURIComponent(pluginId)}/settings`
  const routeBase = `/api/plugins/${encodeURIComponent(pluginId)}/r`

  const routeUrl = (subPath: string) => {
    const normalized = (subPath ?? '').replace(/^\/+/, '')
    return normalized ? `${routeBase}/${normalized}` : routeBase
  }

  const jsonInit = (method: string, body?: unknown): RequestInit =>
    body === undefined ? { method } : { method, body: JSON.stringify(body) }

  const storage: PluginStorage = {
    get: <T = unknown>(key: string): T | null => {
      try {
        const raw = localStorage.getItem(storagePrefix + key)
        return raw === null ? null : JSON.parse(raw) as T
      } catch {
        toast.warning(`插件 ${pluginId} 无法读取本地数据：${key}`)
        return null
      }
    },
    set: (key: string, value: unknown) => {
      try {
        localStorage.setItem(storagePrefix + key, JSON.stringify(value))
      } catch {
        toast.error(`插件 ${pluginId} 无法写入本地数据：${key}`)
      }
    },
    remove: (key: string) => {
      try {
        localStorage.removeItem(storagePrefix + key)
      } catch {
        // 存储不可用时静默忽略，插件不应因此中断
      }
    },
  }

  return {
    plugin: {
      id: pluginId,
      name: manifest.name,
      version: manifest.version,
      description: manifest.description,
      author: manifest.author,
      directory: manifest.directory,
    },
    React,
    hooks: { useState, useEffect, useMemo, useCallback, useRef, useContext, useReducer },
    ui: pluginUi,
    api: {
      get: <T,>(path: string) => request<T>(path),
      post: <T,>(path: string, body?: unknown) => request<T>(path, jsonInit('POST', body)),
      put: <T,>(path: string, body?: unknown) => request<T>(path, jsonInit('PUT', body)),
      del: <T,>(path: string) => request<T>(path, { method: 'DELETE' }),
      plugin: <T,>(subPath: string, init?: RequestInit) => request<T>(routeUrl(subPath), init),
      pluginRaw: (subPath: string, init?: RequestInit) =>
        fetch(routeUrl(subPath), { ...init, headers: csrfHeaders(init ?? {}), credentials: 'same-origin' }),
    },
    toast: {
      info: message => toast.info(message),
      success: message => toast.success(message),
      warning: message => toast.warning(message),
      error: message => toast.error(message),
    },
    storage,
    settings: {
      get: <T = Record<string, unknown>,>() => request<T>(settingsPath),
      update: <T = Record<string, unknown>,>(values: Record<string, unknown>) =>
        request<T>(settingsPath, { method: 'PUT', body: JSON.stringify(values) }),
    },
    navigate: page => bridge.navigate(page.startsWith('plugin:') || BUILT_IN_PAGES.includes(page) ? page : pluginPageKey(pluginId, page)),
    registerNavItem: item => {
      if (!item || !isNonEmptyString(item.id) || !isNonEmptyString(item.label)) return
      bridge.registerNavItem({
        id: item.id,
        label: item.label,
        icon: item.icon,
        title: item.title,
        page: item.page,
      })
    },
    registerMenuItem: item => {
      if (!item || !isNonEmptyString(item.id) || !isNonEmptyString(item.label) || typeof item.onClick !== 'function') return
      bridge.registerMenuItem({ id: item.id, label: item.label, icon: item.icon, onClick: item.onClick })
    },
    registerPage: page => {
      if (!page || !isNonEmptyString(page.id) || typeof page.render !== 'function') return
      bridge.registerPage({
        id: page.id,
        title: isNonEmptyString(page.title) ? page.title : page.id,
        icon: page.icon,
        render: page.render,
      })
    },
    pages: PLUGIN_PAGE_IDS,
  }
}
