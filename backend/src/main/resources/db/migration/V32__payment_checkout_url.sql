-- MoneyFusion (23 November 2026): the payer confirms on the provider's own payment page, not on a push to the phone.
-- The page address is kept with the payment so the app can open it again while the payment is pending.
ALTER TABLE payment ADD COLUMN checkout_url VARCHAR(300) CHARACTER SET ascii COLLATE ascii_bin NULL;
