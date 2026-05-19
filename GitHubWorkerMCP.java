///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * MCP (Model Context Protocol) server for github-worker.
 * Communicates via stdio using LSP framing (Content-Length header + JSON-RPC 2.0).
 */
public class GitHubWorkerMCP {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private static final Path STATE_PATH = Path.of(
            System.getProperty("user.home"), ".config", "github-worker", "state.json");

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    // --- State model (mirrors WorkflowState.java) ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class IssueEntry {
        public String state;
        public Long botCommentId;
        public Integer prNumber;
        public String prUrl;
        public Instant lastUpdated;
        public int attempts;
        public String feedbackText;
        public String title;
        public String ownerRepo;
        public int issueNumber;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ReviewEntry {
        public String state;
        public Instant lastUpdated;
        public String title;
        public String ownerRepo;
        public int prNumber;
        public String headBranch;
        public String author;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class State {
        public Map<String, IssueEntry> issues = new LinkedHashMap<>();
        public Map<String, ReviewEntry> reviews = new LinkedHashMap<>();
    }

    // --- Entry point ---

    public static void main(String[] args) throws Exception {
        log("MCP server starting");
        InputStream in = System.in;
        OutputStream out = System.out;

        while (true) {
            String message = readMessage(in);
            if (message == null) {
                log("stdin closed, exiting");
                break;
            }
            log("<<< " + message);

            JsonNode request = MAPPER.readTree(message);
            String method = request.has("method") ? request.get("method").asText() : null;

            if (method == null) {
                continue; // response or malformed, ignore
            }

            // Notifications have no id and expect no response
            if (!request.has("id")) {
                log("notification: " + method);
                continue;
            }

            Object id = request.get("id").isNumber()
                    ? request.get("id").asLong()
                    : request.get("id").asText();

            ObjectNode response = handleRequest(method, request.get("params"), id);
            if (response != null) {
                writeMessage(out, response);
            }
        }
    }

    // --- Message framing ---

    private static String readMessage(InputStream in) throws IOException {
        // Read headers until blank line
        StringBuilder headerBuf = new StringBuilder();
        int contentLength = -1;
        while (true) {
            String line = readLine(in);
            if (line == null) return null;
            if (line.isEmpty()) break;
            if (line.startsWith("Content-Length:")) {
                contentLength = Integer.parseInt(line.substring("Content-Length:".length()).trim());
            }
        }
        if (contentLength < 0) return null;

        byte[] body = new byte[contentLength];
        int offset = 0;
        while (offset < contentLength) {
            int n = in.read(body, offset, contentLength - offset);
            if (n < 0) return null;
            offset += n;
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int prev = -1;
        while (true) {
            int c = in.read();
            if (c < 0) return sb.length() > 0 ? sb.toString() : null;
            if (c == '\n') {
                if (prev == '\r') sb.setLength(sb.length() - 1);
                return sb.toString();
            }
            sb.append((char) c);
            prev = c;
        }
    }

    private static void writeMessage(OutputStream out, ObjectNode response) throws IOException {
        byte[] body = MAPPER.writeValueAsBytes(response);
        String header = "Content-Length: " + body.length + "\r\n\r\n";
        log(">>> " + new String(body, StandardCharsets.UTF_8));
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    // --- Request dispatch ---

    private static ObjectNode handleRequest(String method, JsonNode params, Object id) {
        try {
            return switch (method) {
                case "initialize" -> handleInitialize(id);
                case "tools/list" -> handleToolsList(id);
                case "tools/call" -> handleToolsCall(params, id);
                default -> makeError(id, -32601, "Method not found: " + method);
            };
        } catch (Exception e) {
            log("Error handling " + method + ": " + e.getMessage());
            return makeError(id, -32603, e.getMessage());
        }
    }

    // --- initialize ---

    private static ObjectNode handleInitialize(Object id) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("protocolVersion", "2024-11-05");

        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "github-worker-mcp");
        serverInfo.put("version", "1.0.0");

        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools");

        return makeResult(id, result);
    }

    // --- tools/list ---

    private static ObjectNode handleToolsList(Object id) {
        ArrayNode tools = MAPPER.createArrayNode();

        tools.add(toolDef("worker_status", "Show current github-worker state: issues being worked, PRs under review, their states and last update times"));
        tools.add(toolDef("worker_trigger", "Trigger a github-worker run in the background"));
        tools.add(toolDef("worker_preview", "Preview what github-worker would do without actually doing it"));

        ObjectNode retrySchema = MAPPER.createObjectNode();
        retrySchema.put("type", "object");
        ObjectNode retryProps = retrySchema.putObject("properties");
        propDef(retryProps, "item", "string", "Issue/PR key to retry (e.g. 'owner/repo#123')");
        retrySchema.putArray("required").add("item");
        tools.add(toolDef("worker_retry", "Reset an item to retry it (moves it back to an earlier state)", retrySchema));

        ObjectNode addSchema = MAPPER.createObjectNode();
        addSchema.put("type", "object");
        ObjectNode addProps = addSchema.putObject("properties");
        propDef(addProps, "item", "string", "GitHub issue/PR URL or key (e.g. 'owner/repo#123' or full URL)");
        addSchema.putArray("required").add("item");
        tools.add(toolDef("worker_add", "Add a new GitHub issue or PR to the worker queue", addSchema));

        ObjectNode removeSchema = MAPPER.createObjectNode();
        removeSchema.put("type", "object");
        ObjectNode removeProps = removeSchema.putObject("properties");
        propDef(removeProps, "item", "string", "Issue/PR key to remove (e.g. 'owner/repo#123')");
        removeSchema.putArray("required").add("item");
        tools.add(toolDef("worker_remove", "Remove an item from the worker queue", removeSchema));

        tools.add(toolDef("worker_discover", "Run discovery to find new issues and PRs to work on"));

        ObjectNode logsSchema = MAPPER.createObjectNode();
        logsSchema.put("type", "object");
        ObjectNode logsProps = logsSchema.putObject("properties");
        ObjectNode linesProp = logsProps.putObject("lines");
        linesProp.put("type", "integer");
        linesProp.put("description", "Number of log lines to return (default: 50)");
        tools.add(toolDef("worker_logs", "Show recent github-worker systemd journal logs", logsSchema));

        ObjectNode result = MAPPER.createObjectNode();
        result.set("tools", tools);
        return makeResult(id, result);
    }

    // --- tools/call ---

    private static ObjectNode handleToolsCall(JsonNode params, Object id) throws Exception {
        String name = params.get("name").asText();
        JsonNode args = params.has("arguments") ? params.get("arguments") : MAPPER.createObjectNode();

        String text = switch (name) {
            case "worker_status" -> toolStatus();
            case "worker_trigger" -> toolTrigger();
            case "worker_preview" -> toolPreview();
            case "worker_retry" -> toolRetry(args.get("item").asText());
            case "worker_add" -> toolAdd(args.get("item").asText());
            case "worker_remove" -> toolRemove(args.get("item").asText());
            case "worker_discover" -> toolDiscover();
            case "worker_logs" -> toolLogs(args.has("lines") ? args.get("lines").asInt() : 50);
            default -> throw new IllegalArgumentException("Unknown tool: " + name);
        };

        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode item = content.addObject();
        item.put("type", "text");
        item.put("text", text);
        return makeResult(id, result);
    }

    // --- Tool implementations ---

    private static String toolStatus() throws Exception {
        State state = loadState();
        StringBuilder sb = new StringBuilder();

        if (state.issues.isEmpty() && state.reviews.isEmpty()) {
            return "No items in the queue.";
        }

        if (!state.issues.isEmpty()) {
            sb.append("## Issues\n\n");
            for (var e : state.issues.entrySet()) {
                IssueEntry v = e.getValue();
                sb.append("- **").append(e.getKey()).append("**");
                if (v.title != null) sb.append(" — ").append(v.title);
                sb.append("\n");
                sb.append("  State: `").append(v.state).append("`");
                if (v.prUrl != null) sb.append(" | PR: ").append(v.prUrl);
                else if (v.prNumber != null) sb.append(" | PR #").append(v.prNumber);
                if (v.lastUpdated != null) sb.append(" | Updated: ").append(TIME_FMT.format(v.lastUpdated));
                sb.append("\n");
            }
        }

        if (!state.reviews.isEmpty()) {
            if (!state.issues.isEmpty()) sb.append("\n");
            sb.append("## Reviews\n\n");
            for (var e : state.reviews.entrySet()) {
                ReviewEntry v = e.getValue();
                sb.append("- **").append(e.getKey()).append("**");
                if (v.title != null) sb.append(" — ").append(v.title);
                sb.append("\n");
                sb.append("  State: `").append(v.state).append("`");
                if (v.author != null) sb.append(" | Author: ").append(v.author);
                if (v.lastUpdated != null) sb.append(" | Updated: ").append(TIME_FMT.format(v.lastUpdated));
                sb.append("\n");
            }
        }

        return sb.toString().stripTrailing();
    }

    private static String toolTrigger() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("github-worker")
                .redirectErrorStream(true);
        pb.start(); // fire and forget
        return "github-worker triggered in background.";
    }

