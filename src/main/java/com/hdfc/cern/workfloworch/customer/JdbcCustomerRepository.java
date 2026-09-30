package com.hdfc.cern.workfloworch.customer;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCustomerRepository implements CustomerRepository {

	private final JdbcClient jdbc;

	public JdbcCustomerRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<Customer> findById(String customerId) {
		return jdbc.sql("""
				SELECT customer_id, name, phone, initial_channel
				FROM orch.customers
				WHERE customer_id = :id
				""")
				.param("id", customerId)
				.query((rs, rowNum) -> new Customer(
						rs.getString("customer_id"),
						rs.getString("name"),
						rs.getString("phone"),
						rs.getString("initial_channel")))
				.optional();
	}
}
