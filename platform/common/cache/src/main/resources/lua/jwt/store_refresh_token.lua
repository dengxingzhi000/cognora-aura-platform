-- Track the current refresh token for each user/device atomically.
-- KEYS[1] = refreshTokensHash, ARGV[1] = deviceId, ARGV[2] = token, ARGV[3] = ttl seconds
redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
redis.call('EXPIRE', KEYS[1], ARGV[3])
return 1