    private static String toolPreview() throws Exception {
        return runCommand("github-worker", "--preview");
    }

    private static String toolRetry(String item) throws Exception {
        State state = loadState();
        String key = normalizeKey(item);

        if (state.issues.containsKey(key)) {
            IssueEntry entry = state.issues.get(key);
            String oldState = entry.state;
            entry.state = switch (entry.state) {
                case "MONITORING_CI" -> "SQUASHING";
                case "FIXING_CI" -> "SQUASHING";
                case "ADDRESSING_FEEDBACK" -> "READY_FOR_REVIEW";
                case "DONE" -> "NEW";
                default -> "NEW";
            };
            entry.lastUpdated = Instant.now();
            saveState(state);
            return "Issue " + key + ": " + oldState + " -> " + entry.state;
        }

        if (state.reviews.containsKey(key)) {
            ReviewEntry entry = state.reviews.get(key);
            String oldState = entry.state;
            entry.state = "NEW";
            entry.lastUpdated = Instant.now();
            saveState(state);
            return "Review " + key + ": " + oldState + " -> " + entry.state;
        }

        return "Item not found: " + key;
    }

    private static String toolAdd(String item) throws Exception {
        String key;
        String ownerRepo;
        int number;
        boolean isPR;

        // Parse URL or key
        if (item.contains("github.com")) {
            // https://github.com/owner/repo/issues/123 or /pull/123
            String[] parts = item.replaceAll("https?://github\\.com/", "").split("/");
            if (parts.length < 4) return "Invalid URL: " + item;
            ownerRepo = parts[0] + "/" + parts[1];
            number = Integer.parseInt(parts[3].replaceAll("[^0-9]", ""));
            isPR = parts[2].equals("pull") || parts[2].equals("pulls");
            key = ownerRepo + "#" + number;
        } else {
            // owner/repo#123
            key = normalizeKey(item);
            String[] split = key.split("#");
            if (split.length != 2) return "Invalid item format: " + item;
            ownerRepo = split[0];
            number = Integer.parseInt(split[1]);
            // detect type via gh api
            isPR = detectIsPR(ownerRepo, number);
        }

        State state = loadState();

        if (state.issues.containsKey(key) || state.reviews.containsKey(key)) {
            return "Already in queue: " + key;
        }

        // Fetch title
        String title = fetchTitle(ownerRepo, number, isPR);

        if (isPR) {
            ReviewEntry entry = new ReviewEntry();
            entry.state = "NEW";
            entry.lastUpdated = Instant.now();
            entry.title = title;
            entry.ownerRepo = ownerRepo;
            entry.prNumber = number;
            state.reviews.put(key, entry);
        } else {
            IssueEntry entry = new IssueEntry();
            entry.state = "NEW";
            entry.lastUpdated = Instant.now();
            entry.title = title;
            entry.ownerRepo = ownerRepo;
            entry.issueNumber = number;
            state.issues.put(key, entry);
        }

        saveState(state);
        String type = isPR ? "PR" : "Issue";
        return "Added " + type + " " + key + (title != null ? " (" + title + ")" : "");
    }

