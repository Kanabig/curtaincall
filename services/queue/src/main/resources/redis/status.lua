-- 내 상태 조회. 대기 중이면 heartbeat를 갱신한다.
-- KEYS: conf, waiting, seen, active   ARGV: userId
-- 반환: {1, rank, admissionPerSecond} 대기 | {3, tokenExpiresAtMs} 입장 완료 | {4} 대기열에 없음
--       {-1} 회차 없음 | {-3} 판매 중이 아님
local conf = redis.call('HMGET', KEYS[1], 'status', 'admissionPerSecond')
if conf[1] == false or conf[1] == 'DELETED' then
  return {-1}
end
if conf[1] ~= 'ON_SALE' then
  return {-3}
end
local t = redis.call('TIME')
local nowMs = t[1] * 1000 + math.floor(t[2] / 1000)
local user = ARGV[1]
local exp = redis.call('ZSCORE', KEYS[4], user)
if exp and tonumber(exp) > nowMs then
  return {3, tonumber(exp)}
end
local rank = redis.call('ZRANK', KEYS[2], user)
if not rank then
  return {4}
end
redis.call('ZADD', KEYS[3], nowMs, user)
return {1, rank, tonumber(conf[2])}
