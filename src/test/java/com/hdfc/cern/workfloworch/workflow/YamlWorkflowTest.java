package com.hdfc.cern.workfloworch.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hdfc.cern.workfloworch.customer.Customer;
import com.hdfc.cern.workfloworch.definition.DefinitionLoader;
import com.hdfc.cern.workfloworch.definition.WorkflowDefinition;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs the shipped YAML through the real workflow in Temporal's time-skipping test environment. */
class YamlWorkflowTest {

	private TestWorkflowEnvironment env;
	private RecordingActivities activities;
	private WorkflowDefinition definition;

	@BeforeEach
	void setUp() {
		DefinitionLoader loader = new DefinitionLoader("workflow-definitions");
		loader.load();
		definition = loader.find("OUTREACH_DEFAULT").orElseThrow();

		env = TestWorkflowEnvironment.newInstance();
		activities = new RecordingActivities(env::currentTimeMillis);
		Worker worker = env.newWorker(OrchTaskQueue.NAME);
		worker.registerWorkflowImplementationTypes(YamlWorkflowImpl.class);
		worker.registerActivitiesImplementations(activities);
		env.start();
	}

	@AfterEach
	void tearDown() {
		env.close();
	}

	@Test
	void voiceCustomerGetsCallThenMessageAfterThirtyMinutes() {
		run(new Customer("C1001", "Test One", "+910000000001", "VOICE"));

		assertThat(activities.calls).extracting(RecordingActivities.Call::activity)
				.containsExactly("executeVoiceCall", "sendMessage");
		long gap = activities.calls.get(1).atMillis() - activities.calls.get(0).atMillis();
		assertThat(gap).isGreaterThanOrEqualTo(Duration.ofMinutes(30).toMillis());
	}

	@Test
	void whatsappCustomerGetsTwoMessagesAndNoCall() {
		run(new Customer("C1002", "Test Two", "+910000000002", "WHATSAPP"));

		assertThat(activities.calls).extracting(RecordingActivities.Call::activity)
				.containsExactly("sendMessage", "sendMessage");
		long gap = activities.calls.get(1).atMillis() - activities.calls.get(0).atMillis();
		assertThat(gap).isGreaterThanOrEqualTo(Duration.ofMinutes(30).toMillis());
	}

	@Test
	void unknownChannelFailsWithClearMessage() {
		assertThatThrownBy(() -> run(new Customer("C9", "Test Nine", "+910000000009", "SMS")))
				.isInstanceOf(WorkflowException.class)
				.rootCause()
				.isInstanceOfSatisfying(ApplicationFailure.class, f -> assertThat(f.getOriginalMessage())
						.isEqualTo("No case for customer.initialChannel=SMS in step 'route'"));
		assertThat(activities.calls).isEmpty();
	}

	private void run(Customer customer) {
		YamlWorkflow workflow = env.getWorkflowClient().newWorkflowStub(YamlWorkflow.class,
				WorkflowOptions.newBuilder()
						.setWorkflowId("OUTREACH_DEFAULT-" + customer.customerId())
						.setTaskQueue(OrchTaskQueue.NAME)
						.build());
		workflow.run(definition, new WorkflowInput(customer));
	}
}
