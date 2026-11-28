import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const css = readFileSync(new URL('./tokens.css', import.meta.url), 'utf8')
const color = new Map([...css.matchAll(/--([\w-]+): (#[0-9a-f]{6});/g)].map((m) => [m[1], m[2]]))

// WCAG 2.x 상대 휘도
function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((i) => {
    const c = parseInt(hex.slice(i, i + 2), 16) / 255
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

function contrast(fg: string, bg: string): number {
  const [a, b] = [fg, bg].map((name) => luminance(color.get(name)!))
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05)
}

describe('글자색과 배경색의 대비', () => {
  it.each([
    ['ink', 'bg'],
    ['ink', 'surface'],
    ['ink-2', 'surface'],
    ['ink-2', 'line'],
    ['muted', 'bg'],
    ['muted', 'surface'],
    ['muted', 'line'],
    ['accent', 'bg'],
    ['accent', 'surface'],
    ['surface', 'accent'],
    ['surface', 'accent-dark'],
    ['success-fg', 'success-bg'],
    ['success-fg', 'bg'],
    ['warn-fg', 'warn-bg'],
    ['danger', 'danger-bg'],
    ['danger', 'bg'],
    ['danger', 'surface'],
    ['wheel', 'wheel-bg'],
  ])('%s / %s 4.5:1 이상', (fg, bg) => {
    expect(contrast(fg, bg)).toBeGreaterThanOrEqual(4.5)
  })
})
