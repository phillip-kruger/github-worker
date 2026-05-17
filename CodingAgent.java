import java.nio.file.Path;

/**
 * Interface for coding agents that can analyze code, implement fixes, and review changes.
 *
 * The github-worker delegates all AI coding tasks to a CodingAgent implementation.
 * Claude Code CLI is the default, but other agents can be used by implementing this
 * interface and setting AGENT in the config.
 *
 * Implementations must support two modes:
 *
 *   run()     — Full mode. The agent works in a git checkout with filesystem access.
 *               Used for coding, reviewing, fixing. The agent should be able to read
 *               files, edit code, run builds, and make git commits.
 *
 *   runBare() — Analysis-only mode. No filesystem access. Used for security triage
 *               and text analysis where the agent should NOT be able to execute code
 *               or access the filesystem.
 *
 * To add a new agent:
 *   1. Create a new class implementing CodingAgent (e.g. CodexAgent.java)
 *   2. Add it as a //SOURCES entry in GitHubWorker.java
 *   3. Add a case in CodingAgent.create() for your agent name
 *   4. Set AGENT=your-agent-name in ~/.config/github-worker/config
 *
 * The agent receives prompts as plain text and returns plain text output.
 * Prompts include full context (issue details, diffs, review findings) so the
 * agent has everything it needs.
 */
public interface CodingAgent {

    /**
     * Run a prompt with full filesystem access in the given working directory.
     * The agent can read/write files, run commands, and make git commits.
     *
     * @param prompt         the task description and context
     * @param workDir        git checkout to work in
     * @param timeoutMinutes max time before the agent is killed
     * @return agent output text, or null on failure
     */
    String run(String prompt, Path workDir, int timeoutMinutes);

    /**
     * Convenience overload with 30-minute default timeout.
     */
    default String run(String prompt, Path workDir) {
        return run(prompt, workDir, 30);
    }

    /**
     * Run a prompt in analysis-only mode with NO filesystem access.
     * Used for security triage and text generation where the agent
     * must not be able to execute code or touch the filesystem.
     *
     * @param prompt         the analysis task
     * @param timeoutSeconds max time before the agent is killed
     * @return agent output text, or null on failure
     */
    String runBare(String prompt, int timeoutSeconds);

    /**
     * Convenience overload with 120-second default timeout.
     */
    default String runBare(String prompt) {
        return runBare(prompt, 120);
    }

    /**
     * Create a CodingAgent from the config. Falls back to Claude Code CLI if
     * AGENT is not set.
     */
    static CodingAgent create(Config config) {
        String agent = config.agent;
        return switch (agent) {
            case "claude" -> new ClaudeAgent();
            // Add new agents here:
            // case "codex"  -> new CodexAgent();
            // case "aider"  -> new AiderAgent();
            // case "gemini" -> new GeminiAgent();
            default -> {
                System.err.println("Unknown agent: " + agent + ", falling back to claude");
                yield new ClaudeAgent();
            }
        };
    }
}
