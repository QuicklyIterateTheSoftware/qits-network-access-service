package eu.wohlben.qits.networkaccess.contracts;

import static io.restassured.RestAssured.given;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import eu.wohlben.qits.networkaccess.support.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>Records qits-network-access's provider golden masters</b> — {@code golden-masters/} at the
 * repository root, the source of {@code eu.wohlben.qits:qits-network-access-golden-masters} (the
 * CLI's pacts) and {@code @qits/network-access-golden-masters} (the landing app's).
 *
 * <p>For each (state, operation) pair in {@link #INTERACTIONS} it runs the state ({@link
 * ProviderStates}), calls the operation as the operator holding {@code qits:admin}, freezes ids and
 * instants ({@link Freezer}) and renders {@code golden-masters/<state-slug>/<operationId>.json}; then
 * {@code golden-masters/index.json} (format version 1). A {@code text/plain} answer (the config) is
 * recorded as one JSON string, as qits-system records its install script.
 *
 * <p>The release gate fails any operation of {@code docs/openapi.yml} this table does not cover, so
 * a new door joins it in the same commit. {@code joinNode} arrives with the node join.
 *
 * <p>It <b>compares by default</b> and fails with a unified diff per differing file, including a
 * committed {@code .json} no interaction produces any more; {@code -Dgolden.update=true} rewrites.
 */
@QuarkusTest
@TestSecurity(user = ProviderStates.OPERATOR, roles = "qits:admin")
class GoldenMasterRecordingTest {

  static final int FORMAT_VERSION = 1;

  static final String PROVIDER = "qits-network-access";

  static final String API = "/network-access/api";

  /** One recorded interaction; {@code body} is the JSON request body sent, or null for none. */
  record Interaction(
      String state, String operationId, String method, String path, String body, int status) {}

  static final List<Interaction> INTERACTIONS =
      List.of(
          new Interaction(
              ProviderStates.AN_ANNOUNCED_PRIVATE_EDGE,
              "createPeer",
              "POST",
              API + "/peers",
              "{\"name\":\"laptop\",\"publicKey\":\"" + Fixtures.key(2) + "\"}",
              201),
          new Interaction(
              ProviderStates.AN_ANNOUNCED_PRIVATE_EDGE,
              "getPrivateEdge",
              "GET",
              API + "/private-edge",
              null,
              200),
          new Interaction(
              ProviderStates.AN_ANNOUNCED_PRIVATE_EDGE,
              "getCompanion",
              "GET",
              API + "/companion",
              null,
              200),
          new Interaction(
              ProviderStates.A_DEVICE_PEER, "getPeers", "GET", API + "/peers", null, 200),
          new Interaction(
              ProviderStates.A_DEVICE_PEER, "getPeer", "GET", API + "/peers/{peerId}", null, 200),
          new Interaction(
              ProviderStates.A_DEVICE_PEER,
              "revokePeer",
              "POST",
              API + "/peers/{peerId}/revoke",
              null,
              200),
          new Interaction(
              ProviderStates.A_DEVICE_PEER,
              "getPeerConfig",
              "GET",
              API + "/peers/{peerId}/config",
              null,
              200),
          new Interaction(
              ProviderStates.A_DEVICE_PEER,
              "getPeerTunnel",
              "GET",
              API + "/peers/{peerId}/tunnel",
              null,
              200),
          new Interaction(
              ProviderStates.A_JOINED_NODE, "getNodes", "GET", API + "/nodes", null, 200),
          new Interaction(
              ProviderStates.A_JOINED_NODE, "getNode", "GET", API + "/nodes/{targetId}", null, 200));

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final Pattern TEMPLATE_PARAM = Pattern.compile("\\{([^}]+)}");

  @Inject ProviderStates states;

  @BeforeEach
  void quietBus() {
    // A write's event goes nowhere: the recording is about the answer.
    Fixtures.recordEvents();
  }

