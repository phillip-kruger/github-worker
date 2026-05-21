import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public class IssueWorkflow {

    private static final int MAX_ATTEMPTS = 3;

    private final GitHubClient gh;
    private final CodingAgent claude;
    private final SecurityTriage security;
    private final Notifier notifier;
    private final Config config;
    private final boolean dryRun;

    IssueWorkflow(GitHubClient gh, CodingAgent claude, SecurityTriage security,
                  Notifier notifier, Config config, boolean dryRun) {
        this.gh = gh;
        this.claude = claude;
        this.security = security;
        this.notifier = notifier;
        this.config = config;
        this.dryRun = dryRun;
    }

    WorkflowState.IssueState advance(WorkflowState.IssueEntry entry) {
        return switch (entry.state) {
            case NEW -> handleNew(entry);
            case AWAITING_APPROVAL -> handleAwaitingApproval(entry);
            case CODING -> handleCoding(entry);
            case SELF_REVIEWING -> handleSelfReviewing(entry);
            case FIXING_REVIEW -> handleFixingReview(entry);
            case READY_FOR_REVIEW -> handleReadyForReview(entry);
            case ADDRESSING_FEEDBACK -> handleAddressingFeedback(entry);
            case SQUASHING -> handleSquashing(entry);
            case MONITORING_CI -> handleMonitoringCI(entry);
            case FIXING_CI -> handleFixingCI(entry);
            case DONE -> handleDone(entry);
            case MERGED -> WorkflowState.IssueState.MERGED;
        };
    }

    // --- NEW: Analyze issue, post understanding comment ---

    private WorkflowState.IssueState handleNew(WorkflowState.IssueEntry entry) {
        String ownerRepo = entry.ownerRepo;
        int issueNumber = entry.issueNumber;

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Analyzing: " + ownerRepo + "#" + issueNumber + " — " + entry.title);
        System.out.println("=".repeat(60));

        JsonNode details = gh.getIssueDetails(ownerRepo, issueNumber);
        if (details == null) {
            System.out.println("  Could not fetch issue details, skipping this run.");
            return WorkflowState.IssueState.NEW;
        }

        String issueBody = details.path("body").asText("");
        String labels = buildLabelsString(details);
        String commentsText = buildCommentsText(details);

        System.out.println("  Security triage...");
        SecurityTriage.Result triage = security.analyze(issueBody, commentsText, ownerRepo,
                issueNumber, entry.title, labels);

        if (!triage.safe()) {
            System.out.println("  FLAGGED: " + triage.explanation());
            if (!dryRun && config.emailNotifications) {
                notifier.sendSecurityWarning(ownerRepo, issueNumber, entry.title,
                        "https://github.com/" + ownerRepo + "/issues/" + issueNumber,
                        triage.explanation());
            }
            entry.lastUpdated = Instant.now();
            return WorkflowState.IssueState.DONE;
        }
        System.out.println("  Passed: " + triage.explanation());

        String feedbackContext = "";
        if (entry.feedbackText != null && !entry.feedbackText.isEmpty()) {
            feedbackContext = """

                    IMPORTANT: A previous analysis was rejected. The maintainer's feedback was:
                    %s

                    Incorporate this feedback into your new analysis.
                    """.formatted(entry.feedbackText);
        }

        String prompt = """
                You are analyzing a GitHub issue to understand it and propose a fix.

                Repository: %s
                Issue #%d: %s
                Labels: %s

                Issue description:
                %s

                Comments:
                %s
                %s
                Write a comment that:
                1. Summarizes your understanding of the issue (2-3 sentences)
                2. Proposes a solution approach (2-3 bullet points)
                3. Ends with: "@%s Does this look right? React with 👍 to proceed or 👎 if I should reconsider."

                Keep it concise and technical. Do NOT use filler phrases.
                Output ONLY the comment body, nothing else.
                """.formatted(ownerRepo, issueNumber, entry.title, labels,
                issueBody, commentsText, feedbackContext, config.githubUser);

        System.out.println("  Generating understanding...");
        String comment = claude.runBare(prompt);

        if (comment == null || comment.isEmpty()) {
            System.out.println("  Analysis failed, will retry next run.");
            return WorkflowState.IssueState.NEW;
        }

        if (dryRun) {
            System.out.println("  [DRY RUN] Would post comment:\n" + truncate(comment, 500));
            entry.botCommentId = 0L;
        } else {
            Long commentId = gh.postComment(ownerRepo, issueNumber, comment);
            if (commentId == null) {
                System.out.println("  Failed to post comment, will retry next run.");
                return WorkflowState.IssueState.NEW;
            }
            entry.botCommentId = commentId;
            System.out.println("  Posted understanding comment (id: " + commentId + ")");
        }

        entry.lastUpdated = Instant.now();
        return WorkflowState.IssueState.AWAITING_APPROVAL;
    }

    // --- AWAITING_APPROVAL: Check for thumbsup/thumbsdown ---

    private WorkflowState.IssueState handleAwaitingApproval(WorkflowState.IssueEntry entry) {
        String ownerRepo = entry.ownerRepo;
        int issueNumber = entry.issueNumber;

        System.out.println("  Checking approval on " + ownerRepo + "#" + issueNumber + "...");

        if (entry.botCommentId == null || entry.botCommentId == 0) {
            System.out.println("  No bot comment ID recorded, resetting to NEW.");
            return WorkflowState.IssueState.NEW;
        }

        if (gh.hasThumbsUpFromUser(ownerRepo, entry.botCommentId)) {
            System.out.println("  👍 received — proceeding to coding.");
            entry.lastUpdated = Instant.now();
            return WorkflowState.IssueState.CODING;
        }

        if (gh.hasThumbsDownFromUser(ownerRepo, entry.botCommentId)) {
            entry.attempts++;
            if (entry.attempts >= MAX_ATTEMPTS) {
                System.out.println("  👎 received but max attempts (" + MAX_ATTEMPTS + ") reached.");
                if (!dryRun) {
                    gh.postComment(ownerRepo, issueNumber,
                            "I've tried " + MAX_ATTEMPTS + " times to understand this issue but keep getting it wrong. "
                                    + "I'll stop here — @" + config.githubUser + " please provide more guidance or take over.");
                }
                entry.lastUpdated = Instant.now();
                return WorkflowState.IssueState.DONE;
            }

            String feedback = gh.getUserCommentAfter(ownerRepo, issueNumber, entry.botCommentId);
            entry.feedbackText = feedback;
            entry.botCommentId = null;
            entry.lastUpdated = Instant.now();

            System.out.println("  👎 received (attempt " + entry.attempts + "/" + MAX_ATTEMPTS
                    + ") — re-analyzing with feedback.");
            return WorkflowState.IssueState.NEW;
        }

        System.out.println("  No reaction yet, waiting.");
        return WorkflowState.IssueState.AWAITING_APPROVAL;
    }

    // --- CODING: Clone, fix, create draft PR ---

    private WorkflowState.IssueState handleCoding(WorkflowState.IssueEntry entry) {
        String ownerRepo = entry.ownerRepo;
        int issueNumber = entry.issueNumber;

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Coding fix: " + ownerRepo + "#" + issueNumber + " — " + entry.title);
        System.out.println("=".repeat(60));

        JsonNode details = gh.getIssueDetails(ownerRepo, issueNumber);
        if (details == null) {
            System.out.println("  Could not fetch issue details, will retry.");
            return WorkflowState.IssueState.CODING;
        }

        String issueBody = details.path("body").asText("");
        String labels = buildLabelsString(details);
        String commentsText = buildCommentsText(details);
        String defaultBranch = gh.getDefaultBranch(ownerRepo);

        if (dryRun) {
            System.out.println("  [DRY RUN] Would clone and fix issue.");
            entry.prUrl = "https://github.com/" + ownerRepo + "/pull/DRY_RUN";
            entry.prNumber = 0;
            entry.lastUpdated = Instant.now();
            return WorkflowState.IssueState.SELF_REVIEWING;
        }

        Path repoDir = null;
        try {
            System.out.println("  Cloning repository...");
            repoDir = gh.cloneForIssue(ownerRepo, issueNumber);
            if (repoDir == null) {
                System.out.println("  Clone failed, will retry.");
                return WorkflowState.IssueState.CODING;
            }

            String fixPrompt = """
                    You are working on a fix for a GitHub issue.

                    Repository: %s
                    Issue #%d: %s
                    Labels: %s

                    Issue description:
                    %s

                    Comments:
                    %s

                    Instructions:
                    1. FIRST read CLAUDE.md, CONTRIBUTING.md, or similar guides in the repo root
                    2. Analyze the issue carefully
                    3. Understand the codebase and find the relevant code
                    4. Implement a fix for the issue
                    5. Write or update tests if applicable
                    6. Build using the project's recommended build command
                    7. Commit your changes with a clear commit message

                    Commit message rules:
                    - Write a clear, natural-language sentence starting with an uppercase letter
                    - Do NOT prefix with feat:, fix:, chore:, or any Conventional Commits type
                    - Keep it concise but descriptive, do not end with a period
                    - Do NOT add Co-Authored-By trailers

                    Make a single commit with all your changes.
                    """.formatted(ownerRepo, issueNumber, entry.title, labels, issueBody, commentsText);

            System.out.println("  Running Claude to fix issue...");
            String fixOutput = claude.run(fixPrompt, repoDir);
            if (fixOutput == null) {
                System.out.println("  Fix failed, will retry.");
                return WorkflowState.IssueState.CODING;
            }

            System.out.println("  Pushing to fork...");
            if (!gh.pushForceLease(ownerRepo, issueNumber, repoDir)) {
                System.out.println("  Push failed, will retry.");
                return WorkflowState.IssueState.CODING;
            }

            String commitMsg = gh.git(GitHubClient.Actor.BOT, repoDir,
                    "log", "--format=%s", "-1");
            if (commitMsg == null || commitMsg.isEmpty()) commitMsg = entry.title;

            String prDescPrompt = """
                    Write a pull request description for the following change.

                    This PR fixes issue #%d: %s

                    Issue description:
                    %s

                    Rules:
                    - Start with "Fixes #%d" on its own line
                    - Then a blank line, followed by a concise explanation of what was changed and why
                    - Focus on the "why", not the "what"
                    - No bullet lists, no headers, no markdown formatting — just plain prose
                    - 2-4 sentences max
                    - Do NOT mention AI, Claude, automated tools, or that this was generated
                    - Output ONLY the PR description text, nothing else
                    """.formatted(issueNumber, entry.title, issueBody, issueNumber);

            String prBody = claude.runBare(prDescPrompt);
            if (prBody == null) prBody = "Fixes #" + issueNumber;

            System.out.println("  Creating draft PR...");
            String prUrl = gh.createDraftPR(ownerRepo, defaultBranch, issueNumber, commitMsg, prBody);
            if (prUrl == null) {
                System.out.println("  PR creation failed, but changes are pushed. Will retry.");
                return WorkflowState.IssueState.CODING;
            }

            entry.prUrl = prUrl;
            try {
                entry.prNumber = Integer.parseInt(prUrl.replaceAll(".*/(\\d+)$", "$1"));
            } catch (Exception e) {
                entry.prNumber = 0;
            }

            System.out.println("  Draft PR created: " + prUrl);
            entry.lastUpdated = Instant.now();
            return WorkflowState.IssueState.SELF_REVIEWING;

        } catch (Exception e) {
            System.err.println("  Error during coding: " + e.getMessage());
            return WorkflowState.IssueState.CODING;
        } finally {
            if (repoDir != null) gh.cleanupWorktree(entry.ownerRepo, entry.issueNumber);
        }
    }

    // --- SELF_REVIEWING: Bot reviews its own PR ---

    private WorkflowState.IssueState handleSelfReviewing(WorkflowState.IssueEntry entry) {
        String ownerRepo = entry.ownerRepo;
        int issueNumber = entry.issueNumber;
        String defaultBranch = gh.getDefaultBranch(ownerRepo);

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Self-reviewing: " + ownerRepo + "#" + issueNumber + " — " + entry.title);
        System.out.println("=".repeat(60));

        if (dryRun) {
            System.out.println("  [DRY RUN] Would self-review PR.");
            entry.lastUpdated = Instant.now();
            return WorkflowState.IssueState.READY_FOR_REVIEW;
        }

        Path repoDir = null;
        try {
            repoDir = gh.cloneForExistingPR(ownerRepo, issueNumber);
            if (repoDir == null) {
                System.out.println("  Clone failed, will retry.");
                return WorkflowState.IssueState.SELF_REVIEWING;
            }

            String reviewPrompt = """
                    Review the changes on the current branch.
                    Run `git diff origin/%s...HEAD` to see all changes.

                    Check correctness, tests, security, and that it follows existing patterns.
                    If everything looks good, respond with exactly: "No issues found."
                    Otherwise, list each issue as a short bullet (one sentence each).
                    Write like a human teammate — no markdown headers, no emoji, no verbose analysis.
                    Do NOT make any changes — only review.
                    """.formatted(defaultBranch);

            System.out.println("  Running self-review...");
            String reviewOutput = claude.run(reviewPrompt, repoDir, 15);

            if (reviewOutput == null) {
                System.out.println("  Review failed, will retry.");
                return WorkflowState.IssueState.SELF_REVIEWING;
            }

            entry.lastUpdated = Instant.now();

            if (reviewOutput.toLowerCase().contains("no issues found")) {
                System.out.println("  Self-review passed, moving to ready for review.");
                Long passedId = gh.postComment(ownerRepo, entry.prNumber,
                        "Self-review complete — no issues found.");
                if (passedId != null) gh.minimizeComment(ownerRepo, passedId);
                gh.addReviewer(ownerRepo, entry.prNumber, config.githubUser);
                gh.markPRReady(ownerRepo, entry.prNumber);
                return WorkflowState.IssueState.READY_FOR_REVIEW;
            }

            System.out.println("  Self-review found issues, posting and moving to fix.");
            Long reviewCommentId = gh.postComment(ownerRepo, entry.prNumber, reviewOutput);
            entry.botCommentId = reviewCommentId;
            entry.feedbackText = reviewOutput;
            return WorkflowState.IssueState.FIXING_REVIEW;

        } catch (Exception e) {
            System.err.println("  Error during self-review: " + e.getMessage());
            return WorkflowState.IssueState.SELF_REVIEWING;
        } finally {
            if (repoDir != null) gh.cleanupWorktree(entry.ownerRepo, entry.issueNumber);
        }
    }

    // --- FIXING_REVIEW: Fix self-review issues ---

    private WorkflowState.IssueState handleFixingReview(WorkflowState.IssueEntry entry) {
        String ownerRepo = entry.ownerRepo;
        int issueNumber = entry.issueNumber;
        String defaultBranch = gh.getDefaultBranch(ownerRepo);

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Fixing review issues: " + ownerRepo + "#" + issueNumber);
        System.out.println("=".repeat(60));

        if (dryRun) {
            System.out.println("  [DRY RUN] Would fix review issues.");
            entry.lastUpdated = Instant.now();
            return WorkflowState.IssueState.READY_FOR_REVIEW;
        }

        Path repoDir = null;
        try {
            repoDir = gh.cloneForExistingPR(ownerRepo, issueNumber);
            if (repoDir == null) {
                System.out.println("  Clone failed, will retry.");
                return WorkflowState.IssueState.FIXING_REVIEW;
            }

            String fixPrompt = """
                    A code review found issues with your changes. You MUST fix every issue listed below.
                    Do NOT just acknowledge the problems — actually change the code.

                    Review findings:

                    %s

                    Instructions:
                    1. FIRST read CLAUDE.md, CONTRIBUTING.md, or similar guides in the repo root
                    2. Go through EACH issue marked as critical, bug, or problem
                    3. Make the actual code change for each one
                    4. Verify your changes by reading the modified files
                    5. Build and run tests to confirm the fix works
                    6. Squash everything into a single commit:
                       git reset --soft $(git merge-base HEAD origin/%s) && git commit -m "your message"

                    Commit message rules:
                    - Write a clear, natural-language sentence starting with an uppercase letter
                    - Do NOT prefix with feat:, fix:, chore:, or any Conventional Commits type
                    - Keep it concise but descriptive, do not end with a period
                    - Do NOT add Co-Authored-By trailers
                    """.formatted(entry.feedbackText != null ? entry.feedbackText : "See review comments on the PR",
                    defaultBranch);

            System.out.println("  Fixing review issues...");
            String result = claude.run(fixPrompt, repoDir);
            if (result == null) {
                System.out.println("  Fix failed, will retry.");
                return WorkflowState.IssueState.FIXING_REVIEW;
            }

            // Verify the review findings were actually fixed
            System.out.println("  Verifying review fixes...");
            String reviewFeedback = entry.feedbackText != null ? entry.feedbackText : "";
            String verifyPrompt = """
                    You are verifying that self-review findings were properly fixed.
                    You have full access to the codebase — read the actual files to check.

                    The review found these issues:
                    %s

                    Instructions:
                    1. Read the actual source files mentioned in the review (do NOT rely on git diff alone)
                    2. For EACH issue, check if the code change was made
                    3. Only mark as unfixed if the specific code change is genuinely missing

                    Respond with a JSON object:
                    {
                      "all_fixed": true/false,
                      "unfixed": ["description of each unfixed issue"]
                    }

                    Output ONLY the JSON.
                    """.formatted(reviewFeedback);

            String verifyResult = claude.run(verifyPrompt, repoDir, 5);
            boolean allFixed = true;
            String unfixedPoints = "";

            if (verifyResult != null) {
                try {
                    int jsonStart = verifyResult.indexOf('{');
                    int jsonEnd = verifyResult.lastIndexOf('}') + 1;
                    if (jsonStart >= 0 && jsonEnd > jsonStart) {
                        var verifyMapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        var verifyJson = verifyMapper.readTree(verifyResult.substring(jsonStart, jsonEnd));
                        allFixed = verifyJson.path("all_fixed").asBoolean(false);
                        var unfixedArr = verifyJson.path("unfixed");
                        if (unfixedArr.isArray() && unfixedArr.size() > 0) {
                            StringBuilder sb = new StringBuilder();
                            for (var item : unfixedArr) {
                                sb.append("- ").append(item.asText()).append("\n");
                            }
                            unfixedPoints = sb.toString();
                        }
                    }
                } catch (Exception e) {
                    System.err.println("  Failed to parse verification: " + e.getMessage());
                }
            }

            if (!allFixed) {
                entry.attempts++;
                System.out.println("  Verification FAILED (attempt " + entry.attempts + "/3). Unfixed:\n" + unfixedPoints);
                if (entry.attempts >= 3) {
                    System.out.println("  Max fix attempts reached. Moving to ready for manual review.");
                    gh.postComment(ownerRepo, entry.prNumber,
                            "I attempted to fix the self-review findings " + entry.attempts
                                    + " times but the verifier still finds unfixed issues:\n\n"
                                    + unfixedPoints + "\n@" + config.githubUser
                                    + " please fix manually.");
                    gh.addReviewer(ownerRepo, entry.prNumber, config.githubUser);
                    gh.markPRReady(ownerRepo, entry.prNumber);
                    entry.feedbackText = null;
                    entry.lastUpdated = Instant.now();
                    return WorkflowState.IssueState.READY_FOR_REVIEW;
                }
                entry.feedbackText = reviewFeedback + "\n\nPREVIOUS FIX ATTEMPT FAILED. These issues are still NOT fixed:\n"
                        + unfixedPoints + "\nYou MUST make the actual code changes.";
                return WorkflowState.IssueState.FIXING_REVIEW;
            }

            if (!gh.pushForceLease(ownerRepo, issueNumber, repoDir)) {
                System.out.println("  Push failed, will retry.");
                return WorkflowState.IssueState.FIXING_REVIEW;
            }

            // Hide the self-review comment as outdated now that issues are fixed
            if (entry.botCommentId != null) {
                gh.minimizeComment(ownerRepo, entry.botCommentId);
            }

            gh.addReviewer(ownerRepo, entry.prNumber, config.githubUser);
            gh.markPRReady(ownerRepo, entry.prNumber);

            System.out.println("  Review issues fixed, PR marked ready for review.");
            entry.feedbackText = null;
            entry.lastUpdated = Instant.now();
            return WorkflowState.IssueState.READY_FOR_REVIEW;

        } catch (Exception e) {
            System.err.println("  Error fixing review: " + e.getMessage());
            return WorkflowState.IssueState.FIXING_REVIEW;
        } finally {
            if (repoDir != null) gh.cleanupWorktree(entry.ownerRepo, entry.issueNumber);
        }
    }

    // --- READY_FOR_REVIEW: Check for user approval or comments ---

    private WorkflowState.IssueState handleReadyForReview(WorkflowState.IssueEntry entry) {
        String ownerRepo = entry.ownerRepo;
        int issueNumber = entry.issueNumber;

        System.out.println("  Checking review status on " + ownerRepo + "#" + entry.prNumber + "...");

        if (entry.prNumber == null || entry.prNumber == 0) {
            System.out.println("  No PR number, cannot check reviews.");
            return WorkflowState.IssueState.READY_FOR_REVIEW;
        }

        // Check for merge conflicts and resolve by rebasing
        if (gh.hasMergeConflicts(ownerRepo, entry.prNumber)) {
            System.out.println("  PR has merge conflicts, rebasing...");
            try {
                Path repoDir = gh.cloneForExistingPR(ownerRepo, issueNumber);
                if (repoDir != null) {
                    String defaultBranch = gh.getDefaultBranch(ownerRepo);
                    gh.git(GitHubClient.Actor.BOT, repoDir, "fetch", "upstream", defaultBranch);
                    String result = gh.git(GitHubClient.Actor.BOT, repoDir, "rebase", "upstream/" + defaultBranch);
                    if (result == null) {
                        // Rebase hit conflicts — try to resolve them
                        result = resolveConflictsAndContinueRebase(repoDir);
                    }
                    if (result != null) {
                        gh.git(GitHubClient.Actor.BOT, repoDir, "push", "--force-with-lease", "-u", "origin", "fix/" + issueNumber);
                        System.out.println("  Conflicts resolved, rebased and pushed.");
                    } else {
                        gh.git(GitHubClient.Actor.BOT, repoDir, "rebase", "--abort");
                        System.out.println("  Could not resolve conflicts automatically.");
                    }
                }
            } catch (Exception e) {
                System.err.println("  Error resolving conflicts: " + e.getMessage());
            }
        }

        GitHubClient.ReviewVerdict verdict = gh.getUserReviewVerdict(ownerRepo, entry.prNumber);

        if (verdict == GitHubClient.ReviewVerdict.APPROVED) {
            System.out.println("  PR approved — moving to squash.");
            entry.lastUpdated = Instant.now();
            return WorkflowState.IssueState.SQUASHING;
        }

        if (verdict == GitHubClient.ReviewVerdict.CHANGES_REQUESTED) {
            System.out.println("  Changes requested — addressing feedback.");
            entry.lastUpdated = Instant.now();
            return WorkflowState.IssueState.ADDRESSING_FEEDBACK;
        }

        List<JsonNode> comments = gh.getUnprocessedPRComments(ownerRepo, entry.prNumber);
        if (!comments.isEmpty()) {
            System.out.println("  Found " + comments.size() + " unprocessed comment(s) — addressing feedback.");
            entry.lastUpdated = Instant.now();
            return WorkflowState.IssueState.ADDRESSING_FEEDBACK;
        }

        List<JsonNode> unreplied = gh.getUnrepliedReviewComments(ownerRepo, entry.prNumber);
        if (!unreplied.isEmpty()) {
            System.out.println("  Found " + unreplied.size() + " unreplied review comment(s) — addressing feedback.");
            entry.lastUpdated = Instant.now();
            return WorkflowState.IssueState.ADDRESSING_FEEDBACK;
        }

        // Check if user thumbsup'd the bot's self-review comment — means "go fix these"
        if (gh.hasBotReviewThumbsUp(ownerRepo, entry.prNumber)) {
            System.out.println("  👍 on bot's review — re-entering self-review.");
            entry.feedbackText = null;
            entry.lastUpdated = Instant.now();
            return WorkflowState.IssueState.SELF_REVIEWING;
        }

        System.out.println("  No approval or comments yet, waiting.");
        return WorkflowState.IssueState.READY_FOR_REVIEW;
    }

    // --- ADDRESSING_FEEDBACK: Process user comments on PR ---

    private WorkflowState.IssueState handleAddressingFeedback(WorkflowState.IssueEntry entry) {
        String ownerRepo = entry.ownerRepo;
        int issueNumber = entry.issueNumber;
        String defaultBranch = gh.getDefaultBranch(ownerRepo);

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Addressing feedback: " + ownerRepo + "#" + issueNumber);
        System.out.println("=".repeat(60));

        List<JsonNode> comments = new java.util.ArrayList<>(gh.getUnprocessedPRComments(ownerRepo, entry.prNumber));
        List<JsonNode> unreplied = gh.getUnrepliedReviewComments(ownerRepo, entry.prNumber);

        if (comments.isEmpty() && unreplied.isEmpty()) {
            System.out.println("  No unprocessed comments or unreplied reviews found.");
            entry.lastUpdated = Instant.now();
            return WorkflowState.IssueState.READY_FOR_REVIEW;
        }

        // Include unreplied review comments as feedback too
        for (JsonNode c : unreplied) {
            String key = c.path("id").asText();
            boolean alreadyIncluded = comments.stream().anyMatch(
                    existing -> existing.path("id").asText().equals(key));
            if (!alreadyIncluded) {
                comments.add(c);
            }
        }

        if (dryRun) {
            System.out.println("  [DRY RUN] Would process " + comments.size() + " comment(s).");
            entry.lastUpdated = Instant.now();
            return WorkflowState.IssueState.READY_FOR_REVIEW;
        }

        Path repoDir = null;
        try {
            repoDir = gh.cloneForExistingPR(ownerRepo, issueNumber);
            if (repoDir == null) {
                System.out.println("  Clone failed, will retry.");
                return WorkflowState.IssueState.ADDRESSING_FEEDBACK;
            }

            // Only include line-level comments (with file path) — broad review bodies
            // overwhelm Claude and cause it to conclude "already implemented"
            StringBuilder allFeedback = new StringBuilder();
            java.util.Set<String> referencedFiles = new java.util.LinkedHashSet<>();
            for (JsonNode c : comments) {
                String path = c.path("path").asText("");
                if (path.isEmpty()) continue;
                String author = c.path("user").asText("");
                allFeedback.append("--- ").append(author).append(" on ").append(path).append(" ---\n");
                allFeedback.append(c.path("body").asText("")).append("\n\n");
                referencedFiles.add(path);
            }

            // Read actual file contents for referenced files so Claude has exact strings
            StringBuilder fileContents = new StringBuilder();
            for (String path : referencedFiles) {
                java.nio.file.Path f = repoDir.resolve(path);
                if (java.nio.file.Files.exists(f)) {
                    String content = java.nio.file.Files.readString(f);
                    fileContents.append("=== FILE: ").append(path).append(" ===\n");
                    fileContents.append(content).append("\n\n");
                }
            }

            // Step 1: Extract specific edit instructions from feedback
            System.out.println("  Extracting edit instructions from feedback...");
            String extractPrompt = """
                    Extract specific file edit instructions from this reviewer feedback.

                    Feedback:
                    %s

                    Current file contents:
                    %s

                    For each change requested, output a JSON array of edits:
                    [
                      {
                        "file": "path/to/file.java",
                        "find": "exact string to find in the file",
                        "replace": "exact string to replace it with",
                        "explanation": "what this change does"
                      }
                    ]

                    Rules:
                    - Use the EXACT file paths shown above (e.g. extensions/devui/runtime/...)
                    - The "find" string must be an EXACT substring copied from the file content above
                    - The "replace" string is what it should be changed to
                    - Include enough context in "find" to be unique (a full line or more)
                    - Output ONLY the JSON array
                    """.formatted(allFeedback, fileContents);

            String editsJson = claude.runBare(extractPrompt);
            boolean appliedEdits = false;

            if (editsJson != null) {
                try {
                    int start = editsJson.indexOf('[');
                    int end = editsJson.lastIndexOf(']') + 1;
                    if (start >= 0 && end > start) {
                        var editMapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        var edits = editMapper.readTree(editsJson.substring(start, end));

                        for (var edit : edits) {
                            String filePath = edit.path("file").asText("");
                            String find = edit.path("find").asText("");
                            String replace = edit.path("replace").asText("");
                            String explanation = edit.path("explanation").asText("");

                            if (filePath.isEmpty() || find.isEmpty() || find.equals(replace)) continue;

                            java.nio.file.Path targetFile = repoDir.resolve(filePath);
                            if (!java.nio.file.Files.exists(targetFile)) {
                                System.out.println("    File not found: " + filePath);
                                continue;
                            }

                            String content = java.nio.file.Files.readString(targetFile);
                            if (content.contains(find)) {
                                content = content.replace(find, replace);
                                java.nio.file.Files.writeString(targetFile, content);
                                System.out.println("    Fixed: " + filePath + " — " + explanation);
                                appliedEdits = true;
                            } else {
                                System.out.println("    Find string not found in " + filePath + ": " + truncate(find, 80));
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("  Failed to parse edit instructions: " + e.getMessage());
                }
            }

            // Step 2: If extraction didn't work, fall back to full Claude run
            if (!appliedEdits) {
                System.out.println("  Edit extraction failed, falling back to full Claude run...");
                String fallbackPrompt = """
                        Fix this reviewer feedback. Open the specific files mentioned and make the changes.
                        Do NOT read CLAUDE.md. Do NOT explore. Just edit the files.
                        Do NOT run git commands — just edit the files.

                        %s
                        """.formatted(allFeedback);
                claude.run(fallbackPrompt, repoDir);
            }

            // We handle the commit — don't rely on Claude to commit
            gh.git(GitHubClient.Actor.BOT, repoDir, "add", "-A");
            String diff = gh.git(GitHubClient.Actor.BOT, repoDir, "diff", "--cached", "--stat");
            if (diff != null && !diff.isEmpty()) {
                gh.git(GitHubClient.Actor.BOT, repoDir, "commit", "-m", "Address reviewer feedback");
                System.out.println("  Committed changes: " + diff.lines().count() + " file(s)");
            } else {
                System.out.println("  WARNING: No code changes were made by the fix attempt.");
            }

            // Verify the feedback was actually addressed by running a verification pass
            System.out.println("  Verifying feedback was addressed...");

            String verifyPrompt = """
                    You are verifying that reviewer feedback was properly addressed in a pull request.
                    You have full access to the codebase — read the actual files to check.

                    The reviewer feedback was:
                    %s

                    Instructions:
                    1. Read the actual source files mentioned in the feedback (do NOT rely on git diff alone)
                    2. For EACH feedback point, check if the code change was made
                    3. Only mark as unaddressed if the specific code change is genuinely missing

                    Respond with a JSON object:
                    {
                      "all_addressed": true/false,
                      "unaddressed": ["description of each unaddressed point"]
                    }

                    Output ONLY the JSON, nothing else.
                    """.formatted(allFeedback);

            String verifyResult = claude.run(verifyPrompt, repoDir, 5);
            boolean allAddressed = true;
            String unaddressedPoints = "";

            if (verifyResult != null) {
                try {
                    int jsonStart = verifyResult.indexOf('{');
                    int jsonEnd = verifyResult.lastIndexOf('}') + 1;
                    if (jsonStart >= 0 && jsonEnd > jsonStart) {
                        var verifyMapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        var verifyJson = verifyMapper.readTree(verifyResult.substring(jsonStart, jsonEnd));
                        allAddressed = verifyJson.path("all_addressed").asBoolean(false);
                        var unaddressedArr = verifyJson.path("unaddressed");
                        if (unaddressedArr.isArray() && unaddressedArr.size() > 0) {
                            StringBuilder sb = new StringBuilder();
                            for (var item : unaddressedArr) {
                                sb.append("- ").append(item.asText()).append("\n");
                            }
                            unaddressedPoints = sb.toString();
                        }
                    }
                } catch (Exception e) {
                    System.err.println("  Failed to parse verification result: " + e.getMessage());
                }
            }

            if (!allAddressed) {
                entry.attempts++;
                System.out.println("  Verification FAILED (attempt " + entry.attempts + "/3). Unaddressed:\n" + unaddressedPoints);

                if (entry.attempts >= 3) {
                    gh.postComment(ownerRepo, entry.prNumber,
                            "I've attempted to address the feedback " + entry.attempts
                                    + " times but the verifier still finds unaddressed points:\n\n"
                                    + unaddressedPoints + "\n@" + config.githubUser
                                    + " please fix manually.");
                    // Mark all comments as processed so we don't re-enter this loop
                    for (JsonNode c : comments) {
                        long commentId = c.path("id").asLong();
                        String type = c.path("type").asText("issue");
                        if ("review".equals(type)) {
                            gh.reactToPRComment(ownerRepo, commentId);
                        } else if ("issue".equals(type)) {
                            gh.addReaction(ownerRepo, commentId, "+1");
                        }
                    }
                    entry.feedbackText = null;
                    entry.attempts = 0;
                    entry.lastUpdated = Instant.now();
                    return WorkflowState.IssueState.READY_FOR_REVIEW;
                }

                // Retry with more specific instructions including what wasn't fixed
                entry.feedbackText = allFeedback + "\n\nPREVIOUS ATTEMPT FAILED. These points were NOT addressed:\n" + unaddressedPoints
                        + "\nYou MUST make the actual code changes for these points. Do not just acknowledge them.";
                return WorkflowState.IssueState.ADDRESSING_FEEDBACK;
            }

            System.out.println("  Verification PASSED — all feedback addressed.");

            if (!gh.pushForceLease(ownerRepo, issueNumber, repoDir)) {
                System.out.println("  Push failed, will retry.");
                return WorkflowState.IssueState.ADDRESSING_FEEDBACK;
            }

            // Reply to review comments AFTER push so replies reflect the actual changes
            System.out.println("  Generating replies to review comments...");
            List<JsonNode> toReply = gh.getUnrepliedReviewComments(ownerRepo, entry.prNumber);
            if (!toReply.isEmpty()) {
                StringBuilder reviewComments = new StringBuilder();
                for (int i = 0; i < toReply.size(); i++) {
                    JsonNode c = toReply.get(i);
                    reviewComments.append("Comment ").append(i + 1).append(" (id:").append(c.path("id").asLong()).append("):\n");
                    reviewComments.append(c.path("body").asText("")).append("\n\n");
                }

                String replyPrompt = """
                        You are the author of this PR. Reply to each reviewer comment.
                        Run `git diff origin/%s...HEAD` to see the changes.
                        Write 1 sentence per reply — say what you changed or acknowledge the point.
                        Write like a teammate, not a bot. No markdown headers or emoji.

                        Comments:
                        %s

                        Respond with ONLY a JSON array: [{"id": 123, "reply": "your reply"}]
                        """.formatted(defaultBranch, reviewComments);

                String repliesJson = claude.run(replyPrompt, repoDir, 5);
                if (repliesJson != null) {
                    try {
                        int start = repliesJson.indexOf('[');
                        int end = repliesJson.lastIndexOf(']') + 1;
                        if (start >= 0 && end > start) {
                            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                            var replies = mapper.readTree(repliesJson.substring(start, end));
                            for (var r : replies) {
                                long commentId = r.path("id").asLong();
                                String reply = r.path("reply").asText("");
                                if (!reply.isEmpty() && commentId > 0) {
                                    gh.replyToPRComment(ownerRepo, entry.prNumber, commentId, reply);
                                }
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("  Failed to parse replies: " + e.getMessage());
                    }
                }
            }

            // React to processed comments
            for (JsonNode c : comments) {
                long commentId = c.path("id").asLong();
                String type = c.path("type").asText("issue");
                if ("review".equals(type)) {
                    gh.reactToPRComment(ownerRepo, commentId);
                } else if ("issue".equals(type)) {
                    gh.addReaction(ownerRepo, commentId, "+1");
                }
            }

            // Re-request review from all reviewers who gave feedback
            java.util.Set<String> reviewers = new java.util.HashSet<>();
            reviewers.add(config.githubUser);
            for (JsonNode c : comments) {
                String author = c.path("user").asText("");
                if (!author.isEmpty() && !author.equals(config.botUser)) {
                    reviewers.add(author);
                }
            }
            for (String reviewer : reviewers) {
                gh.requestReview(ownerRepo, entry.prNumber, reviewer);
            }

            System.out.println("  Feedback addressed, re-requested review.");
            entry.lastUpdated = Instant.now();
            return WorkflowState.IssueState.READY_FOR_REVIEW;

        } catch (Exception e) {
            System.err.println("  Error addressing feedback: " + e.getMessage());
            return WorkflowState.IssueState.ADDRESSING_FEEDBACK;
        } finally {
            if (repoDir != null) gh.cleanupWorktree(entry.ownerRepo, entry.issueNumber);
        }
    }

    // --- SQUASHING: Squash all commits into one ---

    private WorkflowState.IssueState handleSquashing(WorkflowState.IssueEntry entry) {
        String ownerRepo = entry.ownerRepo;
        int issueNumber = entry.issueNumber;
        String defaultBranch = gh.getDefaultBranch(ownerRepo);

        System.out.println("  Squashing commits on " + ownerRepo + "#" + entry.prNumber + "...");

        if (dryRun) {
            System.out.println("  [DRY RUN] Would squash commits.");
            entry.lastUpdated = Instant.now();
            return WorkflowState.IssueState.MONITORING_CI;
        }

        Path repoDir = null;
        try {
            repoDir = gh.cloneForExistingPR(ownerRepo, issueNumber);
            if (repoDir == null) {
                System.out.println("  Clone failed, will retry.");
                return WorkflowState.IssueState.SQUASHING;
            }

            if (!gh.pushForceLease(ownerRepo, issueNumber, repoDir)) {
                System.out.println("  Push failed, will retry.");
                return WorkflowState.IssueState.SQUASHING;
            }

            System.out.println("  Pushed successfully.");
            entry.lastUpdated = Instant.now();
            return WorkflowState.IssueState.MONITORING_CI;

        } catch (Exception e) {
            System.err.println("  Error squashing: " + e.getMessage());
            return WorkflowState.IssueState.SQUASHING;
        } finally {
            if (repoDir != null) gh.cleanupWorktree(entry.ownerRepo, entry.issueNumber);
        }
    }

    // --- MONITORING_CI: Check CI status ---

    private WorkflowState.IssueState handleMonitoringCI(WorkflowState.IssueEntry entry) {
        String ownerRepo = entry.ownerRepo;

        System.out.println("  Checking CI on " + ownerRepo + "#" + entry.prNumber + "...");

        GitHubClient.CIStatus status = gh.getCIStatus(ownerRepo, entry.prNumber);

        switch (status) {
            case PASS -> {
                System.out.println("  CI passed!");
                entry.lastUpdated = Instant.now();
                return WorkflowState.IssueState.DONE;
            }
            case FAIL -> {
                entry.attempts++;
                if (entry.attempts >= MAX_ATTEMPTS) {
                    System.out.println("  CI failed and max fix attempts reached.");
                    if (!dryRun) {
                        gh.postComment(ownerRepo, entry.issueNumber,
                                "CI is still failing after " + MAX_ATTEMPTS + " fix attempts. "
                                        + "@" + config.githubUser + " please take a look.");
                    }
                    entry.lastUpdated = Instant.now();
                    return WorkflowState.IssueState.DONE;
                }
                System.out.println("  CI failed — moving to fix (attempt " + entry.attempts + "/" + MAX_ATTEMPTS + ").");
                entry.lastUpdated = Instant.now();
                return WorkflowState.IssueState.FIXING_CI;
            }
            case PENDING -> {
                System.out.println("  CI still running, waiting.");
                return WorkflowState.IssueState.MONITORING_CI;
            }
        }
        return WorkflowState.IssueState.MONITORING_CI;
    }

    // --- FIXING_CI: Fix CI failures ---

    private WorkflowState.IssueState handleFixingCI(WorkflowState.IssueEntry entry) {
        String ownerRepo = entry.ownerRepo;
        int issueNumber = entry.issueNumber;
        String defaultBranch = gh.getDefaultBranch(ownerRepo);

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Fixing CI: " + ownerRepo + "#" + issueNumber);
        System.out.println("=".repeat(60));

        String failureDetails = gh.getCIFailureDetails(ownerRepo, entry.prNumber);

        if (dryRun) {
            System.out.println("  [DRY RUN] Would fix CI failures:\n" + failureDetails);
            entry.lastUpdated = Instant.now();
            return WorkflowState.IssueState.MONITORING_CI;
        }

        Path repoDir = null;
        try {
            repoDir = gh.cloneForExistingPR(ownerRepo, issueNumber);
            if (repoDir == null) {
                System.out.println("  Clone failed, will retry.");
                return WorkflowState.IssueState.FIXING_CI;
            }

            String prompt = """
                    The CI pipeline is failing on this pull request. Fix the failures.

                    CI failure details:
                    %s

                    Instructions:
                    1. FIRST read CLAUDE.md, CONTRIBUTING.md, or similar guides in the repo root
                    2. Investigate the CI failures
                    3. Fix the issues causing the failures
                    4. Build and test locally
                    5. Squash everything into a single commit:
                       git reset --soft $(git merge-base HEAD origin/%s) && git commit -m "your message"

                    Commit message rules:
                    - Write a clear, natural-language sentence starting with an uppercase letter
                    - Do NOT prefix with feat:, fix:, chore:, or any Conventional Commits type
                    - Keep it concise but descriptive, do not end with a period
                    - Do NOT add Co-Authored-By trailers
                    """.formatted(failureDetails, defaultBranch);

            System.out.println("  Fixing CI failures...");
            String result = claude.run(prompt, repoDir);
            if (result == null) {
                System.out.println("  Fix failed, will retry.");
                return WorkflowState.IssueState.FIXING_CI;
            }

            if (!gh.pushForceLease(ownerRepo, issueNumber, repoDir)) {
                System.out.println("  Push failed, will retry.");
                return WorkflowState.IssueState.FIXING_CI;
            }

            gh.requestReview(ownerRepo, entry.prNumber, config.githubUser);

            System.out.println("  CI fix pushed, re-requested review.");
            entry.lastUpdated = Instant.now();
            return WorkflowState.IssueState.MONITORING_CI;

        } catch (Exception e) {
            System.err.println("  Error fixing CI: " + e.getMessage());
            return WorkflowState.IssueState.FIXING_CI;
        } finally {
            if (repoDir != null) gh.cleanupWorktree(entry.ownerRepo, entry.issueNumber);
        }
    }

    // --- DONE: Check if PR was merged ---

    private WorkflowState.IssueState handleDone(WorkflowState.IssueEntry entry) {
        if (entry.prNumber != null && entry.prNumber > 0) {
            if (gh.isPRMerged(entry.ownerRepo, entry.prNumber)) {
                System.out.println("  " + entry.ownerRepo + "#" + entry.prNumber + " merged!");
                entry.lastUpdated = Instant.now();
                return WorkflowState.IssueState.MERGED;
            }
        }
        return WorkflowState.IssueState.DONE;
    }

    // --- Helpers ---

    private String buildLabelsString(JsonNode details) {
        JsonNode labels = details.path("labels");
        if (!labels.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode l : labels) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(l.path("name").asText(""));
        }
        return sb.toString();
    }

    private String buildCommentsText(JsonNode details) {
        JsonNode comments = details.path("comments");
        if (!comments.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode c : comments) {
            String author = c.path("author").path("login").asText("unknown");
            sb.append("\n--- Comment by ").append(author).append(" ---\n");
            sb.append(c.path("body").asText("")).append("\n");
        }
        return sb.toString();
    }

    private String resolveConflictsAndContinueRebase(Path repoDir) {
        for (int step = 0; step < 20; step++) {
            String conflicted = gh.git(GitHubClient.Actor.BOT, repoDir, "diff", "--name-only", "--diff-filter=U");
            if (conflicted == null || conflicted.isEmpty()) {
                // No more conflicts — continue rebase
                String cont = gh.git(GitHubClient.Actor.BOT, repoDir, "rebase", "--continue");
                if (cont != null) return cont;
                // rebase --continue might hit the next commit's conflicts
                continue;
            }

            boolean resolved = false;
            for (String file : conflicted.split("\n")) {
                file = file.trim();
                if (file.isEmpty()) continue;
                java.nio.file.Path filePath = repoDir.resolve(file);
                try {
                    String content = java.nio.file.Files.readString(filePath);
                    if (!content.contains("<<<<<<<")) continue;

                    String resolvePrompt = """
                            Resolve this merge conflict. The file has git conflict markers.
                            Keep the intent of BOTH sides — merge them logically.
                            Output ONLY the resolved file content, nothing else.

                            File: %s
                            %s
                            """.formatted(file, content);

                    String resolvedContent = claude.runBare(resolvePrompt);
                    if (resolvedContent != null && !resolvedContent.contains("<<<<<<<")) {
                        java.nio.file.Files.writeString(filePath, resolvedContent);
                        gh.git(GitHubClient.Actor.BOT, repoDir, "add", file);
                        System.out.println("    Resolved: " + file);
                        resolved = true;
                    }
                } catch (Exception e) {
                    System.err.println("    Failed to resolve " + file + ": " + e.getMessage());
                }
            }
            if (!resolved) return null;

            String cont = gh.git(GitHubClient.Actor.BOT, repoDir,
                    "-c", "core.editor=true", "rebase", "--continue");
            if (cont != null) return cont;
        }
        return null;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
