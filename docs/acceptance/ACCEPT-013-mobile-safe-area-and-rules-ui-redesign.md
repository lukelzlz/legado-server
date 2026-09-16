# 移动端全面屏死区适配与替换净化规则 UI 重构实操验收手册 (ACCEPT-013)

> **关联提案**：[`docs/proposals/PROPOSAL-013-mobile-safe-area-and-rules-ui-redesign.md`](file:///root/legado-server/docs/proposals/PROPOSAL-013-mobile-safe-area-and-rules-ui-redesign.md)  
> **关联架构决策**：[`docs/decisions/ADR-013-safe-area-layout-and-rules-design-system.md`](file:///root/legado-server/docs/decisions/ADR-013-safe-area-layout-and-rules-design-system.md)  
> **关联工作记忆**：[`docs/sessions/SESSION-014-safe-area-and-rules-ui-redesign.md`](file:///root/legado-server/docs/sessions/SESSION-014-safe-area-and-rules-ui-redesign.md)

---

## 1. 验收环境与快捷验证命令

### 最短自动化验证
```bash
# 1. 前端类型检查与自动化测试套件
npm --prefix web run check && npx tsx web/test/run-all.ts

# 2. 服务端 JVM 单元测试
./gradlew :server:test

# 3. 前后端综合一键验证
npm --prefix web run check && npx tsx web/test/run-all.ts && ./gradlew :server:test
```

### 本地 Docker 容器部署热更新验证
```bash
# 构建并重新启动本地测试容器
docker build -f Dockerfile.server -t test-legado-server:latest .
docker stop test-legado 2>/dev/null || true
docker rm -f test-legado 2>/dev/null || true
docker run -d --name test-legado -p 8080:8080 \
  -e ADMIN_PASSWORD=admin123 \
  -e LEGADO_SECURE_COOKIES=false \
  -v $(pwd)/.data:/data test-legado-server:latest
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/index.html
```

---

## 2. 核心功能实操核验清单

### 场景一：移动端全面屏刘海/灵动岛与底部手势条死区避让
1. **核验步骤**：
   - 使用带刘海/灵动岛的手机（或在 Chrome DevTools 设备模拟模式开启 `safe-area-inset-*`）访问系统；
   - 观察全局主导航顶栏（`.app-page-header`）：
     - 标题、Logo 与导航菜单在顶部具有充足安全间距（`var(--safe-top)`），不会被灵动岛或状态栏遮挡；
   - 进入任意书籍的沉浸式阅读器（`.reader-workspace`）：
     - 顶部阅读器工具栏（`.reader-header`）高度自适应为 `calc(56px + var(--safe-top))`，返回、换源、设置、朗读按钮下移并居中于内容区；
     - 底部移动端控制栏（`.mobile-reader-nav`）高度自适应为 `calc(58px + var(--safe-bottom))`，切章与目录快捷键完整避开屏幕底部的 Home Indicator 虚拟横条；
     - 正文内容无论是「上下滚动模式」还是「横向翻页模式」，顶部与底部文字均预留了对应的安全区内边距，文字不会与手机顶栏或底栏重叠。
2. **核验标准**：
   - 点击顶部/底部各功能按钮均灵敏可点，不会触发手机系统控制中心或多任务手势误触。

---

### 场景二：全站多主题色调一致性（Light / Paper / Dark）
1. **核验步骤**：
   - 打开右上角菜单或阅读器设置抽屉，依次切换 **「浅色 (Light)」**、**「羊皮纸 (Paper)」**、**「深色 (Dark)」** 主题；
   - 切换到主导航的 **「替换净化」**（Replace Rules）页面；
   - 检查规则列表、搜索框、分类标签（Pill）、沙箱测试面板、正则表达式输入框以及各操作按钮在三大主题下的表现。
2. **核验标准**：
   - 彻底消除了原先硬编码的 VSCode 暗黑块（`#1e1e1e` / `#252526`）；
   - 在 **浅色** 与 **羊皮纸** 模式下，代码框与沙箱背景自然融合为温暖柔和的浅色卡片底色（`var(--surface-muted)`），文字对比度清晰不刺眼；
   - 在 **深色** 模式下呈现细腻的哑光暗绿黑质感。

---

### 场景三：桌面端高密度管理与右侧实时调试沙箱
1. **核验步骤**：
   - 使用 PC / 桌面端浏览器打开「替换净化」页面（屏幕宽度 $\ge 768\text{px}$）；
   - 左侧为高密度规则列表（支持按分组筛选、搜索过滤、启用/禁用 Switch 开关、置顶、编辑、删除）；
   - 右侧为卡片式规则详情与实时验证沙箱；
   - 在右侧沙箱的「原始待测试文本」中粘贴一段包含防盗垃圾字符的文字，输入替换净化正则（例如 `\s*【防盗水印】\s*`），观察右侧「净化后结果」即时响应输出清洗后的纯净文本。
2. **核验标准**：
   - 点击左侧任一规则，右侧表单与沙箱即刻同步载入该规则配置；
   - 调试沙箱即时反馈替换结果，无卡顿或页面重载。

---

### 场景四：移动端流式折叠卡片（Accordion Flow）体验
1. **核验步骤**：
   - 将浏览器视口切换至移动端（宽度 $\le 720\text{px}$）打开「替换净化」页面；
   - 页面自动切换为单列流式布局，顶部为横向可滑动的分组选择标签栏；
   - 规则列表以卡片堆叠展示，点击任一规则卡片或点击底部「沙箱测试」，卡片平滑展开内嵌的轻量测试面板；
   - 点击底部快捷操作栏的「新建规则」或「导入」，弹出居中卡片表单进行配置。
2. **核验标准**：
   - 手机单手操作舒适，无横向溢出滚动；
   - 编辑与新建在移动端交互自然流畅。

---

### 场景五：阅读器内替换净化与 TTS 朗读闭环
1. **核验步骤**：
   - 进入包含防盗文字的小说章节；
   - 在阅读器「设置」抽屉中确保已勾选「启用替换净化规则」；
   - 开启 TTS 朗读功能，仔细聆听或查看高亮朗读分句。
2. **核验标准**：
   - 阅读器界面正文已被清洗，防盗段落与冗余乱码已被过滤；
   - TTS 朗读只读清洗后的纯净文字，不会朗读已被替换剔除的脏文本。

---

## 3. 回归与边界检查项

- [x] 1. 前端类型检查无任何报错（`npm run check` $\to$ 0 errors）。
- [x] 2. 前端 127 项自动化测试全部通过（`npx tsx web/test/run-all.ts` $\to$ 100% pass）。
- [x] 3. 服务端 154 项 JVM 单元测试全部通过（`./gradlew :server:test` $\to$ BUILD SUCCESSFUL）。
- [x] 4. Chrome 真实浏览器截图审查通过（浅色/羊皮纸/深色三端渲染无异常）。
- [x] 5. PWA 离线存储提示与更新浮层颜色已与全站设计令牌对齐。
