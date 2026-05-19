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
            case DONE -> WorkflowState.IssueState.DONE;
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
                    Review the changes on the current branch compared to the default branch.
                    Run `git diff upstream/%s...HEAD` to see all changes.

                    Check for:
                    1. Correctness: Does the fix actually address the issue?
                    2. Tests: Are there adequate tests? Are existing tests passing?
                    3. Documentation: Are docs/javadoc updated where needed?
                    4. Security: Any security implications?
                    5. Maintainability: Is the code clean, well-structured, follows existing patterns?
                    6. Quarkus patterns: Does it follow Quarkus conventions? Check CLAUDE.md for guidance.
                    7. Skills: If this repo uses Quarkus skills, are they updated if needed?

                    For each issue found, describe it clearly.
                    If everything looks good, respond with exactly: "No issues found."
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
                gh.addReviewer(ownerRepo, entry.prNumber, config.githubUser);
                gh.markPRReady(ownerRepo, entry.prNumber);
                return WorkflowState.IssueState.READY_FOR_REVIEW;
            }

            System.out.println("  Self-review found issues, posting and moving to fix.");
            gh.postPRReview(ownerRepo, entry.prNumber, reviewOutput);
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
                       git reset --soft $(git merge-base HEAD upstream/%s) && git commit -m "your message"

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

            // Verify that changes were actually made
            String diffCheck = gh.git(GitHubClient.Actor.BOT, repoDir,
                    "diff", "HEAD~1", "--stat");
            if (diffCheck == null || diffCheck.isEmpty()) {
                System.out.println("  No changes detected after fix attempt — Claude may not have applied the fix. Retrying.");
                entry.attempts++;
                if (entry.attempts >= 3) {
                    System.out.println("  Max fix attempts reached. Moving to ready for manual review.");
                    gh.postComment(ownerRepo, entry.prNumber,
                            "I attempted to fix the self-review findings " + entry.attempts
                                    + " times but couldn't apply the changes. @" + config.githubUser
                                    + " please review and fix manually.");
                    gh.addReviewer(ownerRepo, entry.prNumber, config.githubUser);
                    gh.markPRReady(ownerRepo, entry.prNumber);
                    entry.feedbackText = null;
                    entry.lastUpdated = Instant.now();
                    return WorkflowState.IssueState.READY_FOR_REVIEW;
                }
                return WorkflowState.IssueState.FIXING_REVIEW;
            }

            if (!gh.pushForceLease(ownerRepo, issueNumber, repoDir)) {
                System.out.println("  Push failed, will retry.");
                return WorkflowState.IssueState.FIXING_REVIEW;
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

            StringBuilder allFeedback = new StringBuilder();
            for (JsonNode c : comments) {
                String path = c.path("path").asText("");
                String author = c.path("user").asText("");
                allFeedback.append("--- Feedback");
                if (!author.isEmpty()) allFeedback.append(" from @").append(author);
                if (!path.isEmpty()) allFeedback.append(" on file: ").append(path);
                allFeedback.append(" ---\n");
                allFeedback.append(c.path("body").asText("")).append("\n\n");
            }

            String diff = gh.git(GitHubClient.Actor.BOT, repoDir,
                    "diff", "upstream/" + defaultBranch + "...HEAD");
            if (diff == null) diff = "";
            if (diff.length() > 15000) diff = diff.substring(0, 15000) + "\n... (truncated)";

            String prompt = """
                    You are working on a pull request that has received reviewer feedback.
                    You MUST fix EVERY issue raised. Do not just acknowledge — actually change the code.

                    Current changes (diff against %s):
                    %s

                    Reviewer feedback (fix ALL of these):

                    %s

                    Instructions:
                    1. FIRST read CLAUDE.md, CONTRIBUTING.md, or similar guides in the repo root
                    2. Go through EACH feedback point and make the necessary code changes
                    3. If a reviewer says assertions are weak, make them stronger with concrete checks
                    4. If a reviewer points out a bug, fix the bug
                    5. If a reviewer suggests a refactor, do the refactor
                    6. Build and run tests to verify your changes work
                    7. Squash everything into a single commit:
                       git reset --soft $(git merge-base HEAD upstream/%s) && git commit -m "your message"

                    Commit message rules:
                    - Write a clear, natural-language sentence starting with an uppercase letter
                    - Do NOT prefix with feat:, fix:, chore:, or any Conventional Commits type
                    - Keep it concise but descriptive, do not end with a period
                    - Do NOT add Co-Authored-By trailers
                    """.formatted(defaultBranch, diff, allFeedback, defaultBranch);

            System.out.println("  Addressing " + comments.size() + " comment(s)...");
            String result = claude.run(prompt, repoDir);
            if (result == null) {
                System.out.println("  Failed to address feedback, will retry.");
                return WorkflowState.IssueState.ADDRESSING_FEEDBACK;
            }

            if (!gh.pushForceLease(ownerRepo, issueNumber, repoDir)) {
                System.out.println("  Push failed, will retry.");
                return WorkflowState.IssueState.ADDRESSING_FEEDBACK;
            }

            // Reply to all review comments using full Claude with codebase access
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
                        You are the author of this pull request. Reviewers left these comments.
                        Look at the actual code in the repository to understand what was changed.
                        Run `git diff upstream/%s...HEAD` to see the changes.

                        For each comment, write a brief reply (1-2 sentences) explaining how the
                        current code addresses the feedback. Reference specific code if relevant.
                        If something wasn't fixed, say so honestly.

                        Reviewer comments:
                        %s

                        Respond with a JSON array where each element has:
                        - "id": the comment id number
                        - "reply": your reply text

                        Output ONLY the JSON array.
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

            // Fetch latest upstream before squashing to avoid stale changes
            gh.git(GitHubClient.Actor.BOT, repoDir, "fetch", "upstream", defaultBranch);

            // Rebase on upstream to ensure we only have our changes
            gh.git(GitHubClient.Actor.BOT, repoDir, "rebase", "upstream/" + defaultBranch);

            String commitMsg = gh.git(GitHubClient.Actor.BOT, repoDir, "log", "--format=%s", "-1");
            if (commitMsg == null || commitMsg.isEmpty()) commitMsg = entry.title;

            gh.git(GitHubClient.Actor.BOT, repoDir,
                    "reset", "--soft", "upstream/" + defaultBranch);
            gh.git(GitHubClient.Actor.BOT, repoDir,
                    "commit", "-m", commitMsg);

            if (!gh.pushForceLease(ownerRepo, issueNumber, repoDir)) {
                System.out.println("  Push failed, will retry.");
                return WorkflowState.IssueState.SQUASHING;
            }

            System.out.println("  Squashed to single commit.");
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
                       git reset --soft $(git merge-base HEAD upstream/%s) && git commit -m "your message"

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

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
