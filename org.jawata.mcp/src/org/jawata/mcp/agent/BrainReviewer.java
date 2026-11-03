package org.jawata.mcp.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.jawata.core.host.HostProcesses;
import org.jawata.mcp.execution.AcpBrainSession;
import org.jawata.mcp.execution.HireSite;
import org.jawata.mcp.execution.HiredBrain;
import org.jawata.spi.host.EngineSettings;

/**
 * The reviewer of the main seat's messages (Sprint 28h, S11, D25): one brain session per connection, CONTINUED from message to message,
 * so the reviewer remembers what it has already told the person and does not ask for the same thing twice. It is given no tool and no
 * tool server: it reads a draft and returns a message, which is all a review needs and nothing a review could use to act.
 *
 * <p>The rules it applies are the communication rules of the main seat (decision first, one ask per message, abbreviations defined,
 * the reader's own terms), read from the file the setting names at each review so that editing the seat's file takes effect at once.</p>
 *
 * <p>Its answer is one of two shapes: the first line {@code REVIEW: SEND}, meaning the draft may go as it is, or the first line
 * {@code REVIEW: REVISED} followed by the whole message to send. Anything else is no review at all, and the draft is then held.</p>
 */
final class BrainReviewer implements ReviewBeforeSend.Reviewer {

    /** The setting that names the reviewer's brain, in the same shape as the principal brain's. */
    static final String SETTING = "jawata.agent.reviewer";

    /** The setting that names the file holding the communication rules the reviewer applies. */
    static final String RULES_SETTING = "jawata.agent.reviewerRules";

    private static final String DEFAULT_RULES = "You review a message an agent is about to send to a person. Apply these rules: open with the decision "
        + "the person must make, in business terms; one decision per message; define every abbreviation at its first use; use the person's own terms; "
        + "put technical detail below the point; never repeat what was already said; cut anything that is only noise.";

    private final HostProcesses host;
    private final AcpBrainSession.Program program;
    private final HireSite site;
    private final Supplier<String> rules;
    private final HiredBrain.Ceilings ceilings;
    private volatile Optional<String> resume = Optional.empty();

    BrainReviewer(HostProcesses host, AcpBrainSession.Program program, HireSite site, Supplier<String> rules, HiredBrain.Ceilings ceilings) {
        this.host = host;
        this.program = program;
        this.site = site;
        this.rules = rules;
        this.ceilings = ceilings;
    }

    /** The reviewer the settings name, or none when no reviewer is configured or the setting is not a command line. */
    static Optional<ReviewBeforeSend.Reviewer> over(EngineSettings settings, Path workingDirectory) {
        if (settings == null) {
            return Optional.empty();
        }
        return settings.get(SETTING).flatMap(AcpBrainSession::parseProgram).map(program -> new BrainReviewer(HostProcesses.system(), program,
            HireSite.in(workingDirectory), () -> rulesFrom(settings.get(RULES_SETTING)), HiredBrain.Ceilings.of(Duration.ofMinutes(5))));
    }

    /** The rules in the file the setting names, or the default rules when it names none or cannot be read. */
    static String rulesFrom(Optional<String> file) {
        if (file.isEmpty() || file.get().isBlank()) {
            return DEFAULT_RULES;
        }
        try {
            return Files.readString(Path.of(file.get().trim()), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return DEFAULT_RULES;
        }
    }

    @Override
    public Optional<String> review(String draft) {
        String prompt = rules.get() + "\n\nTHE DRAFT TO REVIEW:\n" + draft + "\n\nAnswer with the first line REVIEW: SEND if the draft may go as it is, "
            + "or the first line REVIEW: REVISED followed by the whole message to send.";
        HiredBrain.Outcome outcome;
        try {
            outcome = AcpBrainSession.run(host, program, site, prompt, null, List.of(), resume, ceilings, null, null, update -> { });
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        outcome.brainSessionId().ifPresent(id -> resume = Optional.of(id));
        return reviewed(outcome.text(), draft);
    }

    /** The message to send for {@code draft} given the reviewer's answer, or empty when the answer is neither shape. */
    static Optional<String> reviewed(String answer, String draft) {
        if (answer == null) {
            return Optional.empty();
        }
        String trimmed = answer.strip();
        if (trimmed.startsWith("REVIEW: SEND")) {
            return Optional.of(draft);
        }
        if (trimmed.startsWith("REVIEW: REVISED")) {
            String revised = trimmed.substring("REVIEW: REVISED".length()).strip();
            return revised.isEmpty() ? Optional.empty() : Optional.of(revised);
        }
        return Optional.empty();
    }
}
