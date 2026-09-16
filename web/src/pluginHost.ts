import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api } from './api'
import { normalizePluginModule } from './pluginModule'
import {
  createPluginSdk,
  pluginPageKey,
  resolvePluginTarget,
  type PluginManifestSummary,
  type PluginMenuItem,
  type PluginNavItem,
  type PluginPageRegistration,
  type PluginSdkBridge,
  type PluginWebModule,
} from './pluginSdk'
import { toast } from './Toast'

/**
 * 前端插件宿主。
 *
 * 职责：拉取 `GET /api/plugins`、按需动态加载插件的 `web.js`、注入 SDK 并执行 `activate(sdk)`，
 * 收集插件注册的导航项/菜单项/页面，并维护每个插件的激活错误。
 * 单个插件加载或执行失败只影响它自己：错误记入 `loadErrors`，其余插件与宿主页面不受影响。
 */

export type HostNavItem = PluginNavItem & { pluginId: string; pluginName: string; target: string }
export type HostMenuItem = PluginMenuItem & { pluginId: string; pluginName: string }
export type HostPluginPage = PluginPageRegistration & { pluginId: string; pluginName: string; key: string }

/** 单个插件的注册结果 */
type PluginRegistration = {
  pluginId: string
  pluginName: string
  navItems: PluginNavItem[]
  menuItems: PluginMenuItem[]
  pages: PluginPageRegistration[]
}

export type PluginHost = {
  plugins: PluginManifestSummary[]
  navItems: HostNavItem[]
  menuItems: HostMenuItem[]
  pages: HostPluginPage[]
  loadErrors: Record<string, string>
  loading: boolean
  reload: () => Promise<void>
  enable: (id: string) => Promise<void>
  disable: (id: string) => Promise<void>
}

const messageOf = (error: unknown, fallback: string) =>
  error instanceof Error && error.message ? error.message : fallback

const emptyRegistration = (manifest: PluginManifestSummary): PluginRegistration => ({
  pluginId: manifest.id,
  pluginName: manifest.name,
  navItems: [],
  menuItems: [],
  pages: [],
})

/** 动态加载插件前端模块：URL 带版本号防缓存，且必须绕过 Vite 的静态分析 */
async function importPluginModule(manifest: PluginManifestSummary): Promise<PluginWebModule> {
  const url = `/api/plugins/${encodeURIComponent(manifest.id)}/web.js?v=${encodeURIComponent(manifest.version)}`
  return normalizePluginModule(await import(/* @vite-ignore */ url))
}

export function findPluginPage(pages: HostPluginPage[], key: string): HostPluginPage | undefined {
  return pages.find(page => page.key === key)
}

