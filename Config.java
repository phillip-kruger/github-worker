import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class Config {

    static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".config", "github-worker");
    static final Path CONFIG_PATH = CONFIG_DIR.resolve("config");
    static final Path STATE_PATH = CONFIG_DIR.resolve("state.json");
    static final Path LOCK_PATH = CONFIG_DIR.resolve("lock");

    String githubUser;
    String githubToken;
    String botUser;
    String botToken;
    Set<String> excludeOrgs;
    String gmailAddress;
    String gmailAppPassword;
    String sendTo;
    String activeHours;
    Path workDir;
    int lookbackDays;
    String agent;

    static Config load() {
        if (!Files.exists(CONFIG_PATH)) {
            System.err.println("Config file not found: " + CONFIG_PATH);
            System.err.println("Run install.sh to set up github-worker.");
            System.exit(1);
        }

        Map<String, String> raw = new HashMap<>();
        try {
            for (String line : Files.readAllLines(CONFIG_PATH)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#"))
                    continue;
                int eq = line.indexOf('=');
                if (eq > 0) {
                    raw.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to read config: " + e.getMessage());
            System.exit(1);
        }

        Config c = new Config();
        c.githubUser = raw.getOrDefault("GITHUB_USER", "");
        c.githubToken = raw.getOrDefault("GITHUB_TOKEN", "");
        c.botUser = raw.getOrDefault("BOT_USER", "");
        c.botToken = raw.getOrDefault("BOT_TOKEN", "");
        c.excludeOrgs = parseSet(raw.getOrDefault("EXCLUDE_ORGS", ""));
        c.gmailAddress = raw.getOrDefault("GMAIL_ADDRESS", "");
        c.gmailAppPassword = raw.getOrDefault("GMAIL_APP_PASSWORD", "");
        c.sendTo = raw.getOrDefault("SEND_TO", "");
        c.activeHours = raw.getOrDefault("ACTIVE_HOURS", "08-18");
        c.workDir = Path.of(raw.getOrDefault("WORK_DIR", "/tmp/github-worker"));
        c.lookbackDays = Integer.parseInt(raw.getOrDefault("LOOKBACK_DAYS", "7"));
        c.agent = raw.getOrDefault("AGENT", "claude");
        return c;
    }

    void validate(boolean requireEmail) {
        requireField("GITHUB_USER", githubUser);
        requireField("GITHUB_TOKEN", githubToken);
        requireField("BOT_USER", botUser);
        requireField("BOT_TOKEN", botToken);
        if (requireEmail) {
            requireField("GMAIL_ADDRESS", gmailAddress);
            requireField("GMAIL_APP_PASSWORD", gmailAppPassword);
            requireField("SEND_TO", sendTo);
        }
    }

    boolean isWithinActiveHours() {
        try {
            String[] parts = activeHours.split("-");
            int start = Integer.parseInt(parts[0]);
            int end = Integer.parseInt(parts[1]);
            int hour = LocalTime.now().getHour();
            return start <= hour && hour < end;
        } catch (Exception e) {
            return true;
        }
    }

    private static Set<String> parseSet(String csv) {
        return Set.of(csv.split(",")).stream()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private static void requireField(String name, String value) {
        if (value == null || value.isEmpty() || "CHANGE_ME".equals(value)) {
            System.err.println("Error: " + name + " not configured in " + CONFIG_PATH);
            System.exit(1);
        }
    }
}
