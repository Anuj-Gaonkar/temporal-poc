package com.hdfc.cern.workfloworch.definition;

import static com.hdfc.cern.workfloworch.logging.LogEvents.line;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.hdfc.cern.workfloworch.activity.OrchActivities;
import com.hdfc.cern.workfloworch.logging.LogEvents;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads every {@code *.yaml} in the definitions folder once at startup and validates it. A broken
 * file stops the app with a message naming the file and the problems. No hot reload in Phase 1.
 */
@Component
@Slf4j
public class DefinitionLoader {

	private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
	private final Path folder;
	private final Map<String, WorkflowDefinition> definitions = new LinkedHashMap<>();

	public DefinitionLoader(@Value("${orch.definitions-path:./workflow-definitions}") String definitionsPath) {
		this.folder = Path.of(definitionsPath);
	}

	@PostConstruct
	public void load() {
		long started = System.nanoTime();
		if (!Files.isDirectory(folder)) {
			throw new IllegalStateException("Workflow definitions folder does not exist: " + folder.toAbsolutePath());
		}
		Map<String, String> fileByName = new LinkedHashMap<>();
		try (Stream<Path> listing = Files.list(folder)) {
			for (Path file : listing.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
				WorkflowDefinition def = parse(file);
				String other = fileByName.put(def.getName(), file.getFileName().toString());
				if (other != null) {
					throw broken(file, List.of("definition name '" + def.getName() + "' is already defined by " + other));
				}
				definitions.put(def.getName(), def);
			}
		} catch (IOException e) {
			throw new IllegalStateException("Could not read workflow definitions folder " + folder.toAbsolutePath(), e);
		}
		log.info(line(LogEvents.DEFINITIONS_LOADED, "count", definitions.size(), "names", String.join(",", definitions.keySet()),
				"loadMs", (System.nanoTime() - started) / 1_000_000));
	}

	/** Parses and validates one file; throws with the file name and every problem found. */
	WorkflowDefinition parse(Path file) {
		WorkflowDefinition def;
		try {
			def = yaml.readValue(file.toFile(), WorkflowDefinition.class);
		} catch (IOException e) {
			String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			int newline = message.indexOf('\n');
			throw broken(file, List.of(newline > 0 ? message.substring(0, newline) : message));
		}
		if (def == null) {
			throw broken(file, List.of("file is empty"));
		}
		List<String> errors = DefinitionValidator.validate(def, OrchActivities.NAMES);
		if (!errors.isEmpty()) {
			throw broken(file, errors);
		}
		return def;
	}

	public Optional<WorkflowDefinition> find(String name) {
		return Optional.ofNullable(definitions.get(name));
	}

	public List<WorkflowDefinition> all() {
		return new ArrayList<>(definitions.values());
	}

	private static IllegalStateException broken(Path file, List<String> problems) {
		return new IllegalStateException("Invalid workflow definition " + file.getFileName() + ": " + String.join("; ", problems));
	}
}
