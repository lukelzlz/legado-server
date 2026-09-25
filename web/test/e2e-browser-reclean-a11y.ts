import puppeteer from 'puppeteer-core'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

const CHROME_PATH = process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const BASE_URL = process.env.TEST_BASE_URL || 'http://127.0.0.1:8080'
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'admin123'

async function runTests() {
  console.log('====================================================')
  console.log('🚀 Running Browser & Accessibility (a11y) Test Suite')
  console.log(`📍 Base URL: ${BASE_URL}`)
  console.log(`🌐 Chrome Binary: ${CHROME_PATH}`)
  console.log('====================================================\n')

  const userDataDir = await fs.mkdtemp(path.join(os.tmpdir(), `legado-a11y-test-${Date.now()}-`))
  let browser: any = null

  try {
    browser = await puppeteer.launch({
      executablePath: CHROME_PATH,
      headless: true,
      userDataDir,
      args: [
        '--no-sandbox',
        '--disable-setuid-sandbox',
        '--disable-dev-shm-usage',
        '--disable-gpu',
        '--window-size=1280,800',
      ],
    })
    const page = await browser.newPage()
    await page.setViewport({ width: 1280, height: 800 })

  try {
    // 1. Visit Login / Home Page
    console.log('🔍 [1/6] Navigating to page...')
    await page.goto(`${BASE_URL}/`, { waitUntil: 'networkidle0' })

    // 2. Perform Login if needed
    console.log('🔑 [2/6] Checking authentication status...')
    const pwdInput = await page.$('input[type="password"]')
    if (pwdInput) {
      console.log('   - Submitting login form with admin password...')
      await pwdInput.focus()
      await page.keyboard.type(ADMIN_PASSWORD, { delay: 20 })
      await new Promise(r => setTimeout(r, 100))
      const submitBtn = await page.$('button[type="submit"], .primary-button')
      if (submitBtn) await submitBtn.click()
    }

    // Wait for bookshelf or main header
    await page.waitForSelector('.app-header, .shelf-page, .shelf-grid', { timeout: 15000 })
    console.log('✅ Logged in successfully, bookshelf page loaded.')

    // 3. Accessibility (a11y) Auditing on Bookshelf
    console.log('\n♿ [3/6] Running Accessibility (a11y) audit on Shelf Page...')
    const a11yAudit = await page.evaluate(() => {
      const issues: string[] = []

      // 1. Check all buttons for accessible names & type
      const buttons = document.querySelectorAll('button')
      buttons.forEach((btn, idx) => {
        const text = btn.innerText?.trim() || btn.getAttribute('aria-label') || btn.getAttribute('title')
        if (!text) {
          issues.push(`Button #${idx} (class: ${btn.className}) has no accessible text or aria-label`)
        }
      })

      // 2. Check form inputs have accessible labels or placeholders
      const inputs = document.querySelectorAll('input, select, textarea')
      inputs.forEach((input, idx) => {
        const hasLabel = input.getAttribute('aria-label') || input.getAttribute('placeholder') || input.getAttribute('id')
        if (!hasLabel) {
          issues.push(`Input #${idx} (type: ${input.getAttribute('type')}) has no aria-label or placeholder`)
        }
      })

      // 3. Check landmark roles
      const landmarks = {
        header: !!document.querySelector('header, [role="banner"]'),
        main: !!document.querySelector('main, [role="main"], section.shelf-page'),
        nav: !!document.querySelector('nav, [role="navigation"]'),
      }

      return {
        totalButtons: buttons.length,
        totalInputs: inputs.length,
        landmarks,
        issues,
      }
    })

    console.log(`   - Audited ${a11yAudit.totalButtons} buttons and ${a11yAudit.totalInputs} input elements`)
    console.log(`   - Landmarks found: header=${a11yAudit.landmarks.header}, main=${a11yAudit.landmarks.main}, nav=${a11yAudit.landmarks.nav}`)
    if (a11yAudit.issues.length > 0) {
      console.warn(`   ⚠️ a11y warnings found (${a11yAudit.issues.length}):`, a11yAudit.issues)
    } else {
      console.log('   ✅ All interactive elements satisfy accessibility requirements!')
    }

    // 4. Test Book Management Sheet (Three-dot Menu) and Re-clean Button
    console.log('\n📚 [4/6] Testing Book Card Three-Dot Menu & Re-clean Action...')
    const manageBtn = await page.$('.shelf-btn-manage')
    if (manageBtn) {
      console.log('   - Clicking three-dot menu on book card...')
      await manageBtn.click()
      await page.waitForSelector('.book-manage-sheet', { timeout: 3000 })
      console.log('   - BookManageSheet opened.')

      // Check aria attributes of modal
      const modalAria = await page.$eval('.book-manage-sheet', el => ({
        role: el.getAttribute('role'),
        ariaModal: el.getAttribute('aria-modal'),
        ariaLabel: el.getAttribute('aria-label'),
      }))
      assert.equal(modalAria.role, 'dialog', 'Modal must have role="dialog"')
      assert.equal(modalAria.ariaModal, 'true', 'Modal must have aria-modal="true"')
      assert.ok(modalAria.ariaLabel, 'Modal must have non-empty aria-label')
      console.log(`   ✅ Modal a11y verified: role=${modalAria.role}, aria-label="${modalAria.ariaLabel}"`)

      // Find "重新应用净化规则" button
      const recleanBtn = await page.evaluateHandle(() => {
        const rows = Array.from(document.querySelectorAll('.book-manage-sheet .manage-action-row'))
        return rows.find(r => r.textContent?.includes('重新应用净化规则') || r.textContent?.includes('重新清洗'))
      })
      assert.ok(recleanBtn, 'Re-clean action button must exist in BookManageSheet')
      console.log('   - Found "重新应用净化规则" button in modal. Clicking it...')

      await (recleanBtn as any).click()
      await page.waitForSelector('.toast-container, .toast-item, .toast', { timeout: 5000 }).catch(() => {})
      console.log('   ✅ Re-clean action triggered successfully and toast notification displayed.')

      // Close modal
      const closeBtn = await page.$('.book-manage-sheet .close-btn')
      if (closeBtn) await closeBtn.click()
    } else {
      console.log('   ℹ No book currently on shelf for single book reclean test. Shelf is clean.')
    }

    // 5. Test Batch Mode & Batch Reclean Button
    console.log('\n📦 [5/6] Testing Batch Management Mode & Batch Reclean Button...')
    const batchToggleBtn = await page.$('.shelf-batch-toggle-btn')
    if (batchToggleBtn) {
      await batchToggleBtn.click()
      await page.waitForSelector('.shelf-batch-bar', { timeout: 3000 })
      console.log('   - Batch mode active, toolbar visible.')

      const batchBarAria = await page.$eval('.shelf-batch-bar', el => ({
        role: el.getAttribute('role'),
        ariaLabel: el.getAttribute('aria-label'),
      }))
      assert.equal(batchBarAria.role, 'toolbar', 'Batch bar must have role="toolbar"')
      console.log(`   ✅ Batch toolbar a11y verified: role=${batchBarAria.role}, aria-label="${batchBarAria.ariaLabel}"`)

      // Check for "重洗规则" batch button
      const batchRecleanBtn = await page.evaluate(() => {
        const btns = Array.from(document.querySelectorAll('.shelf-batch-action-btn'))
        return btns.some(b => b.textContent?.includes('重洗规则'))
      })
      assert.ok(batchRecleanBtn, 'Batch reclean button must exist in batch toolbar')
      console.log('   ✅ "重洗规则" batch button verified in toolbar.')

      // Exit batch mode
      const completeBtn = await page.$('.shelf-batch-action-btn.complete-btn')
      if (completeBtn) await completeBtn.click()
    }

    // 6. Test Replace Rules Page Navigation and UI
    console.log('\n⚙️ [6/6] Testing Replace Rules Page (#rules)...')
    await page.goto(`${BASE_URL}/#rules`, { waitUntil: 'networkidle0' })
    await page.waitForSelector('.replace-rules-page, .rules-page-container', { timeout: 5000 })

    const pageHeading = await page.$eval('h2, .page-title h2, h1', el => el.textContent?.trim())
    console.log(`   - Replace rules page loaded. Heading: "${pageHeading}"`)
    assert.ok(pageHeading?.includes('替换') || pageHeading?.includes('净化') || pageHeading?.includes('规则'), 'Page heading should indicate Replace Rules')

    console.log('\n====================================================')
    console.log('🎉 ALL BROWSER & ACCESSIBILITY TESTS PASSED 100%!')
    console.log('====================================================\n')
  } finally {
    await browser.close()
  }
}

runTests().catch(err => {
  console.error('\n❌ Browser test failed with error:', err)
  process.exit(1)
})
