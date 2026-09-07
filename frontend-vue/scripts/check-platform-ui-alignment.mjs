import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative, resolve } from 'node:path'
import { createHash } from 'node:crypto'

const root = resolve(import.meta.dirname, '..')
const source = join(root, 'src')
const referenceRoot = '/root/autodl-fs/video-stream-ai-analysis-alerting-platform/video-ai-platform/frontend'
const failures = []
const checks = []

function read(path) {
  return readFileSync(path, 'utf8')
}

function check(name, condition, detail) {
  checks.push({ name, pass: Boolean(condition), detail })
  if (!condition) failures.push(`${name}: ${detail}`)
}

function walk(directory) {
  return readdirSync(directory).flatMap((name) => {
    const path = join(directory, name)
    return statSync(path).isDirectory() ? walk(path) : [path]
  })
}

const packageJson = JSON.parse(read(join(root, 'package.json')))
const referencePackage = JSON.parse(read(join(referenceRoot, 'package.json')))
for (const dependency of ['vue', 'vue-router', 'pinia', 'axios', 'element-plus']) {
  check(
    `dependency:${dependency}`,
    packageJson.dependencies[dependency] === referencePackage.dependencies[dependency],
    `${packageJson.dependencies[dependency]} === ${referencePackage.dependencies[dependency]}`
  )
}
for (const dependency of ['vite', '@vitejs/plugin-vue']) {
  check(
    `devDependency:${dependency}`,
    packageJson.devDependencies[dependency] === referencePackage.devDependencies[dependency],
    `${packageJson.devDependencies[dependency]} === ${referencePackage.devDependencies[dependency]}`
  )
}

const localTheme = read(join(source, 'styles/theme.css'))
const referenceTheme = read(join(referenceRoot, 'src/styles/theme.css'))
const digest = (value) => createHash('sha256').update(value).digest('hex')
const normalizeEof = (value) => `${value.trimEnd()}\n`
check('theme:source-identical', normalizeEof(localTheme) === normalizeEof(referenceTheme), `${digest(normalizeEof(localTheme))} === ${digest(normalizeEof(referenceTheme))}`)

const appSource = read(join(source, 'App.vue'))
const mainSource = read(join(source, 'main.js'))
check('app:config-provider', appSource.includes('<el-config-provider') && appSource.includes('zh-cn'), 'Element Plus locale provider matches reference shell pattern')
check('main:canonical-theme', mainSource.includes("./styles/theme.css"), 'Canonical reference theme is imported by the application entrypoint')

const layoutSource = read(join(source, 'layout/index.vue'))
check('layout:brand-component', layoutSource.includes('<BrandMark') && layoutSource.includes("platform-ui/BrandMark.vue"), 'Layout uses shared brand component')
check('layout:navigation-icon', layoutSource.includes('<NavigationIcon') && layoutSource.includes("platform-ui/NavigationIcon.vue"), 'Layout uses the reference NavigationIcon implementation')
const localNavigationIcon = read(join(source, 'components/platform-ui/NavigationIcon.vue'))
const referenceNavigationIcon = read(join(referenceRoot, 'src/components/NavigationIcon.vue'))
check('component:navigation-icon-source', normalizeEof(localNavigationIcon) === normalizeEof(referenceNavigationIcon), 'NavigationIcon source matches the reference component')

const componentContracts = {
  'views/Dashboard.vue': ['PageHeading', 'MetricCard', 'PanelCard', 'StatusPill', 'PlatformButton'],
  'views/ProjectList.vue': ['PageHeading', 'PanelCard', 'StatusPill', 'PlatformButton'],
  'views/ProjectDetail.vue': ['PageHeading'],
  'views/ProjectAutoLabel.vue': ['PageHeading'],
  'views/SingleClassWorkflow.vue': ['PageHeading', 'MetricCard', 'PlatformButton'],
  'views/Profile.vue': ['PageHeading'],
  'views/Settings.vue': ['PageHeading'],
  'views/feasibility/AssessmentList.vue': ['PageHeading', 'PanelCard', 'StatusPill', 'PlatformButton'],
  'views/feasibility/CreateAssessment.vue': ['PageHeading'],
  'views/feasibility/AssessmentDetail.vue': ['PageHeading']
}

for (const [file, components] of Object.entries(componentContracts)) {
  const content = read(join(source, file))
  for (const component of components) {
    check(`component:${file}:${component}`, content.includes(`<${component}`) && content.includes(`platform-ui/${component}.vue`), `${file} imports and renders ${component}`)
  }
}

const primitives = walk(join(source, 'components/platform-ui')).filter((path) => path.endsWith('.vue'))
for (const file of primitives) {
  const content = read(file)
  check(`primitive:native:${relative(source, file)}`, !/<el-[a-z-]+/.test(content), 'Shared platform primitive is native Vue markup, matching the reference implementation style')
}

const viewFiles = walk(join(source, 'views')).filter((path) => path.endsWith('.vue'))
const duplicatedHeaders = viewFiles
  .filter((path) => /class="(?:page-intro|page-heading)"/.test(read(path)))
  .map((path) => relative(source, path))
check('views:no-duplicated-page-shell', duplicatedHeaders.length === 0, duplicatedHeaders.length ? duplicatedHeaders.join(', ') : 'All core page shells use PageHeading')

const result = {
  all_pass: failures.length === 0,
  checks: checks.length,
  passed: checks.filter((item) => item.pass).length,
  failed: failures,
  theme_sha256: digest(normalizeEof(localTheme)),
  reference_theme_sha256: digest(normalizeEof(referenceTheme)),
  platform_primitives: primitives.map((path) => relative(source, path)),
  component_contracts: componentContracts
}

console.log(JSON.stringify(result, null, 2))
if (failures.length) process.exitCode = 1
