-- 폴링이 끊긴 대기자 제거. seen의 마지막 폴링 시각이 cutoff보다 오래된 사용자를 waiting과 seen에서 지운다.
-- 한 번에 지우는 수는 ARGV[2]로 제한한다. 반환값이 한도와 같으면 더 남았을 수 있다.
-- KEYS: seen, waiting   ARGV: cutoffMs, limit
-- 반환: 지운 수
local ids = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', '(' .. ARGV[1], 'LIMIT', 0, tonumber(ARGV[2]))
for _, id in ipairs(ids) do
  redis.call('ZREM', KEYS[2], id)
  redis.call('ZREM', KEYS[1], id)
end
return #ids
