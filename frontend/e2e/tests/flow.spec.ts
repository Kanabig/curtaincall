import { expect, test, type Page } from '@playwright/test'
import { adminToken, createSession, ensurePerformance, seatMap } from './api'

const CELL = 14
const PASSWORD = 'password-1234'

let perf: { id: number; name: string }
let sessionId: number

test.beforeAll(async () => {
  const admin = await adminToken()
  perf = await ensurePerformance(admin)
  // 판매 오픈이 곧 되는 회차. 대기열 진입 화면의 오픈 전 카운트다운도 함께 지난다
  sessionId = await createSession(admin, perf.id, 8)
})

async function logout(page: Page) {
  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page.getByRole('link', { name: '로그인' })).toBeVisible()
}

// datetime-local 입력용 KST 시각(YYYY-MM-DDTHH:mm)
function kst(offsetMs: number): string {
  const p = new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(new Date(Date.now() + offsetMs))
  return p.replace(' ', 'T')
}

test('가입부터 결제, 예매 내역 취소까지', async ({ page }) => {
  const email = `e2e-${Date.now()}@example.com`

  await test.step('가입하면 로그인 상태가 되고 관리 메뉴는 보이지 않는다', async () => {
    await page.goto('/signup')
    await page.getByLabel('이메일').fill(email)
    await page.getByLabel(/^비밀번호/).fill(PASSWORD)
    await page.getByLabel('이름').fill('e2e')
    await page.getByRole('button', { name: '가입' }).click()
    await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible()
    await expect(page.getByRole('link', { name: '예매 내역' })).toBeVisible()
    await expect(page.getByRole('link', { name: '관리', exact: true })).toHaveCount(0)
  })

  await test.step('관리 주소로 직접 들어가도 관리 화면이 열리지 않는다', async () => {
    await page.goto('/admin')
    await expect(page.getByRole('alert')).toHaveText('관리자만 접근할 수 있습니다.')
    await expect(page.getByRole('heading', { name: '회차 관리' })).toHaveCount(0)
  })

  await test.step('로그아웃 뒤 다시 로그인한다', async () => {
    await page.goto('/')
    await logout(page)
    await page.getByRole('link', { name: '로그인' }).click()
    await page.getByLabel('이메일').fill(email)
    await page.getByLabel('비밀번호').fill(PASSWORD)
    await page.getByRole('button', { name: '로그인' }).click()
    await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible()
  })

  await test.step('공연을 검색해 상세로 들어가고 회차의 대기열로 간다', async () => {
    await page.getByPlaceholder('공연명').fill(perf.name)
    await page.getByRole('button', { name: '검색' }).click()
    await page.getByRole('link', { name: new RegExp(perf.name.slice(0, 10)) }).first().click()
    await expect(page.getByRole('heading', { name: perf.name })).toBeVisible()
    await page.locator(`a[href="/sessions/${sessionId}/queue"]`).click()
  })

  await test.step('오픈 전에는 카운트다운, 오픈되면 자동으로 입장해 좌석 화면이 열린다', async () => {
    await expect(page).toHaveURL(new RegExp(`/sessions/${sessionId}/seats$`), { timeout: 60_000 })
    await expect(page.getByRole('heading', { name: '좌석 선택' })).toBeVisible()
    await expect(page.getByLabel('좌석 배치도')).toBeVisible()
  })

  await test.step('좌석을 고르고 점유한다', async () => {
    // 좌석 재고는 비동기로 만들어지므로 점유가 되기까지 선택·점유를 반복한다
    const seats = await seatMap(sessionId)
    expect(seats.length).toBeGreaterThan(0)
    const s = seats[0]
    await expect(async () => {
      if (!(await page.getByText(/점유한 좌석 1석/).isVisible())) {
        await page.getByLabel('좌석 배치도').click({ position: { x: s.x * CELL + CELL / 2, y: s.y * CELL + CELL / 2 } })
        await expect(page.getByText(/선택 1\/4석/)).toBeVisible({ timeout: 2000 })
        await page.getByRole('button', { name: '좌석 점유' }).click()
      }
      await expect(page.getByText(/점유한 좌석 1석/)).toBeVisible({ timeout: 5000 })
    }).toPass({ timeout: 90_000 })
  })

  await test.step('결제하면 예매가 확정된다', async () => {
    await page.getByRole('button', { name: '결제하기' }).click()
    await expect(page.getByRole('button', { name: /원 결제$/ })).toBeVisible()
    await page.getByRole('button', { name: /원 결제$/ }).click()
    await expect(page.getByRole('heading', { name: '주문 예매 완료' })).toBeVisible({ timeout: 60_000 })
  })

  await test.step('예매 내역에서 취소하면 환불되고 취소됨으로 바뀐다', async () => {
    await page.getByRole('link', { name: '예매 내역 보기' }).click()
    await page.getByRole('link', { name: /1석/ }).first().click()
    await expect(page.getByRole('button', { name: '예매 취소' })).toBeVisible()
    page.once('dialog', (d) => void d.accept())
    await page.getByRole('button', { name: '예매 취소' }).click()
    await expect(page.getByText('취소되었고 결제 금액이 환불되었습니다.')).toBeVisible({ timeout: 60_000 })
    await page.getByRole('link', { name: '← 예매 내역' }).click()
    await expect(page.getByText('취소됨')).toBeVisible()
  })
})

test('관리자는 관리 메뉴로 회차를 등록한다', async ({ page }) => {
  await page.goto('/login')
  await page.getByLabel('이메일').fill(process.env.ADMIN_EMAIL ?? 'admin@curtaincall.local')
  await page.getByLabel('비밀번호').fill(process.env.ADMIN_PASSWORD ?? 'admin-local-password')
  await page.getByRole('button', { name: '로그인' }).click()
  await page.getByRole('link', { name: '관리', exact: true }).click()
  await expect(page.getByRole('heading', { name: '관리', exact: true })).toBeVisible()

  await page.locator('.admin select').selectOption(String(perf.id))
  await expect(page.getByRole('heading', { name: '회차 추가' })).toBeVisible()
  const before = await page.locator('.admin .sessions > li').count()

  // 시각은 KST 기준 입력. 오픈은 사흘 뒤, 공연은 닷새 뒤라 다른 시험과 겹치지 않는다
  await page.getByLabel('공연 일시(KST)').last().fill(kst(5 * 86400_000))
  await page.getByLabel('판매 오픈(KST)').last().fill(kst(3 * 86400_000))
  await page.getByRole('button', { name: '회차 등록' }).click()
  await expect(page.getByText('회차를 등록했습니다.')).toBeVisible()
  await expect(page.locator('.admin .sessions > li')).toHaveCount(before + 1)
})
