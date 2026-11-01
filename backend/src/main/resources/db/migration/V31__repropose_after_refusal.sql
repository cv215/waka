-- Revised R15 (1 November 2026): a refusal is not final. A client still searching may be proposed again to the same
-- driver a minute after his refusal, so one search may hold several proposals to one driver.
ALTER TABLE ride_proposal ADD INDEX ix_ride_proposal_search_driver (gate_hash, search_id, driver_account_id);
ALTER TABLE ride_proposal DROP INDEX uq_ride_proposal_driver;