    private static String toolRemove(String item) throws Exception {
        State state = loadState();
        String key = normalizeKey(item);

        boolean removed = state.issues.remove(key) != null || state.reviews.remove(key) != null;
        if (!removed) {
            return "Item not found: " + key;
        }

        saveState(state);
        return "Removed: " + key;
    }

    private static String toolDiscover() throws Exception {
        return runCommand("github-worker", "--discover");
    }

    private static String toolLogs(int lines) throws Exception {
        return runCommand("journalctl", "--user-unit", "github-worker",
                "-n", String.valueOf(lines), "--no-pager");
    }

    // --- Helpers ---

    private static State loadState() throws Exception {
        if (!Files.exists(STATE_PATH)) {
            return new State();
        }
        return MAPPER.readValue(STATE_PATH.toFile(), State.class);
    }

    private static void saveState(State state) throws Exception {
        Files.createDirectories(STATE_PATH.getParent());
        Path tmp = STATE_PATH.resolveSibling("state.json.tmp");
        MAPPER.writeValue(tmp.toFile(), state);
        Files.move(tmp, STATE_PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String normalizeKey(String item) {
        // Strip whitespace and any trailing slashes or fragments
        return item.strip().replaceAll("/$", "");
    }

    private static boolean detectIsPR(String ownerRepo, int number) {
        try {
            String output = runCommand("gh", "api",
                    "repos/" + ownerRepo + "/pulls/" + number, "--jq", ".number");
            return output.strip().equals(String.valueOf(number));
        } catch (Exception e) {
            return false; // assume issue if detection fails
        }
    }

    private static String fetchTitle(String ownerRepo, int number, boolean isPR) {
        try {
            String endpoint = isPR
                    ? "repos/" + ownerRepo + "/pulls/" + number
                    : "repos/" + ownerRepo + "/issues/" + number;
            return runCommand("gh", "api", endpoint, "--jq", ".title").strip();
        } catch (Exception e) {
            log("Could not fetch title: " + e.getMessage());
            return null;
        }
    }

    private static String runCommand(String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command)
                .redirectErrorStream(true);
        Process proc = pb.start();
        String output;
        try (var reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
        }
        boolean finished = proc.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            return output + "\n[timed out after 120s]";
        }
        if (proc.exitValue() != 0) {
            throw new RuntimeException("Command failed (exit " + proc.exitValue() + "): " + output);
        }
        return output;
    }

