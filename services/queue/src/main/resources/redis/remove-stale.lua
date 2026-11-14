-- 폴링이 끊긴 대기자 제거. seen의 마지막 폴링 시각이 cutoff보다 오래된 사용자를 waiting과 seen에서 지운다.
-- 한 번에 지우는 수는 ARGV[2]로 제한한다. 반환값이 한도와 같으면 더 남았을 수 있다.
-- 기준 시각은 ARGV[3]이 비어 있으면 Redis TIME에서 staleAfterMs를 뺀 값이다(운영). 값이 있으면 그 시각을 쓴다(테스트).
-- KEYS: seen, waiting   ARGV: limit, staleAfterMs, nowMs(빈 문자열이면 TIME)
-- 반환: 지운 수
local now = tonumber(ARGV[3])
if not now then
  local t = redis.call('TIME')
  now = t[1] * 1000 + math.floor(t[2] / 1000)
end
local cutoff = now - tonumber(ARGV[2])
local ids = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', '(' .. cutoff, 'LIMIT', 0, tonumber(ARGV[1]))
for _, id in ipairs(ids) do
  redis.call('ZREM', KEYS[2], id)
  redis.call('ZREM', KEYS[1], id)
end
return #ids
