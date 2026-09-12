package com.example.banking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.banking.support.IntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The application's database role (created by migration V3) may read and write business data but can neither rewrite
 * the ledger nor change the schema, so a compromised or buggy application cannot either.
 */
@IntegrationTest
class DatabasePrivilegesIT {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PostgreSQLContainer postgres;

    @Test
    void applicationRoleCannotRewriteTheLedgerOrChangeTheSchema() throws SQLException {
        // In RDS the role signs in with IAM tokens; a password is only set here to connect as it.
        jdbc.execute("ALTER ROLE banking_app WITH PASSWORD 'test-only-password'");

        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), "banking_app", "test-only-password");
             Statement statement = connection.createStatement()) {
            assertThatNoException().isThrownBy(() -> statement.executeQuery("SELECT count(*) FROM account_transactions"));
            assertThatNoException().isThrownBy(() -> statement.executeQuery("SELECT count(*) FROM scheduled_payments"));

            assertInsufficientPrivilege(statement, "UPDATE account_transactions SET amount = amount + 1");
            assertInsufficientPrivilege(statement, "DELETE FROM account_transactions");
            assertInsufficientPrivilege(statement, "DELETE FROM accounts");
            assertInsufficientPrivilege(statement, "CREATE TABLE shadow_ledger (id int)");
            assertInsufficientPrivilege(statement, "DROP TABLE accounts");
        }
    }

    private static void assertInsufficientPrivilege(Statement statement, String sql) {
        assertThatThrownBy(() -> statement.execute(sql))
                .as(sql)
                .isInstanceOfSatisfying(SQLException.class,
                        e -> assertThat(e.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
    }
}
