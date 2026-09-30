package com.hdfc.cern.workfloworch.definition;

import java.util.List;
import lombok.Data;

/**
 * Java shape of one workflow-definition YAML file. Parsed at startup and passed as-is into the
 * workflow as input, so the workflow never touches YAML or the filesystem.
 */
@Data
public class WorkflowDefinition {

	private String name;
	private String description;
	private String start;
	private List<StepDefinition> steps;
}
