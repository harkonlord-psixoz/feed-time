-- Household pet profile (LWW) + device token registry for family notifications

ALTER TABLE households
  ADD COLUMN IF NOT EXISTS pets_json JSONB NOT NULL DEFAULT '{"version":2,"pets":[{"species":"cat","name":""}]}'::jsonb,
  ADD COLUMN IF NOT EXISTS pets_updated_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS device_tokens (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  household_id UUID REFERENCES households(id) ON DELETE SET NULL,
  device_id TEXT NOT NULL,
  platform TEXT NOT NULL CHECK (platform IN ('android', 'web')),
  fcm_token TEXT,
  last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, device_id)
);

CREATE INDEX IF NOT EXISTS idx_device_tokens_household
  ON device_tokens(household_id)
  WHERE household_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_device_tokens_fcm
  ON device_tokens(household_id)
  WHERE fcm_token IS NOT NULL;