export function usePluginHost(authenticated: boolean): PluginHost {
  const [plugins, setPlugins] = useState<PluginManifestSummary[]>([])
  const [registrations, setRegistrations] = useState<Record<string, PluginRegistration>>({})
  const [loadErrors, setLoadErrors] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(false)
  const modulesRef = useRef(new Map<string, PluginWebModule>())
  const registrationsRef = useRef<Record<string, PluginRegistration>>({})
  const runIdRef = useRef(0)

  const commit = useCallback((registration: PluginRegistration) => {
    registrationsRef.current = { ...registrationsRef.current, [registration.pluginId]: registration }
    setRegistrations(registrationsRef.current)
  }, [])

  const disposeModule = useCallback((pluginId: string) => {
    const module = modulesRef.current.get(pluginId)
    if (!module) return
    modulesRef.current.delete(pluginId)
    try {
      module.deactivate?.()
    } catch (error) {
      console.error(`[plugins] ${pluginId} deactivate 执行失败`, error)
    }
  }, [])

  const dropRegistration = useCallback((pluginId: string) => {
    disposeModule(pluginId)
    const next = { ...registrationsRef.current }
    delete next[pluginId]
    registrationsRef.current = next
    setRegistrations(next)
  }, [disposeModule])

  const bridgeFor = useCallback((registration: PluginRegistration): PluginSdkBridge => ({
    navigate: page => { location.hash = `#${page}` },
    registerNavItem: item => { registration.navItems.push(item) },
    registerMenuItem: item => { registration.menuItems.push(item) },
    registerPage: page => { registration.pages.push(page) },
  }), [])

  /** 激活单个插件；失败时丢弃半成品注册项并记录错误 */
  const activateOne = useCallback(async (manifest: PluginManifestSummary) => {
    disposeModule(manifest.id)
    const registration = emptyRegistration(manifest)
    if (!manifest.enabled || !manifest.hasWeb) {
      commit(registration)
      return
    }
    try {
      const module = await importPluginModule(manifest)
      await module.activate?.(createPluginSdk(manifest, bridgeFor(registration)))
      modulesRef.current.set(manifest.id, module)
      commit(registration)
    } catch (error) {
      const message = messageOf(error, `插件 ${manifest.name} 激活失败`)
      commit(emptyRegistration(manifest))
      setLoadErrors(current => ({ ...current, [manifest.id]: message }))
    }
  }, [bridgeFor, commit, disposeModule])

  /** 全量加载：先卸载全部插件，再按列表顺序逐个激活 */
  const loadAll = useCallback(async () => {
    const runId = ++runIdRef.current
    registrationsRef.current = {}
    setRegistrations({})
    modulesRef.current.forEach(module => {
      try {
        module.deactivate?.()
      } catch (error) {
        console.error('[plugins] deactivate 执行失败', error)
      }
    })
    modulesRef.current.clear()

    if (!authenticated) {
      setPlugins([])
      setLoadErrors({})
      setLoading(false)
      return
    }

    setLoading(true)
    let list: PluginManifestSummary[]
    try {
      list = await api.plugins()
    } catch (error) {
      if (runIdRef.current !== runId) return
      setPlugins([])
      setLoadErrors({ '*': messageOf(error, '无法载入插件列表') })
      setLoading(false)
      return
    }
    if (runIdRef.current !== runId) return

    setPlugins(list)
    const errors: Record<string, string> = {}
    for (const manifest of list) {
      if (runIdRef.current !== runId) return
      if (!manifest.enabled || !manifest.hasWeb) {
        commit(emptyRegistration(manifest))
        continue
      }
      const registration = emptyRegistration(manifest)
      try {
        const module = await importPluginModule(manifest)
        if (runIdRef.current !== runId) return
        await module.activate?.(createPluginSdk(manifest, bridgeFor(registration)))
        if (runIdRef.current !== runId) return
        modulesRef.current.set(manifest.id, module)
        commit(registration)
      } catch (error) {
        errors[manifest.id] = messageOf(error, `插件 ${manifest.name} 激活失败`)
        setLoadErrors({ ...errors })
        commit(emptyRegistration(manifest))
      }
    }
    if (runIdRef.current !== runId) return
    setLoadErrors(errors)
    setLoading(false)
  }, [authenticated, bridgeFor, commit])

  useEffect(() => {
    void loadAll()
  }, [loadAll])

  // 卸载时注销全部插件，避免残留的定时器/监听影响宿主
  useEffect(() => () => {
    runIdRef.current++
    modulesRef.current.forEach(module => {
      try {
        module.deactivate?.()
      } catch (error) {
        console.error('[plugins] deactivate 执行失败', error)
      }
    })
    modulesRef.current.clear()
  }, [])

  const reload = useCallback(async () => {
    try {
      const result = await api.reloadPlugins()
      toast.success(`已重新加载 ${result.reloaded} 个插件`)
    } catch (error) {
      toast.error(messageOf(error, '重载插件失败'))
    }
    await loadAll()
  }, [loadAll])

  const enable = useCallback(async (id: string) => {
    try {
      const updated = await api.enablePlugin(id)
      setPlugins(current => current.map(item => item.id === id ? updated : item))
      setLoadErrors(current => {
        const next = { ...current }
        delete next[id]
        return next
      })
      await activateOne(updated)
    } catch (error) {
      toast.error(messageOf(error, '启用插件失败'))
    }
  }, [activateOne])

  const disable = useCallback(async (id: string) => {
    try {
      const updated = await api.disablePlugin(id)
      setPlugins(current => current.map(item => item.id === id ? updated : item))
      dropRegistration(id)
    } catch (error) {
      toast.error(messageOf(error, '停用插件失败'))
    }
  }, [dropRegistration])

  const navItems = useMemo<HostNavItem[]>(
    () => Object.values(registrations).flatMap(registration =>
      registration.navItems.map(item => ({
        ...item,
        pluginId: registration.pluginId,
        pluginName: registration.pluginName,
        target: resolvePluginTarget(registration.pluginId, item),
      }))
    ),
    [registrations]
  )

  const menuItems = useMemo<HostMenuItem[]>(
    () => Object.values(registrations).flatMap(registration =>
      registration.menuItems.map(item => ({
        ...item,
        pluginId: registration.pluginId,
        pluginName: registration.pluginName,
      }))
    ),
    [registrations]
  )

  const pages = useMemo<HostPluginPage[]>(
    () => Object.values(registrations).flatMap(registration =>
      registration.pages.map(page => ({
        ...page,
        pluginId: registration.pluginId,
        pluginName: registration.pluginName,
        key: pluginPageKey(registration.pluginId, page.id),
      }))
    ),
    [registrations]
  )

  return { plugins, navItems, menuItems, pages, loadErrors, loading, reload, enable, disable }
}
