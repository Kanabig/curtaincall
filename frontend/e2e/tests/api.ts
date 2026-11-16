// 시험 준비용 API 호출. 화면으로 하지 않는 준비(공연 등록, 구매용 회차)만 여기서 한다.
const base = process.env.BASE_URL ?? 'http://localhost:5173'
const KOPIS_ID = 'PF305720' // fixture 공연. 가격 등급 1층석

async function call(method: string, path: string, token?: string, body?: unknown) {
  const res = await fetch(`${base}/api/v1${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  return { status: res.status, json: text ? JSON.parse(text) : null }
}

export async function adminToken(): Promise<string> {
  const email = process.env.ADMIN_EMAIL ?? 'admin@curtaincall.local'
  const password = process.env.ADMIN_PASSWORD ?? 'admin-local-password'
  const r = await call('POST', '/auth/login', undefined, { email, password })
  if (r.status !== 200) throw new Error(`관리자 로그인 실패: ${r.status}`)
  return r.json.accessToken
}

/** fixture 공연을 판매 공연으로 등록하고(이미 있으면 목록에서 찾아) ID·이름을 돌려준다. */
export async function ensurePerformance(admin: string): Promise<{ id: number; name: string }> {
  const created = await call('POST', '/admin/performances', admin, { kopisId: KOPIS_ID })
  let id: number | undefined = created.json?.id
  if (!id) {
    const list = await call('GET', '/performances?size=50')
    id = list.json?.content?.find((p: { kopisId: string }) => p.kopisId === KOPIS_ID)?.id
  }
  if (!id) throw new Error(`공연 등록 실패(${created.status}): ${JSON.stringify(created.json)}`)
  const detail = await call('GET', `/performances/${id}`)
  return { id, name: detail.json.name }
}

/** 판매 오픈이 openInSeconds초 뒤인 회차를 만들고 ID를 돌려준다. */
export async function createSession(admin: string, performanceId: number, openInSeconds: number): Promise<number> {
  const r = await call('POST', `/admin/performances/${performanceId}/sessions`, admin, {
    startAt: new Date(Date.now() + 3 * 86400_000).toISOString(),
    saleOpenAt: new Date(Date.now() + openInSeconds * 1000).toISOString(),
    prices: [{ grade: '1층석', price: 10000 }],
    admissionPerSecond: 50,
    maxActiveUsers: 300,
  })
  const id = r.json?.sessionId ?? r.json?.id
  if (r.status !== 201 || !id) throw new Error(`회차 생성 실패(${r.status}): ${JSON.stringify(r.json)}`)
  return id
}

export async function seatMap(sessionId: number): Promise<{ seatId: number; x: number; y: number }[]> {
  const r = await call('GET', `/sessions/${sessionId}/seat-map`)
  return r.json ?? []
}