  @Test
  void goldenMastersMatchTheProvider() throws IOException {
    Path dir = GoldenFiles.repositoryRoot().resolve("golden-masters");
    boolean update = GoldenFiles.updating();
    List<String> failures = new ArrayList<>();
    Set<String> written = new TreeSet<>();
    Map<String, ObjectNode> indexStates = new TreeMap<>();
    Map<String, Map<String, ObjectNode>> indexOperations = new TreeMap<>();
    Map<String, Map<String, String>> committed = committedParams(dir.resolve("index.json"));

    for (Interaction interaction : INTERACTIONS) {
      String slug = ProviderStates.slug(interaction.state());
      ProviderStates.Setup setup = states.setUp(interaction.state());
      Recorded recorded = record(interaction, setup, committed.getOrDefault(slug, Map.of()));
      String file = slug + "/" + interaction.operationId() + ".json";

      ObjectNode state = indexStates.get(slug);
      if (state == null) {
        state = JsonNodeFactory.instance.objectNode();
        state.put("name", interaction.state());
        state.put("slug", slug);
        state.set("params", recorded.params());
        state.set("dependsOn", JsonNodeFactory.instance.arrayNode());
        indexStates.put(slug, state);
      } else if (!state.get("params").equals(recorded.params())) {
        failures.add("State '" + interaction.state() + "' froze to different params per operation");
      }

      ObjectNode operation = JsonNodeFactory.instance.objectNode();
      operation.put("operationId", interaction.operationId());
      operation.put("method", interaction.method());
      operation.put("path", interaction.path());
      if (interaction.body() != null) {
        operation.set("body", JSON.readTree(interaction.body()));
      }
      operation.put("status", interaction.status());
      operation.put("file", file);
      ObjectNode frozen = operation.putObject("frozen");
      frozen.set("ids", strings(recorded.freezer().idPaths()));
      frozen.set("instants", strings(recorded.freezer().instantPaths()));
      frozen.set("strings", strings(recorded.freezer().stringPaths()));
      frozen.putNull("listFilteredTo");
      if (indexOperations
              .computeIfAbsent(slug, k -> new TreeMap<>())
              .put(interaction.operationId(), operation)
          != null) {
        failures.add("Duplicate interaction " + file);
      }
      written.add(file);
      check(dir.resolve(file), GoldenJson.render(recorded.body()), update, failures);
    }

    ObjectNode index = JsonNodeFactory.instance.objectNode();
    index.put("formatVersion", FORMAT_VERSION);
    index.put("provider", PROVIDER);
    ArrayNode stateArray = index.putArray("states");
    indexStates.forEach(
        (slug, state) -> {
          ArrayNode operations = state.putArray("operations");
          indexOperations.get(slug).values().forEach(operations::add);
          stateArray.add(state);
        });
    written.add("index.json");
    check(dir.resolve("index.json"), GoldenJson.render(index), update, failures);

    for (String stale : committedJson(dir)) {
      if (written.contains(stale)) {
        continue;
      }
      if (update) {
        Files.delete(dir.resolve(stale));
      } else {
        failures.add(
            dir.resolve(stale)
                + " is committed but no interaction records it any more — rerun with"
                + " -Dgolden.update=true to delete it.");
      }
    }
    if (!failures.isEmpty()) {
      throw new AssertionError(String.join("\n\n", failures));
    }
  }

  /** One interaction's frozen answer, its frozen params and what was frozen where. */
  record Recorded(JsonNode body, ObjectNode params, Freezer freezer) {}

  private static Recorded record(
      Interaction interaction, ProviderStates.Setup setup, Map<String, String> committedParams)
      throws IOException {
    Map<String, String> params = setup.params();
    var request = given().header("Accept", "application/json, text/plain");
    if (interaction.body() != null) {
      request = request.contentType("application/json").body(interaction.body());
    }
    Response response =
        request.when().request(interaction.method(), expand(interaction.path(), params));
    String raw = response.asString();
    if (response.statusCode() != interaction.status()) {
      throw new AssertionError(
          interaction.method()
              + " "
              + interaction.path()
              + " in state '"
              + interaction.state()
              + "' answered "
              + response.statusCode()
              + ", expected "
              + interaction.status()
              + ": "
              + raw);
    }
    String type = String.valueOf(response.getContentType());
    JsonNode body;
    if (raw.isEmpty()) {
      body = JsonNodeFactory.instance.nullNode();
    } else if (type.startsWith("text/")) {
      body = TextNode.valueOf(raw);
    } else {
      body = JSON.readTree(raw);
    }
    Freezer freezer =
        new Freezer().seed(params, committedParams).uniqueTokens(setup.uniqueTokens());
    ObjectNode frozenParams = JsonNodeFactory.instance.objectNode();
    params.forEach((k, v) -> frozenParams.put(k, freezer.freezeParam(v)));
    return new Recorded(freezer.freeze(body), frozenParams, freezer);
  }

  private static void check(Path golden, String actual, boolean update, List<String> failures) {
    String failure = GoldenFiles.check(golden, actual, update, UnaryOperator.identity());
    if (failure != null) {
      failures.add(failure);
    }
  }

  private static String expand(String template, Map<String, String> params) {
    Matcher m = TEMPLATE_PARAM.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String value = params.get(m.group(1));
      if (value == null) {
        throw new IllegalStateException(
            "Path " + template + " names {" + m.group(1) + "}, which the state does not return");
      }
      m.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    m.appendTail(out);
    return out.toString();
  }

  private static ArrayNode strings(List<String> values) {
    ArrayNode out = JsonNodeFactory.instance.arrayNode();
    values.forEach(out::add);
    return out;
  }

  /** slug → frozen params of every state in the committed index; empty when there is none yet. */
  static Map<String, Map<String, String>> committedParams(Path index) throws IOException {
    if (!Files.isRegularFile(index)) {
      return Map.of();
    }
    Map<String, Map<String, String>> out = new TreeMap<>();
    for (JsonNode state : JSON.readTree(index.toFile()).path("states")) {
      Map<String, String> params = new TreeMap<>();
      state
          .path("params")
          .fields()
          .forEachRemaining(p -> params.put(p.getKey(), p.getValue().asText()));
      out.put(state.path("slug").asText(), params);
    }
    return out;
  }

  private static List<String> committedJson(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> files = Files.walk(dir)) {
      return files
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .map(p -> dir.relativize(p).toString().replace('\\', '/'))
          .sorted()
          .toList();
    }
  }
}
