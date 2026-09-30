package com.hdfc.cern.workfloworch.definition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefinitionLoaderTest {

	@TempDir
	Path dir;

	@Test
	void shippedYamlLoads() {
		DefinitionLoader loader = new DefinitionLoader("workflow-definitions");
		loader.load();

		WorkflowDefinition def = loader.find("OUTREACH_DEFAULT").orElseThrow();
		assertThat(def.getStart()).isEqualTo("route");
		assertThat(def.getSteps()).extracting(StepDefinition::getId)
				.containsExactly("route", "voice-call", "whatsapp-message", "wait-30-min", "follow-up-message", "end");
	}

	@Test
	void duplicateStepIdFails() throws IOException {
		assertInvalid(valid().replace("id: whatsapp-message", "id: voice-call"), "duplicate step id 'voice-call'");
	}

	@Test
	void unknownStepTypeFails() throws IOException {
		assertInvalid(valid().replace("type: delay", "type: sleep"), "unknown step type 'sleep'");
	}

	@Test
	void unknownActivityFails() throws IOException {
		assertInvalid(valid().replace("activity: executeVoiceCall", "activity: fax"), "unknown activity 'fax'");
	}

	@Test
	void badDurationFails() throws IOException {
		assertInvalid(valid().replace("duration: 30m", "duration: 30"), "bad duration");
	}

	@Test
	void danglingNextFails() throws IOException {
		assertInvalid(valid().replace("next: follow-up-message", "next: nowhere"), "points to unknown step 'nowhere'");
	}

	@Test
	void danglingCaseTargetFails() throws IOException {
		assertInvalid(valid().replace("VOICE: voice-call", "VOICE: nowhere"), "case 'VOICE' points to unknown step 'nowhere'");
	}

	@Test
	void missingStartStepFails() throws IOException {
		assertInvalid(valid().replace("start: route", "start: nowhere"), "start step 'nowhere' does not exist");
	}

	@Test
	void unreachableStepFails() throws IOException {
		assertInvalid(valid() + "  - id: orphan\n    type: end\n", "step 'orphan' is not reachable");
	}

	@Test
	void activityWithoutNextFails() throws IOException {
		assertInvalid(valid().replace("    next: wait-30-min\n\n  - id: whatsapp-message", "\n  - id: whatsapp-message"),
				"step 'voice-call': 'next' is required");
	}

	@Test
	void unsupportedSwitchPathFails() throws IOException {
		assertInvalid(valid().replace("on: customer.initialChannel", "on: voice-call.outcome"), "unsupported 'on' path");
	}

	@Test
	void errorNamesTheFile() throws IOException {
		assertInvalid(valid().replace("type: delay", "type: sleep"), "Invalid workflow definition bad.yaml");
	}

	private void assertInvalid(String yaml, String expectedMessagePart) throws IOException {
		Files.writeString(dir.resolve("bad.yaml"), yaml);
		DefinitionLoader loader = new DefinitionLoader(dir.toString());
		assertThatThrownBy(loader::load)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(expectedMessagePart);
	}

	private static String valid() throws IOException {
		return Files.readString(Path.of("workflow-definitions/outreach-default.yaml"));
	}
}
