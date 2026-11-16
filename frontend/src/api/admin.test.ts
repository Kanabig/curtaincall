import { describe, expect, it } from 'vitest'
import { defaultPrices, gradeDefaults, isEditLocked, pricesFromGrades, parsePrices, toLocalKst, toOffsetKst } from './admin'

describe('isEditLocked', () => {
  const open = '2026-12-01T20:00:00+09:00'
  const t = new Date(open).getTime()
  it('오픈 10분 전부터 잠긴다', () => {
    expect(isEditLocked(open, t - 10 * 60_000 - 1)).toBe(false)
    expect(isEditLocked(open, t - 10 * 60_000)).toBe(true)
    expect(isEditLocked(open, t + 1)).toBe(true)
  })
})

describe('시각 변환', () => {
  it('KST 입력을 오프셋 문자열로 바꾸고 되돌린다', () => {
    expect(toOffsetKst('2026-12-01T20:00')).toBe('2026-12-01T20:00:00+09:00')
    expect(toLocalKst('2026-12-01T11:00:00Z')).toBe('2026-12-01T20:00')
    expect(toLocalKst('2026-12-01T20:00:00+09:00')).toBe('2026-12-01T20:00')
  })
})

describe('parsePrices', () => {
  it('등급별 가격을 읽는다', () => {
    expect(parsePrices('VIP 150000\nR 110000\n')).toEqual([
      { grade: 'VIP', price: 150000 },
      { grade: 'R', price: 110000 },
    ])
  })
  it('형식 오류·중복 등급·빈 입력은 null', () => {
    expect(parsePrices('VIP')).toBeNull()
    expect(parsePrices('VIP 0')).toBeNull()
    expect(parsePrices('R 1\nR 2')).toBeNull()
    expect(parsePrices('  ')).toBeNull()
  })
})

describe('defaultPrices', () => {
  it('첫 회차의 가격표를 기본값으로 쓴다', () => {
    expect(defaultPrices([{ prices: [{ grade: '1층석', price: 90000 }, { grade: '2층석', price: 60000 }] }])).toBe('1층석 90000\n2층석 60000')
  })
  it('회차가 없으면 null', () => {
    expect(defaultPrices([])).toBeNull()
  })
})

describe('pricesFromGrades', () => {
  it('등급 순서대로 가격표를 만든다', () => {
    expect(pricesFromGrades(['R석', 'S석'], { 'R석': '99000', 'S석': ' 77000 ' })).toEqual([
      { grade: 'R석', price: 99000 },
      { grade: 'S석', price: 77000 },
    ])
  })
  it('빈 칸·숫자가 아닌 값·0은 null', () => {
    expect(pricesFromGrades(['R석', 'S석'], { 'R석': '99000' })).toBeNull()
    expect(pricesFromGrades(['R석'], { 'R석': '9만' })).toBeNull()
    expect(pricesFromGrades(['R석'], { 'R석': '0' })).toBeNull()
  })
})

describe('gradeDefaults', () => {
  it('첫 회차에 있는 등급만 채우고 나머지는 빈 칸', () => {
    expect(gradeDefaults(['R석', 'S석'], [{ prices: [{ grade: 'R석', price: 99000 }] }])).toEqual({ 'R석': '99000', 'S석': '' })
    expect(gradeDefaults(['R석'], [])).toEqual({ 'R석': '' })
  })
})
