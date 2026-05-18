import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class GitHubClient {

    enum Actor { USER, BOT }

    private final Config config;
    private final ObjectMapper mapper = new ObjectMapper();

    GitHubClient(Config config) {
        this.config = config;
    }

    // --- Core execution ---

    JsonNode ghJson(Actor actor, String... args) {
        String output = ghText(actor, args);
        if (output == null || output.isEmpty()) return null;
        try {
            return mapper.readTree(output);
        } catch (Exception e) {
            return null;
        }
    }

    <T> T ghTyped(Actor actor, TypeReference<T> type, String... args) {
        String output = ghText(actor, args);
        if (output == null || output.isEmpty()) return null;
        try {
            return mapper.readValue(output, type);
        } catch (Exception e) {
            return null;
        }
    }

    String ghText(Actor actor, String... args) {
        try {
            ProcessBuilder pb = buildGhProcess(actor, args);
            Process p = pb.start();
            boolean finished = p.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return null;
            }
            String stdout = new String(p.getInputStream().readAllBytes()).trim();
            if (p.exitValue() != 0) {
                String stderr = new String(p.getErrorStream().readAllBytes()).trim();
                System.err.println("  gh failed: " + stderr);
                return null;
            }
            return stdout;
        } catch (Exception e) {
            System.err.println("  gh error: " + e.getMessage());
            return null;
        }
    }

    int ghExitCode(Actor actor, String... args) {
        try {
            ProcessBuilder pb = buildGhProcess(actor, args);
            Process p = pb.start();
            boolean finished = p.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return -1;
            }
            return p.exitValue();
        } catch (Exception e) {
            return -1;
        }
    }

    private ProcessBuilder buildGhProcess(Actor actor, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("gh");
        Collections.addAll(cmd, args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        String token = (actor == Actor.BOT) ? config.botToken : config.githubToken;
        pb.environment().put("GH_TOKEN", token);
        return pb;
    }

    // --- Git commands with token ---

    String git(Actor actor, Path cwd, String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            Collections.addAll(cmd, args);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(cwd.toFile());
            String token = (actor == Actor.BOT) ? config.botToken : config.githubToken;
            pb.environment().put("GH_TOKEN", token);
            Process p = pb.start();
            boolean finished = p.waitFor(300, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return null;
            }
            String stdout = new String(p.getInputStream().readAllBytes()).trim();
            if (p.exitValue() != 0) {
                String stderr = new String(p.getErrorStream().readAllBytes()).trim();
                System.err.println("  git failed: " + stderr);
                return null;
            }
            return stdout;
        } catch (Exception e) {
            System.err.println("  git error: " + e.getMessage());
            return null;
        }
    }

    // --- Issue discovery ---

    List<JsonNode> fetchAssignedIssues() {
        String since = LocalDate.now().minusDays(config.lookbackDays).format(DateTimeFormatter.ISO_DATE);
        JsonNode result = ghJson(Actor.USER,
                "search", "issues",
                "--assignee", config.githubUser,
                "--created", ">=" + since,
                "--state", "open",
                "--limit", "50",
                "--json", "repository,title,url,number,createdAt,updatedAt,labels");
        if (result == null || !result.isArray()) return List.of();
        List<JsonNode> issues = new ArrayList<>();
        for (JsonNode n : result) {
            String org = n.path("repository").path("nameWithOwner").asText("").split("/")[0];
            if (!config.excludeOrgs.contains(org)) {
                issues.add(n);
            }
        }
        return issues;
    }

    boolean wasSelfAssigned(String ownerRepo, int number) {
        String jq = "[.[] | select(.event == \"assigned\" and .assignee.login == \""
                + config.githubUser + "\") | .actor.login] | last";
        String result = ghText(Actor.USER,
                "api", "repos/" + ownerRepo + "/issues/" + number + "/timeline",
                "--paginate", "--jq", jq);
        if (result == null) return false;
        return result.replace("\"", "").trim().equals(config.githubUser);
    }

    boolean wasEyesReactedByUser(String ownerRepo, int number) {
        String jq = "[.[] | select(.user.login == \"" + config.githubUser
                + "\" and .content == \"eyes\")]";
        String result = ghText(Actor.USER,
                "api", "repos/" + ownerRepo + "/issues/" + number + "/reactions",
                "--paginate", "--jq", jq);
        if (result == null || result.isEmpty()) return false;
        try {
            JsonNode arr = mapper.readTree(result);
            return arr.isArray() && arr.size() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    boolean alreadyHasPR(String ownerRepo, int issueNumber) {
        String branch = "fix/" + issueNumber;
        JsonNode prs = ghJson(Actor.BOT,
                "pr", "list",
                "--repo", ownerRepo,
                "--head", config.botUser + ":" + branch,
                "--json", "number");
        return prs != null && prs.isArray() && prs.size() > 0;
    }

    // --- Review discovery ---

    List<JsonNode> fetchReviewRequests() {
        String since = LocalDate.now().minusDays(config.lookbackDays).format(DateTimeFormatter.ISO_DATE);
        JsonNode result = ghJson(Actor.USER,
                "search", "prs",
                "--review-requested", config.githubUser,
                "--state", "open",
                "--created", ">=" + since,
                "--limit", "50",
                "--json", "repository,number,title,url");
        if (result == null || !result.isArray()) return List.of();
        List<JsonNode> prs = new ArrayList<>();
        for (JsonNode n : result) {
            String org = n.path("repository").path("nameWithOwner").asText("").split("/")[0];
            if (!config.excludeOrgs.contains(org)) {
                prs.add(n);
            }
        }
        return prs;
    }

    /**
     * Fetch headRefName and author for a PR (not available via gh search).
     */
    JsonNode fetchPRExtras(String ownerRepo, int prNumber) {
        return ghJson(Actor.USER,
                "pr", "view", String.valueOf(prNumber),
                "--repo", ownerRepo,
                "--json", "headRefName,author");
    }

    boolean wasReviewSelfRequested(String ownerRepo, int prNumber) {
        String jq = "[.[] | select(.event == \"review_requested\" and .requested_reviewer.login == \""
                + config.githubUser + "\") | .actor.login] | last";
        String result = ghText(Actor.USER,
                "api", "repos/" + ownerRepo + "/issues/" + prNumber + "/timeline",
                "--paginate", "--jq", jq);
        return result != null && result.replace("\"", "").trim().equals(config.githubUser);
    }

    // --- Discovery ---

    List<WorkflowState.DiscoveryEntry> fetchDiscoveryItems(WorkflowState state) {
        String since = LocalDate.now().minusDays(config.lookbackDays).format(DateTimeFormatter.ISO_DATE);
        Map<String, WorkflowState.DiscoveryEntry> results = new LinkedHashMap<>();

        // 1. Issues/PRs mentioning the user (scoped to orgs if configured)
        for (String org : config.orgs.isEmpty() ? List.of("") : config.orgs) {
            List<String> issueArgs = new ArrayList<>(List.of("search", "issues",
                    "--mention", config.githubUser,
                    "--created", ">=" + since, "--state", "open", "--limit", "20",
                    "--json", "repository,number,title,url"));
            if (!org.isEmpty()) issueArgs.addAll(List.of("--owner", org));
            addDiscoveries(results, state, "issue", "mention",
                    ghJson(Actor.USER, issueArgs.toArray(new String[0])));

            List<String> prArgs = new ArrayList<>(List.of("search", "prs",
                    "--mention", config.githubUser,
                    "--created", ">=" + since, "--state", "open", "--limit", "20",
                    "--json", "repository,number,title,url"));
            if (!org.isEmpty()) prArgs.addAll(List.of("--owner", org));
            addDiscoveries(results, state, "pr", "mention",
                    ghJson(Actor.USER, prArgs.toArray(new String[0])));
        }

        // 2. Per-topic searches (scoped to orgs)
        for (String topic : config.topics) {
            for (String org : config.orgs.isEmpty() ? List.of("") : config.orgs) {
                List<String> issueArgs = new ArrayList<>(List.of("search", "issues",
                        topic, "--created", ">=" + since, "--state", "open", "--limit", "10",
                        "--json", "repository,number,title,url"));
                if (!org.isEmpty()) issueArgs.addAll(List.of("--owner", org));
                addDiscoveries(results, state, "issue", topic,
                        ghJson(Actor.USER, issueArgs.toArray(new String[0])));

                List<String> prArgs = new ArrayList<>(List.of("search", "prs",
                        topic, "--created", ">=" + since, "--state", "open", "--limit", "10",
                        "--json", "repository,number,title,url"));
                if (!org.isEmpty()) prArgs.addAll(List.of("--owner", org));
                addDiscoveries(results, state, "pr", topic,
                        ghJson(Actor.USER, prArgs.toArray(new String[0])));
            }
        }

        return new ArrayList<>(results.values());
    }

    private void addDiscoveries(Map<String, WorkflowState.DiscoveryEntry> results,
                                WorkflowState state, String type, String source, JsonNode searchResult) {
        if (searchResult == null || !searchResult.isArray()) return;
        for (JsonNode n : searchResult) {
            String ownerRepo = n.path("repository").path("nameWithOwner").asText("");
            int number = n.path("number").asInt();
            String key = ownerRepo + "#" + number;

            if (config.excludeOrgs.contains(ownerRepo.split("/")[0])) continue;
            if (state.issues.containsKey(key)) continue;
            if (state.reviews.containsKey(key)) continue;
            if (results.containsKey(key)) continue;

            WorkflowState.DiscoveryEntry entry = new WorkflowState.DiscoveryEntry();
            entry.title = n.path("title").asText("");
            entry.ownerRepo = ownerRepo;
            entry.number = number;
            entry.url = n.path("url").asText("");
            entry.type = type;
            entry.source = source;
            entry.matchedTopic = "mention".equals(source) ? null : source;
            results.put(key, entry);
        }
    }

    // --- Issue details ---

    JsonNode getIssueDetails(String ownerRepo, int number) {
        return ghJson(Actor.USER,
                "issue", "view", String.valueOf(number),
                "--repo", ownerRepo,
                "--json", "title,body,comments,labels,assignees");
    }

    JsonNode getPRDetails(String ownerRepo, int number) {
        return ghJson(Actor.USER,
                "pr", "view", String.valueOf(number),
                "--repo", ownerRepo,
                "--json", "title,body,comments,reviews,headRefName,author,state,mergeable,statusCheckRollup");
    }

    // --- Bot actions ---

    Long postComment(String ownerRepo, int number, String body) {
        String result = ghText(Actor.BOT,
                "api", "repos/" + ownerRepo + "/issues/" + number + "/comments",
                "-X", "POST",
                "-f", "body=" + body);
        if (result == null) return null;
        try {
            return mapper.readTree(result).path("id").asLong();
        } catch (Exception e) {
            return null;
        }
    }

    void addReaction(String ownerRepo, long commentId, String reaction) {
        ghText(Actor.BOT,
                "api", "repos/" + ownerRepo + "/issues/comments/" + commentId + "/reactions",
                "-X", "POST",
                "-f", "content=" + reaction);
    }

    // --- Comment reaction checks ---

    boolean hasThumbsUpFromUser(String ownerRepo, long commentId) {
        return hasReactionFromUser(ownerRepo, commentId, "+1");
    }

    boolean hasThumbsDownFromUser(String ownerRepo, long commentId) {
        return hasReactionFromUser(ownerRepo, commentId, "-1");
    }

    private boolean hasReactionFromUser(String ownerRepo, long commentId, String content) {
        String jq = "[.[] | select(.user.login == \"" + config.githubUser
                + "\" and .content == \"" + content + "\")]";
        String result = ghText(Actor.USER,
                "api", "repos/" + ownerRepo + "/issues/comments/" + commentId + "/reactions",
                "--jq", jq);
        if (result == null || result.isEmpty()) return false;
        try {
            JsonNode arr = mapper.readTree(result);
            return arr.isArray() && arr.size() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    String getUserCommentAfter(String ownerRepo, int number, long afterCommentId) {
        String jq = "[.[] | select(.user.login == \"" + config.githubUser + "\") | {id: .id, body: .body}]";
        String result = ghText(Actor.USER,
                "api", "repos/" + ownerRepo + "/issues/" + number + "/comments",
                "--paginate", "--jq", jq);
        if (result == null) return null;
        try {
            JsonNode arr = mapper.readTree(result);
            for (JsonNode c : arr) {
                if (c.path("id").asLong() > afterCommentId) {
                    return c.path("body").asText();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // --- PR operations ---

    String createDraftPR(String ownerRepo, String defaultBranch, int issueNumber, String title, String body) {
        String head = config.botUser + ":fix/" + issueNumber;
        return ghText(Actor.BOT,
                "pr", "create",
                "--repo", ownerRepo,
                "--base", defaultBranch,
                "--head", head,
                "--title", title,
                "--body", body,
                "--draft");
    }

    void markPRReady(String ownerRepo, int prNumber) {
        ghText(Actor.BOT, "pr", "ready", String.valueOf(prNumber), "--repo", ownerRepo);
    }

    void addReviewer(String ownerRepo, int prNumber, String reviewer) {
        ghText(Actor.BOT,
                "pr", "edit", String.valueOf(prNumber),
                "--repo", ownerRepo,
                "--add-reviewer", reviewer);
    }

    void requestReview(String ownerRepo, int prNumber, String reviewer) {
        ghText(Actor.BOT,
                "api", "repos/" + ownerRepo + "/pulls/" + prNumber + "/requested_reviewers",
                "-X", "POST",
                "-f", "reviewers[]=" + reviewer);
    }

    void postPRReview(String ownerRepo, int prNumber, String body) {
        ghText(Actor.BOT,
                "pr", "review", String.valueOf(prNumber),
                "--repo", ownerRepo,
                "--comment",
                "--body", body);
    }

    // --- PR status checks ---

    JsonNode getPRReviews(String ownerRepo, int prNumber) {
        return ghJson(Actor.USER,
                "pr", "view", String.valueOf(prNumber),
                "--repo", ownerRepo,
                "--json", "reviews");
    }

    enum ReviewVerdict { APPROVED, CHANGES_REQUESTED, NONE }

    ReviewVerdict getUserReviewVerdict(String ownerRepo, int prNumber) {
        JsonNode data = getPRReviews(ownerRepo, prNumber);
        if (data == null) return ReviewVerdict.NONE;
        JsonNode reviews = data.path("reviews");
        if (!reviews.isArray()) return ReviewVerdict.NONE;

        // Find the latest review from the user
        String latestState = null;
        for (JsonNode r : reviews) {
            if (config.githubUser.equals(r.path("author").path("login").asText())) {
                latestState = r.path("state").asText();
            }
        }

        if ("APPROVED".equals(latestState)) return ReviewVerdict.APPROVED;
        if ("CHANGES_REQUESTED".equals(latestState)) return ReviewVerdict.CHANGES_REQUESTED;
        return ReviewVerdict.NONE;
    }

    /**
     * Get all feedback from the user on a PR — both issue comments (conversation)
     * and PR review comments (line-level), that the bot hasn't reacted 👍 to yet.
     */
    List<JsonNode> getUnprocessedPRComments(String ownerRepo, int prNumber) {
        List<JsonNode> unprocessed = new ArrayList<>();

        // 1. Issue comments (conversation tab)
        String jq = "[.[] | select(.user.login == \"" + config.githubUser
                + "\") | {id: .id, body: .body, created_at: .created_at, type: \"issue\"}]";
        String result = ghText(Actor.USER,
                "api", "repos/" + ownerRepo + "/issues/" + prNumber + "/comments",
                "--paginate", "--jq", jq);
        if (result != null) {
            try {
                for (JsonNode c : mapper.readTree(result)) {
                    if (!hasBotReaction(ownerRepo, c.path("id").asLong(), "issues")) {
                        unprocessed.add(c);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 2. PR review comments (line-level comments on the diff)
        String jq2 = "[.[] | select(.user.login == \"" + config.githubUser
                + "\") | {id: .id, body: .body, path: .path, created_at: .created_at, type: \"review\"}]";
        String result2 = ghText(Actor.USER,
                "api", "repos/" + ownerRepo + "/pulls/" + prNumber + "/comments",
                "--paginate", "--jq", jq2);
        if (result2 != null) {
            try {
                for (JsonNode c : mapper.readTree(result2)) {
                    if (!hasBotReaction(ownerRepo, c.path("id").asLong(), "pulls")) {
                        unprocessed.add(c);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 3. PR review body text (the summary comment on a review)
        String jq3 = "[.[] | select(.user.login == \"" + config.githubUser
                + "\" and .body != \"\" and .body != null) | {id: .id, body: .body, state: .state}]";
        String result3 = ghText(Actor.USER,
                "api", "repos/" + ownerRepo + "/pulls/" + prNumber + "/reviews",
                "--jq", jq3);
        if (result3 != null) {
            try {
                for (JsonNode c : mapper.readTree(result3)) {
                    // Include review bodies as feedback (no reaction tracking for these)
                    unprocessed.add(c);
                }
            } catch (Exception ignored) {
            }
        }

        return unprocessed;
    }

    void reactToPRComment(String ownerRepo, long commentId) {
        ghText(Actor.BOT,
                "api", "repos/" + ownerRepo + "/pulls/comments/" + commentId + "/reactions",
                "-X", "POST",
                "-f", "content=+1");
    }

    private boolean hasBotReaction(String ownerRepo, long commentId, String commentType) {
        String endpoint = "issues".equals(commentType)
                ? "repos/" + ownerRepo + "/issues/comments/" + commentId + "/reactions"
                : "repos/" + ownerRepo + "/pulls/comments/" + commentId + "/reactions";
        String rjq = "[.[] | select(.user.login == \"" + config.botUser
                + "\" and .content == \"+1\")]";
        String rResult = ghText(Actor.BOT, "api", endpoint, "--jq", rjq);
        if (rResult == null || rResult.isEmpty()) return false;
        try {
            JsonNode arr = mapper.readTree(rResult);
            return arr.isArray() && arr.size() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    // --- CI checks ---

    enum CIStatus { PASS, FAIL, PENDING }

    CIStatus getCIStatus(String ownerRepo, int prNumber) {
        JsonNode data = ghJson(Actor.USER,
                "pr", "view", String.valueOf(prNumber),
                "--repo", ownerRepo,
                "--json", "statusCheckRollup");
        if (data == null) return CIStatus.PENDING;
        JsonNode checks = data.path("statusCheckRollup");
        if (!checks.isArray() || checks.isEmpty()) return CIStatus.PENDING;

        boolean anyFailed = false;
        boolean anyPending = false;
        for (JsonNode check : checks) {
            String status = check.path("status").asText("");
            String conclusion = check.path("conclusion").asText("");
            if ("COMPLETED".equalsIgnoreCase(status)) {
                if (!"SUCCESS".equalsIgnoreCase(conclusion) && !"NEUTRAL".equalsIgnoreCase(conclusion)
                        && !"SKIPPED".equalsIgnoreCase(conclusion)) {
                    anyFailed = true;
                }
            } else {
                anyPending = true;
            }
        }
        if (anyFailed) return CIStatus.FAIL;
        if (anyPending) return CIStatus.PENDING;
        return CIStatus.PASS;
    }

    String getCIFailureDetails(String ownerRepo, int prNumber) {
        JsonNode data = ghJson(Actor.USER,
                "pr", "checks", String.valueOf(prNumber),
                "--repo", ownerRepo,
                "--json", "name,state,conclusion,detailsUrl");
        if (data == null) return "Could not fetch CI details";
        StringBuilder sb = new StringBuilder();
        if (data.isArray()) {
            for (JsonNode check : data) {
                String conclusion = check.path("conclusion").asText("");
                if ("FAILURE".equalsIgnoreCase(conclusion) || "ERROR".equalsIgnoreCase(conclusion)) {
                    sb.append("- ").append(check.path("name").asText("?"))
                            .append(": ").append(conclusion)
                            .append(" (").append(check.path("detailsUrl").asText("")).append(")\n");
                }
            }
        }
        return sb.length() > 0 ? sb.toString() : "CI failures detected but details unavailable";
    }

    // --- Repo operations (worktree-based) ---
    //
    // Instead of full clones per issue, we keep a persistent "main" clone per repo
    // under {workDir}/repos/{owner}/{repo}/ and create lightweight git worktrees
    // under {workDir}/worktrees/{owner}_{repo}_{issue}/ for each issue.
    // For Quarkus/Maven projects, each worktree gets an isolated .m2 directory
    // so SNAPSHOT builds don't pollute each other.

    String getDefaultBranch(String ownerRepo) {
        String result = ghText(Actor.USER, "api", "repos/" + ownerRepo, "--jq", ".default_branch");
        return (result != null && !result.isEmpty()) ? result.replace("\"", "").trim() : "main";
    }

    private Path mainCloneDir(String ownerRepo) {
        return config.workDir.resolve("repos").resolve(ownerRepo);
    }

    private Path worktreeDir(String ownerRepo, int number) {
        return config.workDir.resolve("worktrees").resolve(ownerRepo.replace("/", "_") + "_" + number);
    }

    private Path worktreeM2(String ownerRepo, int number) {
        return config.workDir.resolve("m2").resolve(ownerRepo.replace("/", "_") + "_" + number);
    }

    /**
     * Ensure we have a persistent main clone of the repo with both the bot's fork (origin)
     * and the upstream repo as remotes. If it already exists, just fetch.
     */
    private Path ensureMainClone(String ownerRepo) throws IOException {
        Path mainDir = mainCloneDir(ownerRepo);

        if (java.nio.file.Files.isDirectory(mainDir.resolve(".git"))) {
            String defaultBranch = getDefaultBranch(ownerRepo);
            git(Actor.BOT, mainDir, "fetch", "upstream", defaultBranch);
            git(Actor.BOT, mainDir, "fetch", "origin");
            git(Actor.BOT, mainDir, "checkout", defaultBranch);
            git(Actor.BOT, mainDir, "reset", "--hard", "upstream/" + defaultBranch);
            return mainDir;
        }

        java.nio.file.Files.createDirectories(mainDir.getParent());

        ghText(Actor.BOT, "repo", "fork", ownerRepo, "--default-branch-only");

        String forkRepo = config.botUser + "/" + ownerRepo.split("/")[1];
        String httpsOrigin = "https://x-access-token:" + config.botToken + "@github.com/" + forkRepo + ".git";
        String httpsUpstream = "https://github.com/" + ownerRepo + ".git";

        if (ghExitCode(Actor.BOT, "repo", "clone", forkRepo, mainDir.toString()) != 0) {
            return null;
        }

        // Switch origin to HTTPS with embedded token so git push works
        git(Actor.BOT, mainDir, "remote", "set-url", "origin", httpsOrigin);
        git(Actor.BOT, mainDir, "remote", "add", "upstream", httpsUpstream);

        String defaultBranch = getDefaultBranch(ownerRepo);
        git(Actor.BOT, mainDir, "fetch", "upstream", defaultBranch);
        git(Actor.BOT, mainDir, "checkout", defaultBranch);
        git(Actor.BOT, mainDir, "reset", "--hard", "upstream/" + defaultBranch);

        return mainDir;
    }

    /**
     * Set up Maven dependency isolation for a worktree by creating
     * .mvn/maven.config with a worktree-scoped local repo.
     */
    private void setupMavenIsolation(Path wtDir, String ownerRepo, int number) throws IOException {
        if (!java.nio.file.Files.exists(wtDir.resolve("pom.xml"))) return;

        Path m2Dir = worktreeM2(ownerRepo, number);
        java.nio.file.Files.createDirectories(m2Dir);

        Path mvnDir = wtDir.resolve(".mvn");
        java.nio.file.Files.createDirectories(mvnDir);
        java.nio.file.Files.writeString(mvnDir.resolve("maven.config"),
                "-Dmaven.repo.local=" + m2Dir.toAbsolutePath() + "\n");
    }

    Path cloneForIssue(String ownerRepo, int issueNumber) throws IOException {
        Path mainDir = ensureMainClone(ownerRepo);
        if (mainDir == null) return null;

        Path wtDir = worktreeDir(ownerRepo, issueNumber);
        removeWorktree(mainDir, wtDir);

        String defaultBranch = getDefaultBranch(ownerRepo);
        String branch = "fix/" + issueNumber;

        // Delete stale branch from a previous attempt if it exists
        git(Actor.BOT, mainDir, "branch", "-D", branch);

        java.nio.file.Files.createDirectories(wtDir.getParent());
        String result = git(Actor.BOT, mainDir,
                "worktree", "add", wtDir.toAbsolutePath().toString(),
                "-b", branch, "upstream/" + defaultBranch);
        if (result == null) return null;

        setupMavenIsolation(wtDir, ownerRepo, issueNumber);
        return wtDir;
    }

    Path cloneForExistingPR(String ownerRepo, int issueNumber) throws IOException {
        Path mainDir = ensureMainClone(ownerRepo);
        if (mainDir == null) return null;

        Path wtDir = worktreeDir(ownerRepo, issueNumber);
        removeWorktree(mainDir, wtDir);

        String branch = "fix/" + issueNumber;

        git(Actor.BOT, mainDir, "fetch", "origin", branch);

        java.nio.file.Files.createDirectories(wtDir.getParent());
        String result = git(Actor.BOT, mainDir,
                "worktree", "add", wtDir.toAbsolutePath().toString(), branch);
        if (result == null) {
            result = git(Actor.BOT, mainDir,
                    "worktree", "add", wtDir.toAbsolutePath().toString(),
                    "-B", branch, "origin/" + branch);
            if (result == null) return null;
        }

        setupMavenIsolation(wtDir, ownerRepo, issueNumber);
        return wtDir;
    }

    Path cloneForReview(String ownerRepo, int prNumber, String headBranch, String author) throws IOException {
        Path wtDir = worktreeDir(ownerRepo, prNumber);

        // For reviews, we need a clone with the PR checked out — use the upstream repo
        Path mainDir = mainCloneDir(ownerRepo);
        if (!java.nio.file.Files.isDirectory(mainDir.resolve(".git"))) {
            java.nio.file.Files.createDirectories(mainDir.getParent());
            if (ghExitCode(Actor.USER, "repo", "clone", ownerRepo, mainDir.toString()) != 0) {
                return null;
            }
        }

        removeWorktree(mainDir, wtDir);

        // Delete stale PR branch and fetch fresh
        git(Actor.USER, mainDir, "branch", "-D", "pr-" + prNumber);
        git(Actor.USER, mainDir, "fetch", "origin", "pull/" + prNumber + "/head:pr-" + prNumber);

        java.nio.file.Files.createDirectories(wtDir.getParent());
        String result = git(Actor.USER, mainDir,
                "worktree", "add", wtDir.toAbsolutePath().toString(), "pr-" + prNumber);
        if (result == null) return null;

        setupMavenIsolation(wtDir, ownerRepo, prNumber);
        return wtDir;
    }

    boolean pushForceLease(String ownerRepo, int issueNumber, Path repoDir) {
        String branch = "fix/" + issueNumber;
        String result = git(Actor.BOT, repoDir, "push", "--force-with-lease", "-u", "origin", branch);
        return result != null;
    }

    /**
     * Clean up a worktree. Safe to call even if the worktree doesn't exist.
     */
    void cleanupWorktree(String ownerRepo, int number) {
        Path mainDir = mainCloneDir(ownerRepo);
        Path wtDir = worktreeDir(ownerRepo, number);
        removeWorktree(mainDir, wtDir);

        Path m2Dir = worktreeM2(ownerRepo, number);
        if (java.nio.file.Files.isDirectory(m2Dir)) {
            deleteDir(m2Dir);
        }
    }

    private void removeWorktree(Path mainDir, Path wtDir) {
        if (java.nio.file.Files.isDirectory(wtDir)) {
            git(Actor.BOT, mainDir, "worktree", "remove", "--force", wtDir.toAbsolutePath().toString());
            if (java.nio.file.Files.isDirectory(wtDir)) {
                deleteDir(wtDir);
            }
        }
        git(Actor.BOT, mainDir, "worktree", "prune");
    }

    static void deleteDir(Path dir) {
        try {
            java.nio.file.Files.walk(dir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { java.nio.file.Files.delete(p); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {
        }
    }
}
