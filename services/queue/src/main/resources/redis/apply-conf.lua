-- 회차 이벤트를 conf에 반영한다. 이벤트에 실린 값으로 덮어쓰므로 같은 이벤트를 여러 번 적용해도 결과가 같다.
-- 이미 반영한 것보다 오래된 이벤트(ver 비교)는 버린다. 중지·삭제는 되돌리지 않는다.
-- KEYS: conf, open, waiting, seen, seq, active
-- ARGV: sessionId, type(UPSERT|SUSPEND|DELETE), ver(이벤트 발생 시각 ms), saleOpenAtMs, admissionPerSecond, maxActiveUsers
-- 반환: 1 반영 | 0 오래된 이벤트라 무시
local cur = redis.call('HGET', KEYS[1], 'ver')
if cur and tonumber(cur) > tonumber(ARGV[3]) then
  return 0
end
local status = redis.call('HGET', KEYS[1], 'status')
local kind = ARGV[2]
if kind == 'UPSERT' then
  if status ~= 'SUSPENDED' and status ~= 'DELETED' then
    status = 'ON_SALE'
  end
  redis.call('HSET', KEYS[1], 'saleOpenAt', ARGV[4], 'admissionPerSecond', ARGV[5],
    'maxActiveUsers', ARGV[6], 'status', status, 'ver', ARGV[3])
elseif kind == 'SUSPEND' then
  if status ~= 'DELETED' then
    status = 'SUSPENDED'
  end
  redis.call('HSET', KEYS[1], 'status', status, 'ver', ARGV[3])
else
  status = 'DELETED'
  redis.call('HSET', KEYS[1], 'status', status, 'ver', ARGV[3])
  redis.call('DEL', KEYS[3], KEYS[4], KEYS[5], KEYS[6])
end
if status == 'ON_SALE' then
  redis.call('SADD', KEYS[2], ARGV[1])
else
  redis.call('SREM', KEYS[2], ARGV[1])
end
return 1
