-- 대기열 진입. 판정, 번호 발급, 등록, heartbeat 기록을 한 번에 처리한다.
-- KEYS: conf, waiting, seen, seq, active   ARGV: userId
-- 반환: {1, rank, admissionPerSecond, created} 대기 | {3, tokenExpiresAtMs} 입장 완료
--       {-1} 회차 없음 | {-2, saleOpenAtMs} 오픈 전 | {-3} 판매 중이 아님
local conf = redis.call('HMGET', KEYS[1], 'status', 'saleOpenAt', 'admissionPerSecond')
if conf[1] == false or conf[1] == 'DELETED' then
  return {-1}
end
if conf[1] ~= 'ON_SALE' then
  return {-3}
end
local t = redis.call('TIME')
local nowMs = t[1] * 1000 + math.floor(t[2] / 1000)
local openAt = tonumber(conf[2])
if nowMs < openAt then
  return {-2, openAt}
end
local user = ARGV[1]
local exp = redis.call('ZSCORE', KEYS[5], user)
if exp and tonumber(exp) > nowMs then
  return {3, tonumber(exp)}
end
local rank = redis.call('ZRANK', KEYS[2], user)
local created = 0
if not rank then
  local n = redis.call('INCR', KEYS[4])
  redis.call('ZADD', KEYS[2], n, user)
  rank = redis.call('ZRANK', KEYS[2], user)
  created = 1
end
redis.call('ZADD', KEYS[3], nowMs, user)
return {1, rank, tonumber(conf[3]), created}
