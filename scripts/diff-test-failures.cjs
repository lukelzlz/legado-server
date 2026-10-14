// 从 Gradle 的 JUnit XML 里提取「失败集合」，再与干净基线逐条 diff。
// ⚠️ 必须用 Node 而不是 PowerShell 5.1 读这些 XML：PS 会按 GBK 解码，
// 中文消息末尾的引号会被当成多字节字符吃掉，XML 直接解析失败（实测踩过）。
const fs = require('fs')
const path = require('path')

const dir = process.argv[2] || 'server/build/test-results/test'
const baselinePath = process.argv[3] || path.join(process.env.TEMP, 'failing-baseline.txt')
const outPath = process.argv[4] || path.join(process.env.TEMP, 'failing-p2.txt')

const failures = []
let total = 0
for (const file of fs.readdirSync(dir).filter(f => f.endsWith('.xml'))) {
  const text = fs.readFileSync(path.join(dir, file), 'utf8')
  const cls = (text.match(/<testsuite name="([^"]+)"/) || [])[1] || ''
  const short = cls.split('.').pop()
  const re = /<testcase name="([^"]*)"[^>]*?(\/>|>([\s\S]*?)<\/testcase>)/g
  let m
  while ((m = re.exec(text))) {
    total++
    const name = m[1]
    const body = m[3] || ''
    if (/<failure/.test(body)) failures.push(`${short} :: ${name}`)
  }
}

failures.sort()
fs.writeFileSync(outPath, failures.join('\n'), 'utf8')

const baseline = fs.readFileSync(baselinePath, 'utf8').split(/\r?\n/).map(s => s.trim()).filter(Boolean).sort()
const setBase = new Set(baseline)
const setNew = new Set(failures)
const added = failures.filter(x => !setBase.has(x))
const removed = baseline.filter(x => !setNew.has(x))

console.log(`全量用例数: ${total}`)
console.log(`本次失败数: ${failures.length}`)
console.log(`基线失败数: ${baseline.length}`)
console.log(`失败集合已写入: ${outPath}`)
console.log('=== 新增失败（= 我引入的回归）===')
if (!added.length) console.log('（无）')
added.forEach(x => console.log('+ ' + x))
console.log('=== 消失的失败（= 顺带修好了）===')
if (!removed.length) console.log('（无）')
removed.forEach(x => console.log('- ' + x))
if (!added.length && !removed.length) console.log('>>> 失败集合与干净基线完全一致 ⇒ 零回归')
