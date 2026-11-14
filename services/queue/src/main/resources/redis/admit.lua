-- 입장 처리. 앞에서부터 min(admissionPerSecond, maxActiveUsers - 활성 수)명을 활성 목록에 넣는다.
-- 인스턴스가 여럿이어도 같은 초에는 한 번만 돈다: tick 키(초 단위)를 NX로 못 잡으면 바로 끝낸다.
-- 만료된 활성 사용자는 여기서 정리한다. 활성 수를 세기 직전에 지워야 상한이 만료 시각 기준으로 정확하다.
-- KEYS: conf, waiting, seen, active, tick   ARGV: nowMs, entryTtlMs
-- 반환: {0} 이번 초는 다른 인스턴스가 처리 | {1, 입장 수, 입장 뒤 활성 수} | {-1} 판매 중이 아니거나 회차 없음
if not redis.call('SET', KEYS[5], '1', 'NX', 'EX', 5) then
  return {0}
end
local conf = redis.call('HMGET', KEYS[1], 'status', 'saleOpenAt', 'admissionPerSecond', 'maxActiveUsers')
local now = tonumber(ARGV[1])
if conf[1] ~= 'ON_SALE' or now < tonumber(conf[2]) then
  return {-1}
end
redis.call('ZREMRANGEBYSCORE', KEYS[4], '-inf', now)
local active = redis.call('ZCARD', KEYS[4])
local n = math.min(tonumber(conf[3]), tonumber(conf[4]) - active)
local admitted = 0
if n > 0 then
  local popped = redis.call('ZPOPMIN', KEYS[2], n)
  local exp = now + tonumber(ARGV[2])
  for i = 1, #popped, 2 do
    redis.call('ZADD', KEYS[4], exp, popped[i])
    redis.call('ZREM', KEYS[3], popped[i])
    admitted = admitted + 1
  end
end
return {1, admitted, active + admitted}
