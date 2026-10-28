package com.waka.operations.application;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DatabaseHealthService {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseHealthService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isAvailable() {
        try {
            return Integer.valueOf(1).equals(jdbcTemplate.queryForObject("SELECT 1", Integer.class));
        } catch (DataAccessException exception) {
            // Le controle HTTP ne divulgue pas la cause, les identifiants ou l'URL de connexion.
            return false;
        }
    }
}
