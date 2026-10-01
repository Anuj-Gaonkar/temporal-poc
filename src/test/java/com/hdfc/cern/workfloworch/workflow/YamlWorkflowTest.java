package com.hdfc.cern.workfloworch.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.hdfc.cern.workfloworch.customer.Customer;
import com.hdfc.cern.workfloworch.definition.DefinitionLoader;
import com.hdfc.cern.workfloworch.definition.WorkflowDefinition;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runs the shipped YAML through the real workflow in Temporal's time-skipping test environment.
 * Feedback is sent as the signal the REST endpoint would send; nothing here fakes a customer.
 */
class YamlWorkflowTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	private static final Customer VOICE = new Customer("C1001", "Test One", "+910000000001", "VOICE");
	private static final Customer WHATSAPP = new Customer("C1002", "Test Two", "+910000000002", "WHATSAPP");

	private TestWorkflowEnvironment env;
	private RecordingActivities activities;
	private WorkflowDefinition definition;
	private long triggeredAt;

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
	void voiceCustomerIsCalledOnDayOneAtSendHour() {
		YamlWorkflow wf = start(VOICE);
		awaitContacts(1);

		RecordingActivities.Call first = activities.calls.get(0);
		assertThat(first.activity()).isEqualTo("executeVoiceCall");
		assertThat(dayOf(first)).isEqualTo(day0().plusDays(1));
		assertThat(hourOf(first)).isEqualTo(10);

		send(wf, "POSITIVE", null, null);
		finish();
	}

	@Test
	void whatsappCustomerGetsAMessageOnDayOneAndNoCall() {
		YamlWorkflow wf = start(WHATSAPP);
		awaitContacts(1);

		assertThat(activities.calls.get(0).activity()).isEqualTo("sendMessage");
		assertThat(activities.calls.get(0).channel()).isEqualTo("WHATSAPP");
		assertThat(dayOf(activities.calls.get(0))).isEqualTo(day0().plusDays(1));

		send(wf, "POSITIVE", null, null);
		finish();
		assertThat(activities.calls).extracting(RecordingActivities.Call::activity).doesNotContain("executeVoiceCall");
	}

	@Test
	void positiveFeedbackSendsThankYouSmsAndEnds() {
		YamlWorkflow wf = start(VOICE);
		awaitContacts(1);
		send(wf, "POSITIVE", null, null);
		finish();

		assertThat(activities.calls).extracting(RecordingActivities.Call::channel).containsExactly("VOICE", "SMS");
	}

	@Test
	void reminderUsesCustomersTimeAndChannelThenRepeatsWithTheSameWhenUnanswered() {
		YamlWorkflow wf = start(VOICE);
		awaitContacts(1);
		send(wf, "REMIND_LATER", "2d", "WHATSAPP");
		awaitContacts(2);

		RecordingActivities.Call first = activities.calls.get(0);
		RecordingActivities.Call reminder = activities.calls.get(1);
		assertThat(reminder.channel()).isEqualTo("WHATSAPP");
		assertThat(dayOf(reminder)).isEqualTo(dayOf(first).plusDays(2));
		assertThat(hourOf(reminder)).isEqualTo(10);

		// no feedback to the reminder: reminded again, same channel, same gap
		awaitContacts(3);
		RecordingActivities.Call again = activities.calls.get(2);
		assertThat(again.channel()).isEqualTo("WHATSAPP");
		assertThat(dayOf(again)).isEqualTo(dayOf(reminder).plusDays(2));

		send(wf, "POSITIVE", null, null);
		finish();
	}

	@Test
	void reminderWithoutDetailsDefaultsToOneDayOnTheDatabaseChannel() {
		YamlWorkflow wf = start(WHATSAPP);
		awaitContacts(1);
		send(wf, "REMIND_LATER", null, null);
		awaitContacts(2);

		assertThat(activities.calls.get(1).channel()).isEqualTo("WHATSAPP");
		assertThat(dayOf(activities.calls.get(1))).isEqualTo(dayOf(activities.calls.get(0)).plusDays(1));

		send(wf, "POSITIVE", null, null);
		finish();
	}

	@Test
	void noResponseRetriesTheNextDayOnTheDatabaseChannel() {
		YamlWorkflow wf = start(VOICE);
		awaitContacts(2); // the first contact gets no feedback at all: the wait times out

		assertThat(activities.calls).extracting(RecordingActivities.Call::channel).containsExactly("VOICE", "VOICE");
		assertThat(dayOf(activities.calls.get(1))).isEqualTo(dayOf(activities.calls.get(0)).plusDays(1));

		send(wf, "POSITIVE", null, null);
		finish();
	}

	@Test
	void explicitNoResponseFeedbackAlsoRetriesTheNextDay() {
		YamlWorkflow wf = start(WHATSAPP);
		awaitContacts(1);
		send(wf, "NO_RESPONSE", null, null);
		awaitContacts(2);

		assertThat(dayOf(activities.calls.get(1))).isEqualTo(dayOf(activities.calls.get(0)).plusDays(1));
		send(wf, "POSITIVE", null, null);
		finish();
	}

	@Test
	void inconclusiveFollowsUpOnTheChannelTheCustomerAnsweredOn() {
		YamlWorkflow wf = start(VOICE);
		awaitContacts(1);
		send(wf, "REMIND_LATER", "1d", "WHATSAPP"); // the reminder goes out on WhatsApp ...
		awaitContacts(2);
		send(wf, "INCONCLUSIVE", null, null); // ... customer answers there without a conclusion
		awaitContacts(3);

		assertThat(activities.calls.get(2).channel()).isEqualTo("WHATSAPP");
		assertThat(dayOf(activities.calls.get(2))).isEqualTo(dayOf(activities.calls.get(1)).plusDays(1));

		send(wf, "POSITIVE", null, null);
		finish();
	}

	@Test
	void reminderBeyondDayFifteenIsNotSentAndTheWorkflowEnds() {
		YamlWorkflow wf = start(VOICE);
		awaitContacts(1);
		send(wf, "REMIND_LATER", "20d", "WHATSAPP");
		finish();

		assertThat(activities.calls).hasSize(1);
	}

	@Test
	void reminderThatLandsOnDayFifteenIsStillSent() {
		YamlWorkflow wf = start(VOICE);
		awaitContacts(1); // Day 1
		send(wf, "REMIND_LATER", "14d", "WHATSAPP"); // Day 15
		finish();

		assertThat(activities.calls).hasSize(2);
		assertThat(dayOf(activities.calls.get(1))).isEqualTo(day0().plusDays(15));
	}

	@Test
	void withNeverAnyFeedbackTheCustomerIsContactedEveryDayUntilDayFifteenAndNeverAfter() {
		start(VOICE);
		finish();

		assertThat(activities.calls).hasSize(15);
		assertThat(dayOf(activities.calls.get(0))).isEqualTo(day0().plusDays(1));
		assertThat(dayOf(activities.calls.get(14))).isEqualTo(day0().plusDays(15));
	}

	@Test
	void feedbackSentBeforeAnyContactIsDropped() {
		YamlWorkflow wf = start(VOICE);
		send(wf, "POSITIVE", null, null); // Day 0: no contact has been made, so this answers nothing
		awaitContacts(2);

		// the first contact was not ended by that stale POSITIVE: it went unanswered and was retried
		assertThat(activities.calls).extracting(RecordingActivities.Call::channel).containsExactly("VOICE", "VOICE");
		send(wf, "POSITIVE", null, null);
		finish();
	}

	private YamlWorkflow start(Customer customer) {
		triggeredAt = env.currentTimeMillis();
		workflowId = "OUTREACH_DEFAULT-" + customer.customerId();
		YamlWorkflow workflow = env.getWorkflowClient().newWorkflowStub(YamlWorkflow.class,
				WorkflowOptions.newBuilder()
						.setWorkflowId(workflowId)
						.setTaskQueue(OrchTaskQueue.NAME)
						.build());
		WorkflowClient.start(workflow::run, definition, new WorkflowInput(customer));
		return workflow;
	}

	private static void send(YamlWorkflow workflow, String type, String remindAfter, String channel) {
		workflow.feedback(new FeedbackSignal(type, remindAfter, channel));
	}

	/** Moves test time forward in small steps until the n-th contact has happened. */
	private void awaitContacts(int n) {
		for (int i = 0; i < 40 * 24 * 6 && activities.calls.size() < n; i++) {
			env.sleep(Duration.ofMinutes(10));
		}
		assertThat(activities.calls).hasSizeGreaterThanOrEqualTo(n);
		// Let the workflow get from the contact to its feedback wait before the test sends feedback.
		env.sleep(Duration.ofSeconds(1));
	}

	/** Runs the workflow to its end (time skipping does the waiting). */
	private void finish() {
		env.getWorkflowClient().newUntypedWorkflowStub(workflowId).getResult(Void.class);
	}

	private String workflowId;

	private LocalDate day0() {
		return Instant.ofEpochMilli(triggeredAt).atZone(IST).toLocalDate();
	}

	private static LocalDate dayOf(RecordingActivities.Call call) {
		return Instant.ofEpochMilli(call.atMillis()).atZone(IST).toLocalDate();
	}

	private static int hourOf(RecordingActivities.Call call) {
		return Instant.ofEpochMilli(call.atMillis()).atZone(IST).getHour();
	}
}
