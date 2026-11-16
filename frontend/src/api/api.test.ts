import { describe, expect, it } from 'vitest'
import { buildListQuery } from './index'

describe('buildListQuery', () => {
  it('빈 값은 빼고 page·size를 붙인다', () => {
    const q = new URLSearchParams(buildListQuery({ keyword: ' 캣츠 ', genre: '', state: 'ONGOING', page: 2 }))
    expect(q.get('keyword')).toBe('캣츠')
    expect(q.has('genre')).toBe(false)
    expect(q.get('state')).toBe('ONGOING')
    expect(q.get('page')).toBe('2')
    expect(q.get('size')).toBe('20')
  })
})
