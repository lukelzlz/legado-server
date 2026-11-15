import React from 'react'
import { useTranslation } from 'react-i18next'
import { Icon } from './icons'

/**
 * 「设置」首页：把原来平铺在顶栏的四个管理入口（书源 / 订阅 / 规则 / 文件）收进来。
 *
 * 只做导航聚合，**不复用/重写各页面本身**：点进去仍是原来的 `SourcesPage` /
 * `SubscriptionPage` / `ReplaceRulesPage` / `WebDavSettingsPage`，
 * 避免同一个功能出现两套实现（本项目的历史教训：读侧与触发侧两份实现必然不同步）。
 */
export type SettingsSection = 'sources' | 'subscriptions' | 'rules' | 'webdav'

type Props = {
  onOpen: (section: SettingsSection) => void
}

export function SettingsPage({ onOpen }: Props) {
  const { t } = useTranslation()
  const entries: Array<{ section: SettingsSection; icon: 'book' | 'list' | 'sliders' | 'folder'; title: string; desc: string }> = [
    {
      section: 'sources',
      icon: 'book',
      title: t('settings.sources', '书源'),
      desc: t('settings.sourcesDesc', '导入/编辑书源，登录与调试，分组管理'),
    },
    {
      section: 'subscriptions',
      icon: 'list',
      title: t('settings.subscriptions', '订阅'),
      desc: t('settings.subscriptionsDesc', '订阅源与自动更新策略'),
    },
    {
      section: 'rules',
      icon: 'sliders',
      title: t('settings.rules', '规则'),
      desc: t('settings.rulesDesc', '替换净化规则：正文清洗与反义词对调'),
    },
    {
      section: 'webdav',
      icon: 'folder',
      title: t('settings.files', '文件'),
      desc: t('settings.filesDesc', 'WebDAV 存储、备份导入导出、本地书籍与自动缓存'),
    },
  ]

  return (
    <section className="settings-page">
      <header className="settings-page-header">
        <span className="section-kicker">{t('settings.kicker', '设置')}</span>
        <h1>{t('settings.title', '设置')}</h1>
        <p>{t('settings.desc', '书源、订阅、规则、文件都在这里；点哪一项进哪一项。')}</p>
      </header>
      <div className="settings-entries">
        {entries.map(entry => (
          <button
            key={entry.section}
            type="button"
            className="settings-entry"
            onClick={() => onOpen(entry.section)}
          >
            <span className="settings-entry-icon">
              <Icon name={entry.icon} />
            </span>
            <span className="settings-entry-body">
              <strong>{entry.title}</strong>
              <small>{entry.desc}</small>
            </span>
            <Icon name="arrowRight" />
          </button>
        ))}
      </div>
    </section>
  )
}
