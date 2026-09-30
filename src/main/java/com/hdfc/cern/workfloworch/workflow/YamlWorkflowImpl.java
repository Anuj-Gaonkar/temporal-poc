package com.hdfc.cern.workfloworch.workflow;

import static com.hdfc.cern.workfloworch.logging.LogEvents.line;

import com.hdfc.cern.workfloworch.activity.ActivityInput;
import com.hdfc.cern.workfloworch.activity.OrchActivities;
import com.hdfc.cern.workfloworch.customer.Customer;
import com.hdfc.cern.workfloworch.definition.CustomerPaths;
import com.hdfc.cern.workfloworch.definition.DurationParser;
import com.hdfc.cern.workfloworch.definition.StepDefinition;
import com.hdfc.cern.workfloworch.definition.WorkflowDefinition;
import com.hdfc.cern.workfloworch.logging.LogEvents;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;

/**
 * Interprets a {@link WorkflowDefinition} with a step pointer: start at the first step, each step
 * decides the next step id, stop at an {@code end} step (the workflow completes normally).
 *
 * <p>Deterministic: no clock, random, IO or database. Time comes from {@link Workflow#currentTimeMillis()},
 * log lines are written only when not replaying.
 */
@WorkflowImpl(taskQueues = OrchTaskQueue.NAME)
public class YamlWorkflowImpl implements YamlWorkflow {

	private static final int MAX_TRANSITIONS = 100;

	private final Logger log = Workflow.getLogger(YamlWorkflowImpl.class);

	private final OrchActivities activities = Workflow.newActivityStub(
			OrchActivities.class,
			ActivityOptions.newBuilder()
					.setStartToCloseTimeout(Duration.ofSeconds(30))
					.setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
					.build());

	@Override
	public void run(WorkflowDefinition definition, WorkflowInput input) {
		String workflowId = Workflow.getInfo().getWorkflowId();
		Customer customer = input.customer();
		long startedAt = Workflow.currentTimeMillis();
		long startLagMs = startedAt - Workflow.getInfo().getRunStartedTimestampMillis();
		info(line(LogEvents.WF_STARTED, "workflowId", workflowId, "customerId", customer.customerId(),
				"channel", customer.initialChannel(), "definition", definition.getName(), "startLagMs", startLagMs));

		try {
			interpret(definition, customer, workflowId);
		} catch (RuntimeException e) {
			if (!Workflow.isReplaying()) {
				log.error(line(LogEvents.WF_FAILED, "workflowId", workflowId, "customerId", customer.customerId(),
						"totalMs", Workflow.currentTimeMillis() - startedAt, "error", rootMessage(e)));
			}
			throw e;
		}
		info(line(LogEvents.WF_COMPLETED, "workflowId", workflowId, "customerId", customer.customerId(),
				"totalMs", Workflow.currentTimeMillis() - startedAt));
	}

	private void interpret(WorkflowDefinition definition, Customer customer, String workflowId) {
		Map<String, StepDefinition> steps = new HashMap<>();
		definition.getSteps().forEach(step -> steps.put(step.getId(), step));

		String current = definition.getStart();
		int transitions = 0;
		while (true) {
			if (++transitions > MAX_TRANSITIONS) {
				throw fail("More than " + MAX_TRANSITIONS + " transitions - check the definition for a loop", "TooManyTransitions");
			}
			StepDefinition step = steps.get(current);
			if (step == null) {
				throw fail("Unknown step id '" + current + "'", "UnknownStep");
			}

			long enteredAt = Workflow.currentTimeMillis();
			info(line(LogEvents.WF_STEP_ENTER, "workflowId", workflowId, "stepId", step.getId(), "stepType", step.getType()));
			String next = execute(step, customer);
			info(line(LogEvents.WF_STEP_EXIT, "workflowId", workflowId, "stepId", step.getId(), "stepType", step.getType(),
					"stepMs", Workflow.currentTimeMillis() - enteredAt));
			if (next == null) {
				return;
			}
			current = next;
		}
	}

	/** Runs one step and returns the next step id, or null when the workflow has ended. */
	private String execute(StepDefinition step, Customer customer) {
		switch (step.getType()) {
			case StepDefinition.SWITCH -> {
				String value = CustomerPaths.resolve(step.getOn(), customer);
				String target = step.getCases() == null ? null : step.getCases().get(value);
				if (target == null) {
					target = step.getDefaultNext();
				}
				if (target == null) {
					throw fail("No case for " + step.getOn() + "=" + value + " in step '" + step.getId() + "'", "NoCase");
				}
				return target;
			}
			case StepDefinition.ACTIVITY -> {
				ActivityInput in = new ActivityInput(step.getId(), customer, step.getParams());
				switch (step.getActivity()) {
					case OrchActivities.EXECUTE_VOICE_CALL -> activities.executeVoiceCall(in);
					case OrchActivities.SEND_MESSAGE -> activities.sendMessage(in);
					default -> throw fail("Unknown activity '" + step.getActivity() + "' in step '" + step.getId() + "'", "UnknownActivity");
				}
				return step.getNext();
			}
			case StepDefinition.DELAY -> {
				Workflow.sleep(DurationParser.parse(step.getDuration()));
				return step.getNext();
			}
			case StepDefinition.END -> {
				return null;
			}
			default -> throw fail("Unknown step type '" + step.getType() + "' in step '" + step.getId() + "'", "UnknownStepType");
		}
	}

	private void info(String message) {
		if (!Workflow.isReplaying()) {
			log.info(message);
		}
	}

	private static ApplicationFailure fail(String message, String type) {
		return ApplicationFailure.newNonRetryableFailure(message, type);
	}

	private static String rootMessage(Throwable t) {
		if (t instanceof ApplicationFailure failure) {
			return failure.getOriginalMessage();
		}
		return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
	}
}
