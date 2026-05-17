///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public class GitHubWorkerUI {

    static final String REPO = "House-elves/github-worker-ui";
    static final String VERSION = "v1.0.0";
    static final String JAR_NAME = "github-worker-ui-1.0.0-runner.jar";
    static final Path INSTALL_DIR = Path.of(System.getProperty("user.home"), ".local", "share", "github-worker-ui");
    static final Path JAR_PATH = INSTALL_DIR.resolve(JAR_NAME);

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--install".equals(args[0])) {
            install();
            return;
        }

        if (!Files.exists(JAR_PATH)) {
            System.out.println("Dashboard not downloaded yet. Fetching...");
            download();
        }

        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-jar");
        cmd.add(JAR_PATH.toString());
        for (String arg : args) cmd.add(arg);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        System.exit(pb.start().waitFor());
    }

    static void install() throws Exception {
        download();
        setupService();
        setupHostname();
        System.out.println("\nDashboard: http://github-worker.house-elves:7478");
    }

    static void download() throws Exception {
        String url = "https://github.com/" + REPO + "/releases/download/" + VERSION + "/" + JAR_NAME;
        System.out.println("Downloading " + JAR_NAME + " from " + url + "...");

        Files.createDirectories(INSTALL_DIR);
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setInstanceFollowRedirects(true);

        if (conn.getResponseCode() == 302 || conn.getResponseCode() == 301) {
            conn = (HttpURLConnection) URI.create(conn.getHeaderField("Location")).toURL().openConnection();
        }

        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, JAR_PATH, StandardCopyOption.REPLACE_EXISTING);
        }

        long size = Files.size(JAR_PATH);
        System.out.printf("Downloaded %.1f MB to %s%n", size / 1024.0 / 1024.0, JAR_PATH);
    }

    static void setupService() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("linux")) {
            setupSystemd();
        } else if (os.contains("mac")) {
            setupLaunchd();
        } else {
            System.out.println("Unsupported OS. Run manually: java -jar " + JAR_PATH);
        }
    }

    static void setupSystemd() throws Exception {
        Path serviceDir = Path.of(System.getProperty("user.home"), ".config", "systemd", "user");
        Files.createDirectories(serviceDir);

        String javaBin = ProcessHandle.current().info().command().orElse("java");

        String unit = """
                [Unit]
                Description=GitHub Worker Dashboard (House Elves)
                After=network.target

                [Service]
                Type=simple
                ExecStart=%s -jar %s
                Environment=HOME=%s
                Restart=on-failure
                RestartSec=5

                [Install]
                WantedBy=default.target
                """.formatted(javaBin, JAR_PATH, System.getProperty("user.home"));

        Files.writeString(serviceDir.resolve("github-worker-ui.service"), unit);

        run("systemctl", "--user", "daemon-reload");
        run("systemctl", "--user", "enable", "--now", "github-worker-ui.service");
        run("loginctl", "enable-linger", System.getProperty("user.name"));
        System.out.println("Systemd service enabled (auto-starts on boot).");
    }

    static void setupLaunchd() throws Exception {
        Path plistDir = Path.of(System.getProperty("user.home"), "Library", "LaunchAgents");
        Files.createDirectories(plistDir);

        String javaBin = ProcessHandle.current().info().command().orElse("java");
        String logDir = Path.of(System.getProperty("user.home"), ".config", "github-worker").toString();

        String plist = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>Label</key>
                    <string>com.house-elves.github-worker-ui</string>
                    <key>ProgramArguments</key>
                    <array>
                        <string>%s</string>
                        <string>-jar</string>
                        <string>%s</string>
                    </array>
                    <key>RunAtLoad</key>
                    <true/>
                    <key>KeepAlive</key>
                    <true/>
                    <key>StandardOutPath</key>
                    <string>%s/ui-stdout.log</string>
                    <key>StandardErrorPath</key>
                    <string>%s/ui-stderr.log</string>
                </dict>
                </plist>
                """.formatted(javaBin, JAR_PATH, logDir, logDir);

        Path plistFile = plistDir.resolve("com.house-elves.github-worker-ui.plist");
        run("launchctl", "unload", plistFile.toString());
        Files.writeString(plistFile, plist);
        run("launchctl", "load", plistFile.toString());
        System.out.println("Launchd agent installed (auto-starts on login).");
    }

    static void setupHostname() throws Exception {
        String hostname = "github-worker.house-elves";
        ProcessBuilder check = new ProcessBuilder("grep", "-q", hostname, "/etc/hosts");
        if (check.start().waitFor() == 0) {
            System.out.println("Hostname " + hostname + " already in /etc/hosts.");
            return;
        }

        System.out.println("\nTo access the dashboard at http://" + hostname + ":7478");
        System.out.print("Add hostname to /etc/hosts? (requires sudo) [Y/n] ");
        int ch = System.in.read();
        if (ch == 'n' || ch == 'N') return;

        ProcessBuilder pb = new ProcessBuilder("sudo", "sh", "-c",
                "echo '127.0.0.1 " + hostname + "' >> /etc/hosts");
        pb.inheritIO();
        pb.start().waitFor();
        System.out.println("Added: 127.0.0.1 " + hostname);
    }

    static void run(String... cmd) throws Exception {
        new ProcessBuilder(cmd).inheritIO().start().waitFor();
    }
}
