-- 입장 토큰으로 예매 API를 부른 사용자가 아직 활성 목록에 있는지 확인하고, 마지막 활동 시각을 남긴다(#145).
-- 활동 기록은 사용자당 intervalMs에 한 번만 쓴다. 활성 목록에 없는(회수됐거나 만료된) 사용자는 기록하지 않는다.
-- KEYS: active, activity   ARGV: userId, intervalMs
-- 반환: 1 활성 목록에 있음 | 0 없음
local t = redis.call('TIME')
local now = t[1] * 1000 + math.floor(t[2] / 1000)
local exp = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not exp or tonumber(exp) <= now then
  return 0
end
local last = redis.call('ZSCORE', KEYS[2], ARGV[1])
if not last or now - tonumber(last) >= tonumber(ARGV[2]) then
  redis.call('ZADD', KEYS[2], now, ARGV[1])
end
return 1
