// 1만 석 좌석 격자 렌더링 측정. API는 route mock이라 frontend 컨테이너(프로덕션 build)만 있으면 된다.
// 조건: 100x100 격자(셀 14px), 점유 2000·판매 3000, 1280x800, 새 컨텍스트 RUNS회. 실행 방법은 frontend/README.md.
import { chromium } from '@playwright/test'

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:5173'
const RUNS = Number(process.env.RUNS ?? 10)
const SIDE = 100
const SESSION = '1'
const CELL = 14

const seats = Array.from({ length: SIDE * SIDE }, (_, i) => ({
  seatId: i + 1, floor: 1, section: 'A', row: String(Math.floor(i / SIDE) + 1), number: (i % SIDE) + 1,
  x: i % SIDE, y: Math.floor(i / SIDE), grade: '1층석', price: 10000, wheelchair: false,
}))
const held = seats.filter((s) => [0, 5].includes(s.seatId % 10)).map((s) => s.seatId) // 2000
const sold = seats.filter((s) => [1, 3, 7].includes(s.seatId % 10)).map((s) => s.seatId) // 3000
const freeSeats = seats.filter((s) => ![0, 5, 1, 3, 7].includes(s.seatId % 10))

const pct = (a, p) => [...a].sort((x, y) => x - y)[Math.min(a.length - 1, Math.ceil((p / 100) * a.length) - 1)]
const fmt = (a) => `n=${a.length} p50 ${pct(a, 50).toFixed(1)} / p95 ${pct(a, 95).toFixed(1)} / max ${Math.max(...a).toFixed(1)}`

const browser = await chromium.launch()
const draws = [], frames = [], clicks = []
let version = 1

for (let run = 0; run < RUNS; run++) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } })
  await ctx.addInitScript(({ sid }) => {
    localStorage.setItem('curtaincall.tokens', JSON.stringify({ accessToken: 'x', refreshToken: 'x' }))
    sessionStorage.setItem(`curtaincall.entry.${sid}`, JSON.stringify({ token: 'x', expiresAt: Date.now() + 3_600_000 }))
  }, { sid: SESSION })
  await ctx.route('**/api/v1/**', (route) => {
    const url = route.request().url()
    const json = (body) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) })
    if (url.endsWith(`/sessions/${SESSION}/seat-map`)) return json(seats)
    if (url.endsWith(`/sessions/${SESSION}/seat-status`)) return json({ version: version++, held, sold })
    if (url.endsWith('/users/me')) return json({ id: 1, email: 'bench@example.com', name: 'bench', role: 'USER' })
    return route.fulfill({ status: 404, contentType: 'application/json', body: '{}' })
  })
  const page = await ctx.newPage()
  await page.goto(`${BASE_URL}/sessions/${SESSION}/seats`)
  await page.getByLabel('좌석 배치도').waitFor()
  // 상태 폴링(2초)이 두 번 더 지나 재그리기가 일어나도록 둔다
  await page.waitForTimeout(4500)
  draws.push(...(await page.evaluate(() => performance.getEntriesByName('seat-draw').map((e) => e.duration))))

  // 3초 동안 스크롤하며 프레임 간격을 잰다
  const gaps = await page.evaluate(async () => {
    const box = document.querySelector('.seat-scroll')
    const gaps = []
    let last = performance.now()
    const t0 = last
    await new Promise((done) => {
      const step = (now) => {
        gaps.push(now - last)
        last = now
        const p = (now - t0) / 3000
        box.scrollTop = p * (box.scrollHeight - box.clientHeight)
        box.scrollLeft = p * (box.scrollWidth - box.clientWidth)
        p < 1 ? requestAnimationFrame(step) : done()
      }
      requestAnimationFrame(step)
    })
    return gaps.slice(1)
  })
  frames.push(...gaps)

  // 좌석 클릭 → 재그리기 완료까지. 화면 맨 위로 돌려 두고 보이는 좌석 4개를 차례로 누른다
  await page.evaluate(() => { const b = document.querySelector('.seat-scroll'); b.scrollTop = 0; b.scrollLeft = 0 })
  await page.evaluate(() => {
    window.__clickMs = []
    const c = document.querySelector('canvas')
    c.addEventListener('click', () => {
      const t = performance.now()
      const n = performance.getEntriesByName('seat-draw').length
      const poll = () => {
        const e = performance.getEntriesByName('seat-draw')
        if (e.length > n) window.__clickMs.push(e[e.length - 1].startTime + e[e.length - 1].duration - t)
        else requestAnimationFrame(poll)
      }
      requestAnimationFrame(poll)
    }, true)
  })
  for (const s of freeSeats.filter((s) => s.y < 30 && s.x < 50).slice(0, 4)) {
    await page.getByLabel('좌석 배치도').click({ position: { x: s.x * CELL + CELL / 2, y: s.y * CELL + CELL / 2 } })
    await page.waitForTimeout(300)
  }
  clicks.push(...(await page.evaluate(() => window.__clickMs)))
  await ctx.close()
}
await browser.close()

const over33 = frames.filter((g) => g > 33).length
console.log(`SHA ${process.env.GIT_SHA ?? '미지정'} · 컨텍스트 ${RUNS}회 · ${SIDE}x${SIDE} 격자, 점유 ${held.length}·판매 ${sold.length}`)
console.log(`canvas 전체 그리기(ms)      ${fmt(draws)}`)
console.log(`스크롤 프레임 간격(ms)       ${fmt(frames)}, 33ms 초과 ${over33}`)
console.log(`클릭→재그리기 완료(ms)       ${fmt(clicks)}`)
