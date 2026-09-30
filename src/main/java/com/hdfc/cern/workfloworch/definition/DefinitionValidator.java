package com.hdfc.cern.workfloworch.definition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Checks one parsed definition and returns every problem found (empty list = valid). */
public final class DefinitionValidator {

	private static final Set<String> TYPES = Set.of(
			StepDefinition.SWITCH, StepDefinition.ACTIVITY, StepDefinition.DELAY, StepDefinition.END);

	private DefinitionValidator() {
	}

	public static List<String> validate(WorkflowDefinition def, Set<String> knownActivities) {
		List<String> errors = new ArrayList<>();
		if (isBlank(def.getName())) {
			errors.add("'name' is required");
		}
		if (isBlank(def.getStart())) {
			errors.add("'start' is required");
		}
		if (def.getSteps() == null || def.getSteps().isEmpty()) {
			errors.add("'steps' is required and must not be empty");
			return errors;
		}

		Map<String, StepDefinition> byId = new HashMap<>();
		for (StepDefinition step : def.getSteps()) {
			if (step == null || isBlank(step.getId())) {
				errors.add("every step needs an 'id'");
			} else if (byId.put(step.getId(), step) != null) {
				errors.add("duplicate step id '" + step.getId() + "'");
			}
		}
		if (!isBlank(def.getStart()) && !byId.containsKey(def.getStart())) {
			errors.add("start step '" + def.getStart() + "' does not exist");
		}

		for (StepDefinition step : def.getSteps()) {
			if (step != null && !isBlank(step.getId())) {
				checkStep(step, byId, knownActivities, errors);
			}
		}

		if (errors.isEmpty()) {
			checkReachable(def, byId, errors);
		}
		return errors;
	}

	private static void checkStep(StepDefinition step, Map<String, StepDefinition> byId, Set<String> knownActivities,
			List<String> errors) {
		String where = "step '" + step.getId() + "': ";
		if (isBlank(step.getType())) {
			errors.add(where + "'type' is required");
			return;
		}
		if (!TYPES.contains(step.getType())) {
			errors.add(where + "unknown step type '" + step.getType() + "' (use switch, activity, delay or end)");
			return;
		}
		switch (step.getType()) {
			case StepDefinition.SWITCH -> {
				if (isBlank(step.getOn())) {
					errors.add(where + "switch needs 'on'");
				} else if (!CustomerPaths.SUPPORTED.contains(step.getOn())) {
					errors.add(where + "unsupported 'on' path '" + step.getOn() + "' (use one of "
							+ new java.util.TreeSet<>(CustomerPaths.SUPPORTED) + ")");
				}
				boolean hasCases = step.getCases() != null && !step.getCases().isEmpty();
				if (!hasCases && isBlank(step.getDefaultNext())) {
					errors.add(where + "switch needs 'cases' or 'default'");
				}
				if (hasCases) {
					step.getCases().forEach((value, target) -> checkTarget(where + "case '" + value + "'", target, byId, errors));
				}
				if (!isBlank(step.getDefaultNext())) {
					checkTarget(where + "default", step.getDefaultNext(), byId, errors);
				}
			}
			case StepDefinition.ACTIVITY -> {
				if (isBlank(step.getActivity())) {
					errors.add(where + "activity step needs 'activity'");
				} else if (!knownActivities.contains(step.getActivity())) {
					errors.add(where + "unknown activity '" + step.getActivity() + "' (known: "
							+ new java.util.TreeSet<>(knownActivities) + ")");
				}
				checkNext(where, step, byId, errors);
			}
			case StepDefinition.DELAY -> {
				try {
					DurationParser.parse(step.getDuration());
				} catch (IllegalArgumentException e) {
					errors.add(where + "bad duration: " + e.getMessage());
				}
				checkNext(where, step, byId, errors);
			}
			default -> {
				// end: nothing to check
			}
		}
	}

	private static void checkNext(String where, StepDefinition step, Map<String, StepDefinition> byId, List<String> errors) {
		if (isBlank(step.getNext())) {
			errors.add(where + "'next' is required (only 'end' steps have no way forward)");
		} else {
			checkTarget(where + "next", step.getNext(), byId, errors);
		}
	}

	private static void checkTarget(String what, String target, Map<String, StepDefinition> byId, List<String> errors) {
		if (isBlank(target)) {
			errors.add(what + " has an empty target");
		} else if (!byId.containsKey(target)) {
			errors.add(what + " points to unknown step '" + target + "'");
		}
	}

	/** A step nobody can reach is almost certainly a typo, so it is an error, not a warning. */
	private static void checkReachable(WorkflowDefinition def, Map<String, StepDefinition> byId, List<String> errors) {
		Set<String> seen = new HashSet<>();
		java.util.ArrayDeque<String> todo = new java.util.ArrayDeque<>();
		todo.add(def.getStart());
		while (!todo.isEmpty()) {
			String id = todo.poll();
			if (!seen.add(id)) {
				continue;
			}
			StepDefinition step = byId.get(id);
			if (step.getCases() != null) {
				todo.addAll(step.getCases().values());
			}
			for (String target : new String[] { step.getDefaultNext(), step.getNext() }) {
				if (!isBlank(target)) {
					todo.add(target);
				}
			}
		}
		for (StepDefinition step : def.getSteps()) {
			if (!seen.contains(step.getId())) {
				errors.add("step '" + step.getId() + "' is not reachable from start '" + def.getStart() + "'");
			}
		}
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}
}
