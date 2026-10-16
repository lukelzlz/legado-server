// 打印某个测试类的失败消息（按 UTF-8 读，避免 PowerShell 的 GBK 解码把 XML 读坏）。
// 用法: node scripts/show-test-failures.cjs [类名过滤，可省略]
const fs = require('fs')
const path = require('path')

const dir = 'server/build/test-results/test'
const filter = process.argv[2] || ''
let shown = 0

for (const file of fs.readdirSync(dir).filter(f => f.endsWith('.xml'))) {
  if (filter && !file.includes(filter)) continue
  const text = fs.readFileSync(path.join(dir, file), 'utf8')
  const cls = (text.match(/<testsuite name="([^"]+)"/) || [])[1] || file
  const re = /<testcase name="([^"]*)"[^>]*?>([\s\S]*?)<\/testcase>/g
  let m
  while ((m = re.exec(text))) {
    const body = m[2]
    if (!/<failure/.test(body)) continue
    shown++
    const raw = (body.match(/<failure message="([\s\S]*?)" type=/) || [])[1] || '(无消息)'
    const message = raw
      .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"')
      .replace(/&apos;/g, "'").replace(/&amp;/g, '&')
    console.log(`\n=== ${cls.split('.').pop()} :: ${m[1]}`)
    console.log(message.split('\n').slice(0, 12).join('\n'))
  }
}

console.log(shown ? `\n共 ${shown} 条失败` : '\n没有失败用例')
