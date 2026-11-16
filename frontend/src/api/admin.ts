// 관리자 화면용 순수 함수. 시각은 모두 KST(+09:00) 기준으로 다룬다.

export const EDIT_LOCK_MINUTES = 10

/** 서버에 보내기 전 폼 검증 실패. */
export class InputError extends Error {}

/** 판매 오픈 10분 전부터는 일시·오픈 시각·가격을 수정할 수 없다(ADM-2). */
export function isEditLocked(saleOpenAt: string, now: number = Date.now()): boolean {
  const open = new Date(saleOpenAt).getTime()
  return Number.isNaN(open) || now >= open - EDIT_LOCK_MINUTES * 60_000
}

/** `datetime-local` 값(2026-12-01T20:00)을 +09:00 오프셋 ISO 문자열로 바꾼다. */
export function toOffsetKst(local: string): string {
  return local.length === 16 ? `${local}:00+09:00` : `${local}+09:00`
}

/** ISO 시각을 `datetime-local` 입력값(KST)으로 바꾼다. */
export function toLocalKst(iso: string): string {
  const d = new Date(new Date(iso).getTime() + 9 * 3_600_000)
  return Number.isNaN(d.getTime()) ? '' : d.toISOString().slice(0, 16)
}

/** "VIP 150000" 줄바꿈 입력을 가격표로 바꾼다. 형식이 틀리면 null. */
export function parsePrices(text: string): { grade: string; price: number }[] | null {
  const rows = text
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
  if (rows.length === 0) return null
  const out: { grade: string; price: number }[] = []
  for (const row of rows) {
    const m = /^(\S+)\s+(\d+)$/.exec(row)
    if (!m || Number(m[2]) < 1) return null
    out.push({ grade: m[1], price: Number(m[2]) })
  }
  return new Set(out.map((p) => p.grade)).size === out.length ? out : null
}

export function pricesToText(prices: { grade: string; price: number }[]): string {
  return prices.map((p) => `${p.grade} ${p.price}`).join('\n')
}
