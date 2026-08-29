import { run } from 'node:test'
import { spec } from 'node:test/reporters'
import { glob } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

// Import all test suites to register test definitions
import './VirtualChapterList.test.ts'
import './LazySourceLoading.test.ts'
import './ReaderOptimization.test.ts'
import './ReaderPagination.test.ts'
import './TOCPerformanceScenario.test.ts'
import './ChallengerEmpiricalStress.test.ts'
import './SourceSwitchAndCache.test.ts'
import './SearchStatePersistence.test.ts'
import './search-sort.test.ts'
import './source-inspector.test.ts'
import './HeaderMenu.test.ts'
import './api-client.test.ts'
import './source-inspector-concurrent.test.ts'
import './search-store-lifecycle.test.ts'
import './reader-interactions-settings.test.ts'
import './ToastStore.test.ts'

console.log('🚀 Running Legado Web Frontend Comprehensive Test Suite (Tier 1, 2, 4 + Challenger Stress)...\n')


