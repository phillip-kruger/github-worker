import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public class ClaudeAgent implements CodingAgent {

    private static final Path EMPTY_MCP_CONFIG = Path.of("/tmp/github-worker-empty-mcp.json");

    @Override
    public String run(String prompt, Path workDir, int timeoutMinutes) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "claude", "-p",
                    "--dangerously-skip-permissions",
                    "--model", "sonnet");
            pb.directory(workDir.toFile());
            return execute(pb, prompt, timeoutMinutes);
        } catch (Exception e) {
            System.err.println("  Claude failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public String runBare(String prompt, int timeoutSeconds) {
        try {
            ensureEmptyMcpConfig();
            ProcessBuilder pb = new ProcessBuilder(
                    "claude", "-p",
                    "--model", "sonnet",
                    "--mcp-config", EMPTY_MCP_CONFIG.toString(),
                    "--strict-mcp-config");
            return execute(pb, prompt, timeoutSeconds / 60 + 1);
        } catch (Exception e) {
            System.err.println("  Claude (bare) failed: " + e.getMessage());
            return null;
        }
    }

    private String execute(ProcessBuilder pb, String prompt, int timeoutMinutes) throws IOException, InterruptedException {
        pb.redirectErrorStream(false);
        Process process = pb.start();

        try (var os = process.getOutputStream()) {
            os.write(prompt.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            System.err.println("  Claude timed out after " + timeoutMinutes + " minutes");
            return null;
        }

        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            System.err.println("  Claude exit code " + process.exitValue() + ": " + truncate(stderr, 500));
            return null;
        }
        return stdout;
    }

    private void ensureEmptyMcpConfig() throws IOException {
        if (!Files.exists(EMPTY_MCP_CONFIG)) {
            Files.writeString(EMPTY_MCP_CONFIG, "{\"mcpServers\":{}}");
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
