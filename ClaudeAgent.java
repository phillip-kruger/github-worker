import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

public class ClaudeAgent implements CodingAgent {

    private static final Path EMPTY_MCP_CONFIG = Path.of("/tmp/github-worker-empty-mcp.json");
    private static final Path LOG_PATH = Path.of(System.getProperty("user.home"),
            ".config", "github-worker", "claude.log");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String run(String prompt, Path workDir, int timeoutMinutes) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "claude", "-p",
                    "--dangerously-skip-permissions",
                    "--model", "sonnet");
            pb.directory(workDir.toFile());
            return execute(pb, prompt, timeoutMinutes, workDir.toString());
        } catch (Exception e) {
            System.err.println("  Claude failed: " + e.getMessage());
            appendLog("ERROR", e.getMessage());
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
            return execute(pb, prompt, timeoutSeconds / 60 + 1, "(bare)");
        } catch (Exception e) {
            System.err.println("  Claude (bare) failed: " + e.getMessage());
            appendLog("ERROR", e.getMessage());
            return null;
        }
    }

    private String execute(ProcessBuilder pb, String prompt, int timeoutMinutes, String context)
            throws IOException, InterruptedException {
        appendLog("START", "cwd=" + context + " timeout=" + timeoutMinutes + "m prompt=" + truncate(prompt, 200));

        pb.redirectErrorStream(false);
        Process process = pb.start();

        try (var os = process.getOutputStream()) {
            os.write(prompt.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            appendLog("TIMEOUT", "after " + timeoutMinutes + " minutes");
            return null;
        }

        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            appendLog("FAIL", "exit=" + process.exitValue() + " " + truncate(stderr, 300));
            return null;
        }

        appendLog("OK", truncate(stdout, 500));
        return stdout;
    }

    private void appendLog(String level, String message) {
        try {
            String line = "[" + LocalDateTime.now().format(TS) + "] " + level + " " + message + "\n";
            Files.writeString(LOG_PATH, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
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
