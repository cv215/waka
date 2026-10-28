-- R03/R04 : configuration adoptee, pas des comptes ni des transactions de demonstration.
-- launch-v1 reste la reference immuable des futurs calculs ; changement = nouvelle version.
-- Aucune date d'activation commerciale : cette grille est preparee, pas mise en vente.
CREATE TABLE fare_schedule (
    version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (version),
    CONSTRAINT chk_fare_schedule_currency CHECK (currency = 'XAF')
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE fare_band (
    schedule_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    band_code CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    lower_metres INT NOT NULL,
    upper_metres INT NOT NULL,
    upper_inclusive BOOLEAN NOT NULL,
    unit_price_xaf INT NOT NULL,
    PRIMARY KEY (schedule_version, band_code),
    UNIQUE KEY uk_fare_band_lower (schedule_version, lower_metres),
    CONSTRAINT fk_fare_band_schedule FOREIGN KEY (schedule_version)
        REFERENCES fare_schedule (version) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_fare_band_range CHECK (
        lower_metres >= 0 AND upper_metres > lower_metres AND upper_metres <= 17900),
    CONSTRAINT chk_fare_band_inclusive CHECK (
        (upper_inclusive = 0 AND upper_metres < 17900)
        OR (upper_inclusive = 1 AND upper_metres = 17900)),
    CONSTRAINT chk_fare_band_price CHECK (unit_price_xaf > 0)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

INSERT INTO fare_schedule (version, currency) VALUES ('launch-v1', 'XAF');
INSERT INTO fare_band
    (schedule_version, band_code, lower_metres, upper_metres, upper_inclusive, unit_price_xaf)
VALUES
    ('launch-v1', 'B01', 0, 1000, 0, 150),
    ('launch-v1', 'B02', 1000, 1900, 0, 205),
    ('launch-v1', 'B03', 1900, 2700, 0, 260),
    ('launch-v1', 'B04', 2700, 3600, 0, 315),
    ('launch-v1', 'B05', 3600, 4400, 0, 370),
    ('launch-v1', 'B06', 4400, 5300, 0, 430),
    ('launch-v1', 'B07', 5300, 6100, 0, 485),
    ('launch-v1', 'B08', 6100, 7000, 0, 540),
    ('launch-v1', 'B09', 7000, 7800, 0, 600),
    ('launch-v1', 'B10', 7800, 8600, 0, 655),
    ('launch-v1', 'B11', 8600, 9400, 0, 715),
    ('launch-v1', 'B12', 9400, 10300, 0, 770),
    ('launch-v1', 'B13', 10300, 11100, 0, 830),
    ('launch-v1', 'B14', 11100, 12200, 0, 890),
    ('launch-v1', 'B15', 12200, 13400, 0, 950),
    ('launch-v1', 'B16', 13400, 14500, 0, 1010),
    ('launch-v1', 'B17', 14500, 15600, 0, 1070),
    ('launch-v1', 'B18', 15600, 16800, 0, 1130),
    ('launch-v1', 'B19', 16800, 17900, 1, 1200);
