package com.hdfc.cern.workfloworch.activity;

import static com.hdfc.cern.workfloworch.logging.LogEvents.line;

import com.hdfc.cern.workfloworch.logging.ConversationLog;
import com.hdfc.cern.workfloworch.logging.LogEvents;
import com.hdfc.cern.workfloworch.workflow.OrchTaskQueue;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInfo;
import io.temporal.spring.boot.ActivityImpl;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
@ActivityImpl(taskQueues = OrchTaskQueue.NAME)
@Slf4j
public class OrchActivitiesImpl implements OrchActivities {

	/** Readable "what we sent" lines; also written to logs/conversation.log. */
	private static final org.slf4j.Logger conversation = org.slf4j.LoggerFactory.getLogger(ConversationLog.LOGGER);

	@Override
	public void executeVoiceCall(ActivityInput input) {
		run(OrchActivities.EXECUTE_VOICE_CALL, input, () -> conversation.info(ConversationLog.contact(
				"VOICE", input.customer().customerId(), input.customer().phone(), input.stepId(),
				System.currentTimeMillis(), param(input, "script"))));
	}

	@Override
	public void sendMessage(ActivityInput input) {
		run(OrchActivities.SEND_MESSAGE, input, () -> conversation.info(ConversationLog.contact(
				param(input, "channel"), input.customer().customerId(), input.customer().phone(), input.stepId(),
				System.currentTimeMillis(), param(input, "text"))));
	}

	private void run(String name, ActivityInput input, Runnable body) {
		ActivityInfo info = Activity.getExecutionContext().getInfo();
		long startedNanos = System.nanoTime();
		MDC.put("workflowId", info.getWorkflowId());
		MDC.put("customerId", input.customer().customerId());
		try {
			long scheduleToStartMs = System.currentTimeMillis() - info.getScheduledTimestamp();
			log.info(line(LogEvents.ACT_START, "workflowId", info.getWorkflowId(), "stepId", input.stepId(),
					"activity", name, "attempt", info.getAttempt(), "scheduleToStartMs", scheduleToStartMs));
			try {
				body.run();
			} catch (RuntimeException e) {
				log.error(line(LogEvents.ACT_FAILED, "workflowId", info.getWorkflowId(), "stepId", input.stepId(),
						"activity", name, "attempt", info.getAttempt(), "execMs", elapsedMs(startedNanos),
						"error", e.toString()));
				throw e;
			}
			log.info(line(LogEvents.ACT_DONE, "workflowId", info.getWorkflowId(), "stepId", input.stepId(),
					"activity", name, "attempt", info.getAttempt(), "execMs", elapsedMs(startedNanos)));
		} finally {
			MDC.remove("workflowId");
			MDC.remove("customerId");
		}
	}

	private static String param(ActivityInput input, String key) {
		Map<String, String> params = input.params();
		return params == null ? null : params.get(key);
	}

	private static long elapsedMs(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000;
	}
}
