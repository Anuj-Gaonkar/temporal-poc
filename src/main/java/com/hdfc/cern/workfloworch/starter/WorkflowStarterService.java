package com.hdfc.cern.workfloworch.starter;

import static com.hdfc.cern.workfloworch.logging.LogEvents.line;

import com.hdfc.cern.workfloworch.customer.Customer;
import com.hdfc.cern.workfloworch.customer.CustomerRepository;
import com.hdfc.cern.workfloworch.definition.DefinitionLoader;
import com.hdfc.cern.workfloworch.definition.WorkflowDefinition;
import com.hdfc.cern.workfloworch.logging.LogEvents;
import com.hdfc.cern.workfloworch.starter.StartRejectedException.Reason;
import com.hdfc.cern.workfloworch.workflow.OrchTaskQueue;
import com.hdfc.cern.workfloworch.workflow.WorkflowInput;
import com.hdfc.cern.workfloworch.workflow.YamlWorkflow;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Starts one workflow for one customer: check the definition, read the customer snapshot, start.
 * Workflow id is {@code <definition>-<customerId>}, so a second start while it runs is refused.
 * Also writes the API_START_* timing log lines.
 */
@Service
@Slf4j
public class WorkflowStarterService {

	private final WorkflowClient workflowClient;
	private final DefinitionLoader definitions;
	private final CustomerRepository customers;

	public WorkflowStarterService(WorkflowClient workflowClient, DefinitionLoader definitions, CustomerRepository customers) {
		this.workflowClient = workflowClient;
		this.definitions = definitions;
		this.customers = customers;
	}

	public StartResult start(String customerId, String definitionName) {
		long startedNanos = System.nanoTime();
		String workflowId = null;
		try {
			if (customerId != null) {
				MDC.put("customerId", customerId);
			}
			log.info(line(LogEvents.API_START_RECEIVED, "customerId", customerId, "definition", definitionName));

			if (isBlank(customerId) || isBlank(definitionName)) {
				throw new StartRejectedException(Reason.BAD_REQUEST, "customerId and definition are required");
			}
			WorkflowDefinition definition = definitions.find(definitionName).orElseThrow(() ->
					new StartRejectedException(Reason.DEFINITION_NOT_FOUND, "No workflow definition named '" + definitionName + "'"));

			long lookupStart = System.nanoTime();
			Customer customer = customers.findById(customerId).orElseThrow(() ->
					new StartRejectedException(Reason.CUSTOMER_NOT_FOUND, "No customer with id '" + customerId + "'"));
			long lookupMs = elapsedMs(lookupStart);

			workflowId = definition.getName() + "-" + customerId;
			MDC.put("workflowId", workflowId);
			long startStart = System.nanoTime();
			WorkflowExecution execution = startWorkflow(workflowId, definition, customer);
			long startMs = elapsedMs(startStart);

			log.info(line(LogEvents.API_START_DONE, "workflowId", workflowId, "runId", execution.getRunId(),
					"customerId", customerId, "channel", customer.initialChannel(),
					"lookupMs", lookupMs, "startMs", startMs, "totalMs", elapsedMs(startedNanos)));
			return new StartResult(workflowId, execution.getRunId(), customerId, customer.initialChannel());
		} catch (StartRejectedException e) {
			log.info(line(LogEvents.API_START_REJECTED, "customerId", customerId, "definition", definitionName,
					"reason", e.reason(), "totalMs", elapsedMs(startedNanos)));
			throw e;
		} finally {
			MDC.remove("customerId");
			MDC.remove("workflowId");
		}
	}

	private WorkflowExecution startWorkflow(String workflowId, WorkflowDefinition definition, Customer customer) {
		YamlWorkflow workflow = workflowClient.newWorkflowStub(YamlWorkflow.class,
				WorkflowOptions.newBuilder().setWorkflowId(workflowId).setTaskQueue(OrchTaskQueue.NAME).build());
		try {
			return WorkflowClient.start(workflow::run, definition, new WorkflowInput(customer));
		} catch (WorkflowExecutionAlreadyStarted e) {
			throw new StartRejectedException(Reason.ALREADY_RUNNING, "A workflow '" + workflowId + "' is already running");
		}
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}

	private static long elapsedMs(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000;
	}
}
