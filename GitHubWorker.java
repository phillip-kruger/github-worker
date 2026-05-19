///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.6
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//DEPS org.eclipse.angus:angus-mail:2.0.3
//SOURCES Config.java
//SOURCES GitHubClient.java
//SOURCES CodingAgent.java
//SOURCES ClaudeAgent.java
//SOURCES IssueWorkflow.java
//SOURCES ReviewWorkflow.java
//SOURCES WorkflowState.java
//SOURCES SecurityTriage.java
//SOURCES Notifier.java

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "github-worker", mixinStandardHelpOptions = true, version = "1.0.0",
        description = "Automated GitHub issue fixing and PR reviewing using Claude Code")
public class GitHubWorker implements Callable<Integer> {

    @Option(names = "--preview", description = "Show eligible work without processing")
    boolean preview;

    @Option(names = "--once", description = "Process only one item then exit")
    boolean once;

    @Option(names = "--dry-run", description = "Run full logic but skip all write operations")
    boolean dryRun;

    @Option(names = "--status", description = "Show current state of tracked issues and reviews")
    boolean status;

    @Option(names = "--discover", description = "Run topic-based discovery and update state")
    boolean discover;

    @Override
    public Integer call() {
        Config config = Config.load();

        if (status) {
            return showStatus(config);
        }

        if (discover) {
            return runDiscovery(config);
        }

        config.validate(!preview && !dryRun);

        if (!preview && !dryRun && !config.isWithinActiveHours()) {
            System.out.println("Outside active hours (" + config.activeHours + "), exiting.");
            return 0;
        }

        WorkflowState.Lock lock = null;
        try {
            lock = WorkflowState.Lock.tryAcquire(Config.LOCK_PATH);
            if (lock == null) {
                System.out.println("Another instance is already running, exiting.");
                return 0;
            }

            return run(config);
        } catch (IOException e) {
            System.err.println("Failed to acquire lock: " + e.getMessage());
            return 1;
        } finally {
            if (lock != null) {
                try { lock.close(); } catch (IOException ignored) {}
            }
        }
    }

