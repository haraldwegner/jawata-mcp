package org.jawata.mcp.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * mcp#27 stage 1 — <b>asking the other residents, so a miss becomes an answer.</b>
 *
 * <p>{@link SiblingRegistry} says WHO is running; this asks them. One question only, on the miss
 * path: <i>do you hold this type, and in which project?</i> The answer becomes
 * <i>"not here — workspace X has it, in project Y"</i>.</p>
 *
 * <h2>The loop guard, which is not optional</h2>
 *
 * <p>Two residents that each peek on a miss, pointed at one another, never terminate. Every
 * request carries {@link #PEEK_HEADER}; a resident serving a request that has it must not peek
 * onward. The header describes the HOP rather than the question, which is why it is not a tool
 * argument — a marker in the arguments would appear in every published schema, and mcp#31 has
 * just finished moving exactly that kind of meta-argument off the tools and onto the choke.</p>
 *
 * <p>Sending it is forward-compatible: a resident that does not yet honour it ignores an unknown
 * header, so this side can ship first and the guard closes when the serving side lands.</p>
 *
 * <h2>Why the sweep reports THREE numbers and not one</h2>
 *
 * <p>The stage's own exit criterion is that <i>"the same search with the second resident stopped
 * says it consulted ZERO siblings"</i> — a search that found nothing and still reports a count.
 * So the count cannot live on the answer, because on that path there is no answer.</p>
 *
 * <p>And one number would not be honest either. A registry row is written when a resident spawns
 * and is NOT removed when it dies, so a stale row is the ordinary case rather than an incident.
 * <b>"One sibling listed, none answered" and "no siblings listed" are different facts</b>, and a
 * single count collapses them — which is this project's recorded deepest bug class, an absence
 * reported as an emptiness. {@link Sweep} therefore reports what was LISTED, how many actually
 * ANSWERED, and whether the walk was cut short.</p>
 *
 * <h2>Why the budget is TOTAL as well as per sibling</h2>
 *
 * <p>"A dead sibling is skipped" quietly assumes a dead port fails fast. On Linux a connection to
 * a loopback port with no listener is refused in microseconds; <b>on Windows it can burn the
 * client's whole timeout</b>. So a per-sibling bound alone lets a machine with several stale rows
 * turn one miss into several seconds of waiting.</p>
 *
 * <p>Hence {@link #TOTAL_BUDGET}: the walk stops asking once it is spent, whatever remains
 * unasked. An incomplete sweep is the correct outcome — and it says so, rather than being passed
 * off as an exhaustive one.</p>
 */
public final class SiblingPeek {

    private static final Logger log = LoggerFactory.getLogger(SiblingPeek.class);
    private static final ObjectMapper OM = new ObjectMapper();

    /** Set on every peek; a resident serving a request carrying it must not peek onward. */
    public static final String PEEK_HEADER = "X-Jawata-Peek";

    /** One sibling's share. Short: this sits on a miss a human is waiting through. */
    static final Duration PER_SIBLING = Duration.ofMillis(750);

    /** The whole walk's share — see the class note on why this exists separately. */
    static final Duration TOTAL_BUDGET = Duration.ofMillis(2_000);

    /** Which sibling holds the type, and where. */
    public record Found(String workspaceName, String projectKey) {}

    /**
     * What ONE sibling said.
     *
     * <p>Two facts rather than one, because <i>"it answered and does not hold the type"</i> and
     * <i>"it never heard us"</i> are the same empty {@code Optional} to a caller and are not the
     * same fact to a reader. {@link Sweep}'s counts exist to tell them apart, so the reply that
     * feeds those counts has to carry both.</p>
     *
     * @param answered   the sibling replied to the question we asked. A connection refused, a
     *                   timeout and a refusal to serve us are all {@code false}
     * @param projectKey the project it says declares the type, when it holds it
     */
    record Reply(boolean answered, Optional<String> projectKey) {

        static final Reply SILENT = new Reply(false, Optional.empty());

        static Reply doesNotHoldIt() {
            return new Reply(true, Optional.empty());
        }

        static Reply holdsIt(String projectKey) {
            return new Reply(true, Optional.of(projectKey));
        }
    }

    /**
     * What the walk did, whether or not it found anything.
     *
     * @param found       the first sibling that holds the type, if any
     * @param listed      how many siblings the registry named — a stale row counts here, because
     *                    it was a row we had to consider
     * @param answered    how many of them actually replied. This is the number the exit criterion
     *                    calls CONSULTED: a resident that is not listening was not consulted, it
     *                    was skipped, and reporting it as consulted would claim we asked something
     *                    that never heard us
     * @param budgetSpent whether {@link #TOTAL_BUDGET} ran out before every listed sibling was
     *                    asked. When true, {@code answered} is a floor and not a total
     */
    public record Sweep(Optional<Found> found, int listed, int answered, boolean budgetSpent) {

        /** Nobody to ask — the ordinary single-resident machine, with no studio and no registry. */
        public static Sweep noSiblings() {
            return new Sweep(Optional.empty(), 0, 0, false);
        }

        /** One sentence for the miss path, naming what was examined to produce it. */
        public String describe() {
            String scope = budgetSpent
                ? "consulted " + answered + " of " + listed + " sibling workspace(s) before the"
                    + " time budget ran out"
                : "consulted " + answered + " of " + listed + " sibling workspace(s)";
            return found
                .map(f -> "Not here — workspace '" + f.workspaceName() + "' has it, in project '"
                    + f.projectKey() + "'. (" + scope + ".)")
                .orElse("No sibling workspace holds it (" + scope + ").");
        }
    }

    private SiblingPeek() {}

    /**
     * Ask each sibling in turn until one holds {@code fqn}, the budget runs out, or the list ends.
     *
     * <p>Never throws and never reports a failure: a caller on the miss path already has an
     * answer to give, and this can only add to it.</p>
     */
    public static Sweep sweep(String fqn, List<SiblingRegistry.Sibling> siblings,
                              HttpClient client) {
        if (siblings.isEmpty()) {
            return Sweep.noSiblings();
        }
        long deadline = System.nanoTime() + TOTAL_BUDGET.toNanos();
        int answered = 0;
        for (int i = 0; i < siblings.size(); i++) {
            if (System.nanoTime() >= deadline) {
                log.debug("peek budget spent; {} of {} sibling(s) unasked",
                    siblings.size() - i, siblings.size());
                return new Sweep(Optional.empty(), siblings.size(), answered, true);
            }
            SiblingRegistry.Sibling sibling = siblings.get(i);
            Reply reply = askOne(sibling, fqn, client);
            if (reply.answered()) {
                answered++;
            }
            if (reply.projectKey().isPresent()) {
                return new Sweep(
                    Optional.of(new Found(sibling.workspaceName(), reply.projectKey().get())),
                    siblings.size(), answered, false);
            }
        }
        return new Sweep(Optional.empty(), siblings.size(), answered, false);
    }

    /**
     * Ask ONE sibling, and report both whether it spoke and what it said.
     *
     * <p>The question is {@code inspect(kind=source)} with {@code maxChars=1}: it is the only
     * published call that answers WHICH PROJECT declares a type, and the existing paging makes
     * it cheap to ask without hauling the file back over the wire.</p>
     */
    static Reply askOne(SiblingRegistry.Sibling sibling, String fqn, HttpClient client) {
        try {
            String body = OM.writeValueAsString(java.util.Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "tools/call",
                "params", java.util.Map.of(
                    "name", "inspect",
                    "arguments", java.util.Map.of(
                        "kind", "source",
                        "typeName", fqn,
                        "maxChars", 1))));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + sibling.port() + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + sibling.token())
                .header(PEEK_HEADER, "1")
                .timeout(PER_SIBLING)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                // It is listening but it would not serve us — a stale token, most likely. That is
                // a row we could not use, not a resident we consulted.
                return Reply.SILENT;
            }
            return projectKeyOf(response.body())
                .map(Reply::holdsIt)
                .orElseGet(Reply::doesNotHoldIt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reply.SILENT;
        } catch (Exception e) {
            // Logged at debug because a stale registry row makes this the ORDINARY case, not an
            // incident: rows are written on spawn and nothing removes them on stop.
            log.debug("peek to {} on port {} did not answer: {}",
                sibling.workspaceName(), sibling.port(), e.toString());
            return Reply.SILENT;
        }
    }

    /**
     * The project key out of an MCP {@code tools/call} response, or empty.
     *
     * <p>The envelope is read from the side that BUILDS it — {@code McpProtocolHandler} puts one
     * {@code content} entry whose {@code text} is the serialized tool response — rather than
     * guessed from what a response happens to look like.</p>
     */
    static Optional<String> projectKeyOf(String responseBody) {
        try {
            JsonNode text = OM.readTree(responseBody)
                .path("result").path("content").path(0).path("text");
            if (text.isMissingNode() || !text.isTextual()) {
                return Optional.empty();
            }
            JsonNode tool = OM.readTree(text.asText());
            if (!tool.path("success").asBoolean(false)) {
                return Optional.empty();
            }
            String key = tool.path("data").path("projectKey").asText("");
            return key.isBlank() ? Optional.empty() : Optional.of(key);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
