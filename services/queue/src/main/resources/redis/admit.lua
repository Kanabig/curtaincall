-- 입장 처리. 앞에서부터 min(admissionPerSecond, maxActiveUsers - 활성 수)명을 활성 목록에 넣는다.
-- 인스턴스가 여럿이어도 같은 초에는 한 번만 돈다: tick 키(초 단위)를 NX로 못 잡으면 바로 끝낸다.
-- 만료된 활성 사용자는 여기서 정리한다. 활성 수를 세기 직전에 지워야 상한이 만료 시각 기준으로 정확하다.
-- 꺼낸 대기자의 마지막 폴링이 admitStaleMs보다 오래됐으면 입장시키지 않고 대기열에서 지운 뒤 다음 사람으로 채운다.
-- 폴링이 끊긴 사람에게 자리를 주면 토큰을 받아 가지 않아 입장 TTL 동안 슬롯만 차지한다.
-- 시각은 ARGV[1]이 비어 있으면 Redis TIME을 쓴다. 운영은 비워서 인스턴스 시계와 상관없이 초를 Redis 한 곳에서 정하고(enter·status와 같은 기준),
-- 테스트만 시각을 넘겨 기다리지 않는다.
-- 지우는 수가 ARGV[5]에 닿으면 이번 틱은 거기서 멈춘다(Redis를 오래 잡지 않기 위해). 나머지는 다음 틱과 이탈 정리가 맡는다.
-- 활동이 idleMs(ARGV[6]) 넘게 없는 활성 사용자는 활성 목록에서 빼고 자리를 채운다(#145). 활동 시각은 gateway가 예매 API 요청 때 activity에 남기고,
-- 입장시킬 때 입장 시각으로 시작한다. 빠진 사용자의 토큰은 gateway가 활성 소속을 확인해 거절한다. idleMs가 0이면 회수하지 않는다. 한 틱에 dropLimit명까지.
-- KEYS: conf, waiting, seen, active, activity   ARGV: nowMs(빈 문자열이면 TIME), entryTtlMs, tickKeyPrefix, admitStaleMs, dropLimit, idleMs
-- 매진(conf.soldOut=1)이면 입장 0명, 만료 정리만 한다.
-- 반환: {0} 이번 초는 다른 인스턴스가 처리 | {1, 입장 수, 입장 뒤 활성 수, 폴링이 끊겨 지운 수, 활동 없어 회수한 수} | {-1} 판매 중이 아니거나 회차 없음
local now = tonumber(ARGV[1])
if not now then
  local t = redis.call('TIME')
  now = t[1] * 1000 + math.floor(t[2] / 1000)
end
if not redis.call('SET', ARGV[3] .. math.floor(now / 1000), '1', 'NX', 'EX', 5) then
  return {0}
end
local conf = redis.call('HMGET', KEYS[1], 'status', 'saleOpenAt', 'admissionPerSecond', 'maxActiveUsers', 'soldOut')
if conf[1] ~= 'ON_SALE' or now < tonumber(conf[2]) then
  return {-1}
end
local expired = redis.call('ZRANGEBYSCORE', KEYS[4], '-inf', now)
if #expired > 0 then
  -- 만료로 빠지는 사용자는 활동 기록도 같이 지운다(idleMs가 0이어도 activity가 쌓이지 않게)
  redis.call('ZREM', KEYS[4], unpack(expired))
  redis.call('ZREM', KEYS[5], unpack(expired))
end
local reclaimed = 0
local idleMs = tonumber(ARGV[6])
if idleMs and idleMs > 0 then
  local idle = redis.call('ZRANGEBYSCORE', KEYS[5], '-inf', now - idleMs, 'LIMIT', 0, tonumber(ARGV[5]))
  for _, user in ipairs(idle) do
    reclaimed = reclaimed + redis.call('ZREM', KEYS[4], user)
  end
  if #idle > 0 then
    redis.call('ZREM', KEYS[5], unpack(idle))
  end
end
local active = redis.call('ZCARD', KEYS[4])
-- 매진이면 입장시키지 않는다. 만료 정리는 위에서 했고 대기열은 그대로 둬서 매진이 풀리면 원래 순서로 재개한다(QUE-8)
if conf[5] == '1' then
  return {1, 0, active, 0, reclaimed}
end
local need = math.min(tonumber(conf[3]), tonumber(conf[4]) - active)
local exp = now + tonumber(ARGV[2])
local freshAfter = now - tonumber(ARGV[4])
local admitted = 0
local dropped = 0
local dropLimit = tonumber(ARGV[5])
while need > 0 and dropped < dropLimit do
  local popped = redis.call('ZPOPMIN', KEYS[2], need)
  if #popped == 0 then
    break
  end
  for i = 1, #popped, 2 do
    local lastSeen = redis.call('ZSCORE', KEYS[3], popped[i])
    redis.call('ZREM', KEYS[3], popped[i])
    if lastSeen and tonumber(lastSeen) >= freshAfter then
      redis.call('ZADD', KEYS[4], exp, popped[i])
      redis.call('ZADD', KEYS[5], now, popped[i])
      admitted = admitted + 1
      need = need - 1
    else
      dropped = dropped + 1
    end
  end
end
return {1, admitted, active + admitted, dropped, reclaimed}
