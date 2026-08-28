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
import './HeaderMenu.test.ts'

console.log('🚀 Running Legado Web Frontend Comprehensive Test Suite (Tier 1, 2, 4 + Challenger Stress)...\n')
