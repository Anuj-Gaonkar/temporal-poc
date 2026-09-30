package com.hdfc.cern.workfloworch.customer;

/** One row of orch.customers. Also the customer snapshot handed to the workflow as input. */
public record Customer(String customerId, String name, String phone, String initialChannel) {
}