    // --- JSON-RPC helpers ---

    private static ObjectNode makeResult(Object id, ObjectNode result) {
        ObjectNode resp = MAPPER.createObjectNode();
        resp.put("jsonrpc", "2.0");
        if (id instanceof Long) resp.put("id", (Long) id);
        else resp.put("id", id.toString());
        resp.set("result", result);
        return resp;
    }

    private static ObjectNode makeError(Object id, int code, String message) {
        ObjectNode resp = MAPPER.createObjectNode();
        resp.put("jsonrpc", "2.0");
        if (id instanceof Long) resp.put("id", (Long) id);
        else resp.put("id", id.toString());
        ObjectNode error = resp.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return resp;
    }

    private static ObjectNode toolDef(String name, String description) {
        return toolDef(name, description, null);
    }

    private static ObjectNode toolDef(String name, String description, ObjectNode inputSchema) {
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        if (inputSchema != null) {
            tool.set("inputSchema", inputSchema);
        } else {
            ObjectNode empty = MAPPER.createObjectNode();
            empty.put("type", "object");
            tool.set("inputSchema", empty);
        }
        return tool;
    }

    private static void propDef(ObjectNode props, String name, String type, String description) {
        ObjectNode prop = props.putObject(name);
        prop.put("type", type);
        prop.put("description", description);
    }

    private static void log(String msg) {
        System.err.println("[github-worker-mcp] " + msg);
    }
}
