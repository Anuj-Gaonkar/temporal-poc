package com.hdfc.cern.workfloworch.definition;

import com.hdfc.cern.workfloworch.customer.Customer;
import java.util.Set;

/**
 * The only paths a switch {@code on} accepts in Phase 1: fields of the customer snapshot. No
 * expressions. Kept small and separate so Phase 2 can add step results here.
 */
public final class CustomerPaths {

	public static final Set<String> SUPPORTED = Set.of(
			"customer.customerId", "customer.name", "customer.phone", "customer.initialChannel");

	private CustomerPaths() {
	}

	public static String resolve(String path, Customer customer) {
		return switch (path) {
			case "customer.customerId" -> customer.customerId();
			case "customer.name" -> customer.name();
			case "customer.phone" -> customer.phone();
			case "customer.initialChannel" -> customer.initialChannel();
			default -> throw new IllegalArgumentException("Unsupported path '" + path + "'");
		};
	}
}
