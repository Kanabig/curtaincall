-- 회차 이벤트를 conf에 반영한다. 이벤트에 실린 값으로 덮어쓰므로 같은 이벤트를 여러 번 적용해도 결과가 같다.
-- 이미 반영한 것보다 오래된 설정 이벤트(ver 비교)는 버린다. 중지·삭제는 종결 상태라 ver와 상관없이 항상 반영하고,
-- 저장하는 ver는 지금까지 본 가장 큰 값으로 둔다. 중지·삭제는 되돌릴 수 없는 종결 상태라, 이미 종결된 회차에 온 설정 이벤트는 무시한다.
-- KEYS: conf, open, waiting, seen, seq, active
-- ARGV: sessionId, type(UPSERT|SUSPEND|DELETE), ver(이벤트 발생 시각 ms), saleOpenAtMs, admissionPerSecond, maxActiveUsers
-- 반환: 1 반영 | 0 오래된 이벤트라 무시
local kind = ARGV[2]
local cur = redis.call('HGET', KEYS[1], 'ver')
local ver = ARGV[3]
if cur and tonumber(cur) > tonumber(ver) then
  if kind == 'UPSERT' then
    return 0
  end
  ver = cur
end
local status = redis.call('HGET', KEYS[1], 'status')
if kind == 'UPSERT' then
  if status == 'SUSPENDED' or status == 'DELETED' then
    return 0
  end
  status = 'ON_SALE'
  redis.call('HSET', KEYS[1], 'saleOpenAt', ARGV[4], 'admissionPerSecond', ARGV[5],
    'maxActiveUsers', ARGV[6], 'status', status, 'ver', ver)
elseif kind == 'SUSPEND' then
  if status ~= 'DELETED' then
    status = 'SUSPENDED'
  end
  redis.call('HSET', KEYS[1], 'status', status, 'ver', ver)
else
  status = 'DELETED'
  redis.call('HSET', KEYS[1], 'status', status, 'ver', ver)
  redis.call('DEL', KEYS[3], KEYS[4], KEYS[5], KEYS[6])
end
if status == 'ON_SALE' then
  redis.call('SADD', KEYS[2], ARGV[1])
else
  redis.call('SREM', KEYS[2], ARGV[1])
end
return 1
