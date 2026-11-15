-- 매진 여부를 conf에 반영한다. reservation이 회차별로 1씩 올리는 version이 더 클 때만 덮어쓰므로
-- 중복·역순으로 와도 마지막 version의 상태로 수렴한다. 회차 설정(apply-conf)의 ver와는 따로 비교한다.
-- KEYS: conf   ARGV: soldOut(1|0), version
-- 반환: 1 반영 | 0 이미 반영한 것보다 오래됐거나 같은 version이라 무시
local cur = redis.call('HGET', KEYS[1], 'soldOutVer')
if cur and tonumber(cur) >= tonumber(ARGV[2]) then
  return 0
end
redis.call('HSET', KEYS[1], 'soldOut', ARGV[1], 'soldOutVer', ARGV[2])
return 1