    private int run(Config config) {
        if (!Files.isDirectory(config.workDir)) {
            createWorkDir(config);
        }

        GitHubClient gh = new GitHubClient(config);
        CodingAgent claude = CodingAgent.create(config);
        SecurityTriage security = new SecurityTriage(claude);
        Notifier notifier = new Notifier(config);
        IssueWorkflow issueWorkflow = new IssueWorkflow(gh, claude, security, notifier, config, dryRun);
        ReviewWorkflow reviewWorkflow = new ReviewWorkflow(gh, claude, config, dryRun);

        WorkflowState state = WorkflowState.load(Config.STATE_PATH);
        state.prune(config.lookbackDays);

        List<Notifier.ProcessingResult> results = new ArrayList<>();
        int processed = 0;

        // 1. Discover new issues
        System.out.println("Fetching issues assigned to " + config.githubUser + "...");
        List<JsonNode> issues = gh.fetchAssignedIssues();
        System.out.println("Found " + issues.size() + " assigned issues.");

        for (JsonNode issue : issues) {
            String ownerRepo = issue.path("repository").path("nameWithOwner").asText("");
            int number = issue.path("number").asInt();
            String key = ownerRepo + "#" + number;
            String title = issue.path("title").asText("");

            if (state.issues.containsKey(key)) continue;
            if (gh.alreadyHasPR(ownerRepo, number)) {
                System.out.println("  " + key + ": Skipping — PR already exists.");
                continue;
            }

            boolean selfAssigned = gh.wasSelfAssigned(ownerRepo, number);
            boolean eyesReacted = gh.wasEyesReactedByUser(ownerRepo, number);

            if (!selfAssigned && !eyesReacted) {
                System.out.println("  " + key + ": Skipping — not self-assigned, no 👀.");
                continue;
            }

            System.out.println("  " + key + ": Eligible (" + (selfAssigned ? "self-assigned" : "👀") + ")");

            if (preview) continue;

            WorkflowState.IssueEntry entry = new WorkflowState.IssueEntry();
            entry.title = title;
            entry.ownerRepo = ownerRepo;
            entry.issueNumber = number;
            state.issues.put(key, entry);
        }

        // 2. Discover new review requests
        System.out.println("\nFetching review requests for " + config.githubUser + "...");
        List<JsonNode> reviewPRs = gh.fetchReviewRequests();
        System.out.println("Found " + reviewPRs.size() + " review requests.");

        for (JsonNode pr : reviewPRs) {
            String ownerRepo = pr.path("repository").path("nameWithOwner").asText("");
            int number = pr.path("number").asInt();
            String key = ownerRepo + "#" + number;
            String title = pr.path("title").asText("");

            if (state.reviews.containsKey(key)) continue;

            boolean selfRequested = gh.wasReviewSelfRequested(ownerRepo, number);
            boolean eyesReacted = gh.wasEyesReactedByUser(ownerRepo, number);

            if (!selfRequested && !eyesReacted) {
                System.out.println("  " + key + ": Skipping — not self-requested, no 👀.");
                continue;
            }

            System.out.println("  " + key + ": Eligible (" + (selfRequested ? "self-requested" : "👀") + ")");

            if (preview) continue;

            WorkflowState.ReviewEntry entry = new WorkflowState.ReviewEntry();
            entry.title = title;
            entry.ownerRepo = ownerRepo;
            entry.prNumber = number;

            JsonNode extras = gh.fetchPRExtras(ownerRepo, number);
            if (extras != null) {
                entry.headBranch = extras.path("headRefName").asText("");
                entry.author = extras.path("author").path("login").asText("");
            }

            state.reviews.put(key, entry);
        }

        // 2b. Find items with 👀 reaction via GraphQL
        System.out.println("\nChecking for 👀 reactions in configured repos...");
        List<JsonNode> eyesItems = gh.fetchEyesReactedItems();
        for (JsonNode item : eyesItems) {
            String ownerRepo = item.path("ownerRepo").asText("");
            int number = item.path("number").asInt();
            String key = ownerRepo + "#" + number;
            String title = item.path("title").asText("");
            String type = item.path("type").asText("issue");

            if (state.issues.containsKey(key) || state.reviews.containsKey(key)) continue;

            System.out.println("  " + key + ": Found 👀 — adding to tracking as " + type);

            if (!preview) {
                if ("pr".equals(type)) {
                    WorkflowState.ReviewEntry re = new WorkflowState.ReviewEntry();
                    re.title = title;
                    re.ownerRepo = ownerRepo;
                    re.prNumber = number;
                    JsonNode extras = gh.fetchPRExtras(ownerRepo, number);
                    if (extras != null) {
                        re.headBranch = extras.path("headRefName").asText("");
                        re.author = extras.path("author").path("login").asText("");
                    }
                    state.reviews.put(key, re);
                } else {
                    WorkflowState.IssueEntry ie = new WorkflowState.IssueEntry();
                    ie.title = title;
                    ie.ownerRepo = ownerRepo;
                    ie.issueNumber = number;
                    state.issues.put(key, ie);
                }
            }
        }

        // 2c. Promote discovered items that have 👀 reaction to tracked issues/reviews
        if (!state.discoveries.isEmpty()) {
            var toPromote = new java.util.ArrayList<String>();
            for (var entry : state.discoveries.entrySet()) {
                String key = entry.getKey();
                var d = entry.getValue();
                if (state.issues.containsKey(key) || state.reviews.containsKey(key)) continue;
                if (!gh.wasEyesReactedByUser(d.ownerRepo, d.number)) continue;

                System.out.println("  Promoting discovered item with 👀: " + key);
                toPromote.add(key);

                if ("pr".equals(d.type)) {
                    WorkflowState.ReviewEntry re = new WorkflowState.ReviewEntry();
                    re.title = d.title;
                    re.ownerRepo = d.ownerRepo;
                    re.prNumber = d.number;
                    JsonNode extras = gh.fetchPRExtras(d.ownerRepo, d.number);
                    if (extras != null) {
                        re.headBranch = extras.path("headRefName").asText("");
                        re.author = extras.path("author").path("login").asText("");
                    }
                    state.reviews.put(key, re);
                } else {
                    WorkflowState.IssueEntry ie = new WorkflowState.IssueEntry();
                    ie.title = d.title;
                    ie.ownerRepo = d.ownerRepo;
                    ie.issueNumber = d.number;
                    state.issues.put(key, ie);
                }
            }
            for (String key : toPromote) {
                state.discoveries.remove(key);
            }
            if (!toPromote.isEmpty()) {
                System.out.println("  Promoted " + toPromote.size() + " discovered item(s) to tracking.");
            }
        }

        // 2d. Re-activate DONE reviews that have been re-requested
        for (var entry : state.reviews.entrySet()) {
            WorkflowState.ReviewEntry review = entry.getValue();
            if (review.state != WorkflowState.ReviewState.DONE) continue;
            if (gh.isBotReviewRequested(review.ownerRepo, review.prNumber)) {
                System.out.println("  " + entry.getKey() + ": Re-requested for review — reactivating.");
                review.state = WorkflowState.ReviewState.NEW;
                review.lastUpdated = java.time.Instant.now();
            }
        }

        if (preview) {
            System.out.println("\nTracked issues:");
            for (var e : state.issues.entrySet()) {
                System.out.println("  " + e.getKey() + " [" + e.getValue().state + "] — " + e.getValue().title);
            }
            System.out.println("Tracked reviews:");
            for (var e : state.reviews.entrySet()) {
                System.out.println("  " + e.getKey() + " [" + e.getValue().state + "] — " + e.getValue().title);
            }
            return 0;
        }

        // 3. Advance tracked issues
        for (var entry : state.issues.entrySet()) {
            String key = entry.getKey();
            WorkflowState.IssueEntry issue = entry.getValue();
            if (issue.state == WorkflowState.IssueState.DONE) continue;

            WorkflowState.IssueState before = issue.state;
            try {
                WorkflowState.IssueState after = issueWorkflow.advance(issue);
                issue.state = after;
                if (before != after) {
                    results.add(new Notifier.ProcessingResult(
                            issue.ownerRepo, "#" + issue.issueNumber, issue.title,
                            "https://github.com/" + issue.ownerRepo + "/issues/" + issue.issueNumber,
                            issue.prUrl, before.name(), after.name()));
                    processed++;
                }
            } catch (Exception e) {
                System.err.println("Error processing " + key + ": " + e.getMessage());
            }

            saveState(state);

            if (once && processed > 0) break;
        }

        // 4. Advance tracked reviews
        if (!once || processed == 0) {
            for (var entry : state.reviews.entrySet()) {
                String key = entry.getKey();
                WorkflowState.ReviewEntry review = entry.getValue();
                if (review.state == WorkflowState.ReviewState.DONE) continue;

                WorkflowState.ReviewState before = review.state;
                try {
                    WorkflowState.ReviewState after = reviewWorkflow.advance(review);
                    review.state = after;
                    if (before != after) {
                        results.add(new Notifier.ProcessingResult(
                                review.ownerRepo, "PR#" + review.prNumber, review.title,
                                "https://github.com/" + review.ownerRepo + "/pull/" + review.prNumber,
                                null, before.name(), after.name()));
                        processed++;
                    }
                } catch (Exception e) {
                    System.err.println("Error processing " + key + ": " + e.getMessage());
                }

                saveState(state);

                if (once && processed > 0) break;
            }
        }

        // 5. Send email summary
        if (!results.isEmpty() && !dryRun && config.emailNotifications) {
            System.out.println("\nSending summary email...");
            notifier.sendSummary(results);
        }

        if (results.isEmpty()) {
            System.out.println("\nNo state changes this run.");
        } else {
            System.out.println("\n" + results.size() + " item(s) advanced.");
        }

        System.out.println("Done!");
        return 0;
    }

