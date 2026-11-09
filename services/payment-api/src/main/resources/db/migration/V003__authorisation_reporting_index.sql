CREATE INDEX intents_by_authorisation ON payment_intents (merchant_id, authorised_at DESC, id DESC)
    WHERE authorised_at IS NOT NULL;
