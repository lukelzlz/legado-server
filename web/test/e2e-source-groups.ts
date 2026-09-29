/**
 * 端到端 UI 取证：书源分组的两处落点（E2E，需先有一个在跑的实例）。
 *
 * 与 `e2e-*.ts` 家族一致：**不进入 `run-all.ts`**，只在需要真机取证时手动执行。
 *
 * ```sh
 * # 1) 起一个本地实例（示例；用你自己的数据目录）
 * LEGADO_DATA_DIR=./.verify-data LEGADO_PORT=18080 ADMIN_PASSWORD=admin123 \
 *   LEGADO_SECURE_COOKIES=false ./server/build/install/legado-server/bin/legado-server
 * # 2) 跑取证（CHROME_BIN 指向本机 Chrome/Edge）
 * CHROME_BIN="/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe" npx tsx web/test/e2e-source-groups.ts
 * ```
 *
 * 验证的需求（PROPOSAL-021）：
 * 1. **书库页搜索栏下面**有「搜索范围」选项卡，且**第一项恒为「全部书源」并默认选中**；
 * 2. 点某个分组后，该分组成为**唯一**激活项，「全部书源」仍在但取消激活；点回去能复位；
 * 3. 分组选项卡上的数字来自服务端聚合的**已启用**书源数；
 * 4. 分组的新建/改名/删除入口在**书源**页（选中具体分组后才出现）。
 */
import puppeteer from 'puppeteer-core'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

const CHROME_PATH = process.env.CHROME_BIN || process.env.PUPPETEER_EXECUTABLE_PATH || '/usr/bin/google-chrome'
const BASE_URL = process.env.LEGADO_BASE_URL || 'http://127.0.0.1:18080'
const ADMIN_PASSWORD = process.env.LEGADO_PASSWORD || 'admin123'
const SHOT_DIR = process.env.LEGADO_SHOT_DIR || os.tmpdir()

const results: string[] = []
const check = (label: string, ok: boolean, extra = ''): boolean => {
  results.push(`${ok ? '✔' : '✖'} ${label}${extra ? ` — ${extra}` : ''}`)
  return ok
}

const userDataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'legado-e2e-source-groups-'))
const browser = await puppeteer.launch({
  executablePath: CHROME_PATH,
  headless: true,
  userDataDir,
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
})

