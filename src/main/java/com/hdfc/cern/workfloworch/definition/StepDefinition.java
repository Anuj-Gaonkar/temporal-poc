package com.hdfc.cern.workfloworch.definition;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import lombok.Data;

/**
 * One step of a {@link WorkflowDefinition}. Which fields apply depends on {@code type}:
 * <ul>
 *   <li>switch: on, cases, default</li>
 *   <li>activity: activity, params, next</li>
 *   <li>delay: duration, next</li>
 *   <li>end: nothing</li>
 * </ul>
 */
@Data
public class StepDefinition {

	public static final String SWITCH = "switch";
	public static final String ACTIVITY = "activity";
	public static final String DELAY = "delay";
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

	// activity and delay
	private String next;
}
