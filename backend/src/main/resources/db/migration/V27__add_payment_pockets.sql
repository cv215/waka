-- R14 (founder, 30 October 2026): two pockets per person. The credit (top-ups, refunds) is withdrawn at 3 %;
-- the earnings of drivers and sponsors at 2 %. Earnings can also pay a service. No row is inserted here.
ALTER TABLE payment DROP CHECK ck_payment_method;
ALTER TABLE payment ADD CONSTRAINT ck_payment_method CHECK (method IN ('cm.mtn', 'cm.orange', 'credit', 'earnings'));
ALTER TABLE withdrawal ADD COLUMN pocket VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'credit' AFTER method;
ALTER TABLE withdrawal ADD CONSTRAINT ck_withdrawal_pocket CHECK (pocket IN ('credit', 'earnings'));
