package com.hdfc.cern.workfloworch.definition;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import lombok.Data;

/**
 * One step of a {@link WorkflowDefinition}. Which fields apply depends on {@code type}:
 * <ul>
 *   <li>switch: on, cases, default</li>
 *   <li>activity: activity, params, next</li>
 *   <li>delay: one of duration, fromVar (+ defaultDuration) or untilDay; next</li>
 *   <li>contact: channel, defaultChannel, text, next</li>
 *   <li>await-feedback: timeout, next</li>
 *   <li>end: nothing</li>
 * </ul>
 */
@Data
public class StepDefinition {

	public static final String SWITCH = "switch";
	public static final String ACTIVITY = "activity";
	public static final String DELAY = "delay";
	public static final String CONTACT = "contact";
	public static final String AWAIT_FEEDBACK = "await-feedback";
	public static final String END = "end";

	private String id;
	/** Kept as text so an unknown type is reported by the validator, not by the YAML parser. */
	private String type;

	// switch
	private String on;
	private Map<String, String> cases;
	@JsonProperty("default")
	private String defaultNext;

	// activity
	private String activity;
	private Map<String, String> params;

	// delay
	private String duration;
	/** Read the duration from a run variable (reminder.after, feedback.remindAfter). */
	private String fromVar;
	/** Used when {@code fromVar} has no value. */
	private String defaultDuration;
	/** Wait until Day N of the campaign at the send hour (needs a campaign block). */
	private Integer untilDay;

	// contact: a literal channel (VOICE, WHATSAPP, SMS) or a path such as reminder.channel
	private String channel;
	/** Used when {@code channel} is a path with no value. */
	private String defaultChannel;
	/** Script (VOICE) or message text (WHATSAPP, SMS). */
	private String text;

	// await-feedback: how long to wait for the customer's feedback before it counts as NO_RESPONSE
	private String timeout;

	// activity, delay, contact and await-feedback
	private String next;
}