    private int runDiscovery(Config config) {
        config.validate(false);
        GitHubClient gh = new GitHubClient(config);
        WorkflowState state = WorkflowState.load(Config.STATE_PATH);

        if (config.topics.isEmpty()) {
            System.out.println("No TOPICS configured.");
            return 0;
        }

        System.out.println("Discovering items by topics: " + String.join(", ", config.topics) + "...");
        var discoveries = gh.fetchDiscoveryItems(state);
        state.discoveries.clear();
        for (var d : discoveries) {
            state.discoveries.put(d.ownerRepo + "#" + d.number, d);
        }
        System.out.println("Found " + discoveries.size() + " discoverable item(s).");

        try {
            state.save(Config.STATE_PATH);
        } catch (java.io.IOException e) {
            System.err.println("Failed to save state: " + e.getMessage());
            return 1;
        }
        return 0;
    }

    private int showStatus(Config config) {
        WorkflowState state = WorkflowState.load(Config.STATE_PATH);

        System.out.println("=== GitHub Worker Status ===\n");

        if (state.issues.isEmpty() && state.reviews.isEmpty()) {
            System.out.println("No tracked issues or reviews.");
            return 0;
        }

        if (!state.issues.isEmpty()) {
            System.out.println("Issues (" + state.issues.size() + "):");
            for (var e : state.issues.entrySet()) {
                var issue = e.getValue();
                System.out.printf("  %-40s [%-20s] %s%n",
                        e.getKey(), issue.state, issue.title);
                if (issue.prUrl != null) {
                    System.out.println("    PR: " + issue.prUrl);
                }
                System.out.println("    Updated: " + issue.lastUpdated);
                if (issue.attempts > 0) {
                    System.out.println("    Attempts: " + issue.attempts);
                }
            }
        }

        if (!state.reviews.isEmpty()) {
            System.out.println("\nReviews (" + state.reviews.size() + "):");
            for (var e : state.reviews.entrySet()) {
                var review = e.getValue();
                System.out.printf("  %-40s [%-15s] %s%n",
                        e.getKey(), review.state, review.title);
                System.out.println("    Updated: " + review.lastUpdated);
            }
        }

        return 0;
    }

    private void saveState(WorkflowState state) {
        try {
            state.save(Config.STATE_PATH);
        } catch (IOException e) {
            System.err.println("Warning: Failed to save state: " + e.getMessage());
        }
    }

    private boolean createWorkDir(Config config) {
        try {
            Files.createDirectories(config.workDir);
            return true;
        } catch (IOException e) {
            System.err.println("Failed to create work dir: " + e.getMessage());
            return false;
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new GitHubWorker()).execute(args);
        System.exit(exitCode);
    }
}