try {
  const page = await browser.newPage()
  await page.setViewport({ width: 1440, height: 960 })
  await page.goto(BASE_URL, { waitUntil: 'networkidle2' })

  // 登录：用回车提交。PWA 安装横幅的「立即安装」按钮同样带 .primary-button，
  // 直接 click('.primary-button') 会点到横幅上，表现为「点了登录却还停在登录页」（实测踩过）。
  if (await page.$('input[type="password"]')) {
    await page.type('input[type="password"]', ADMIN_PASSWORD)
    await page.keyboard.press('Enter')
    await page.waitForSelector('.library-scope-bar', { timeout: 20_000 })
  } else {
    await page.waitForSelector('.library-scope-bar', { timeout: 20_000 })
  }

  // ---- 书库页 ----
  const tabs = await page.$$eval('.library-scope-bar .scope-tab', els => els.map(el => ({
    text: (el.textContent || '').trim(),
    active: el.classList.contains('active'),
    selected: el.getAttribute('aria-selected'),
  })))
  const labels = tabs.map(t => t.text)
  check('搜索栏下方存在搜索范围选项卡', labels.length > 0, labels.join(' | '))
  check('第一项是「全部书源」', labels[0] === '全部书源', labels[0])
  check('默认选中「全部书源」', tabs[0].active && tabs[0].selected === 'true')
  check('存在「未分组」范围', labels.includes('未分组'))
  check('分组选项卡带已启用源数', labels.slice(1).some(l => /\d/.test(l)))

  const belowSearchBox = await page.evaluate(() => {
    const form = document.querySelector('.library-hero form')
    const bar = document.querySelector('.library-scope-bar')
    if (!form || !bar) return false
    return form.compareDocumentPosition(bar) === Node.DOCUMENT_POSITION_FOLLOWING
      && bar.getBoundingClientRect().top >= form.getBoundingClientRect().bottom
  })
  check('选项卡位于搜索栏正下方', belowSearchBox)
  await page.screenshot({ path: path.join(SHOT_DIR, 'e2e-source-groups-library-all.png') })

  const groupLabels = labels.slice(1).filter(label => label !== '未分组')
  if (groupLabels.length > 0) {
    const groupIndex = 2 // 第一项是「全部书源」，第二项即第一个分组
    await page.click(`.library-scope-bar .scope-tab:nth-of-type(${groupIndex})`)
    await new Promise(resolve => setTimeout(resolve, 250))
    const afterClick = await page.$$eval('.library-scope-bar .scope-tab', els => els.map(el => ({
      text: (el.textContent || '').trim(),
      active: el.classList.contains('active'),
    })))
    check('点击分组后该分组成为唯一激活项', afterClick.filter(t => t.active).length === 1 && afterClick[1].active)
    check('「全部书源」仍在且已取消激活', afterClick[0].text === '全部书源' && !afterClick[0].active)
    await page.screenshot({ path: path.join(SHOT_DIR, 'e2e-source-groups-library-group.png') })

    await page.click('.library-scope-bar .scope-tab:nth-of-type(1)')
    await new Promise(resolve => setTimeout(resolve, 200))
    const backToAll = await page.$$eval('.library-scope-bar .scope-tab', els => els.map(el => el.classList.contains('active')))
    check('点回「全部书源」后范围复位', backToAll[0] === true && backToAll.filter(Boolean).length === 1)
  } else {
    check('实例里存在书源分组（需先导入带分组的书源/备份）', false)
  }

  // ---- 书源页：分组管理是独立按钮，不再藏在批量管理里 ----
  await page.goto(`${BASE_URL}#sources`, { waitUntil: 'networkidle2' })
  await page.waitForSelector('.source-group-select', { timeout: 20_000 })
  const options = await page.$$eval('.source-group-select option', els => els.map(el => (el.textContent || '').trim()))
  check('书源页有分组筛选框', options.length > 1, options.join(' | '))

  const managerButton = await page.$('.group-manager-btn')
  check('「分组管理」是独立按钮', managerButton !== null)
  if (managerButton) {
    await managerButton.click()
    await page.waitForSelector('.source-group-manager', { timeout: 20_000 })
    const panel = await page.evaluate(() => {
      const root = document.querySelector('.source-group-manager')
      if (!root) return null
      return {
        text: (root.textContent || '').slice(0, 400),
        rename: root.querySelector('[aria-label="重命名"]') !== null,
        remove: root.querySelector('[aria-label="删除"]') !== null,
        add: Array.from(root.querySelectorAll('button')).some(b => (b.textContent || '').trim() === '加入分组'),
        ungroup: Array.from(root.querySelectorAll('button')).some(b => (b.textContent || '').trim() === '移出分组'),
        checkboxes: root.querySelectorAll('.group-picker-list input[type="checkbox"]').length,
      }
    })
    check('面板可打开且列出分组', panel !== null && panel.text.includes(groupLabels[0].replace(/\s*\d+$/, '')))
    check('面板提供重命名 / 删除', Boolean(panel?.rename) && Boolean(panel?.remove))
    check('面板提供 加入分组 / 移出分组', Boolean(panel?.add) && Boolean(panel?.ungroup))
    check('面板可批量勾选书源', (panel?.checkboxes ?? 0) > 0, `checkbox=${panel?.checkboxes ?? 0}`)
    await page.screenshot({ path: path.join(SHOT_DIR, 'e2e-source-groups-manager.png') })
    // 关掉面板：不关的话遮罩会吃掉后面的点击（点「批量管理」会被 backdrop 当作关闭面板）
    await page.click('.source-group-manager .close-btn')
    await page.waitForFunction(() => document.querySelector('.source-group-manager') === null, { timeout: 20_000 })
  }

  // 批量管理里不应再有分组动作（分组管理已独立）
  await page.click('.batch-mode-btn')
  await page.waitForSelector('.source-batch-bar', { timeout: 20_000 })
  const batchActions = await page.$$eval('.source-batch-bar button', els => els.map(el => (el.textContent || '').trim()))
  check('批量管理不再包含分组动作', !batchActions.includes('修改分组') && !batchActions.includes('移动到分组'), batchActions.join(','))
} finally {
  await browser.close()
  await fs.rm(userDataDir, { recursive: true, force: true }).catch(() => {})
}

console.log(results.join('\n'))
process.exitCode = results.some(line => line.startsWith('✖')) ? 1 : 0
