import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.time.Instant;

public class ReviewWorkflow {

    private final GitHubClient gh;
    private final ClaudeRunner claude;
    private final Config config;
    private final boolean dryRun;

    ReviewWorkflow(GitHubClient gh, ClaudeRunner claude, Config config, boolean dryRun) {
        this.gh = gh;
        this.claude = claude;
        this.config = config;
        this.dryRun = dryRun;
    }

    WorkflowState.ReviewState advance(WorkflowState.ReviewEntry entry) {
        return switch (entry.state) {
            case NEW -> handleNew(entry);
            case REVIEW_POSTED, DONE -> WorkflowState.ReviewState.DONE;
        };
    }

    private WorkflowState.ReviewState handleNew(WorkflowState.ReviewEntry entry) {
        String ownerRepo = entry.ownerRepo;
        int prNumber = entry.prNumber;

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Reviewing: " + ownerRepo + "#" + prNumber + " — " + entry.title);
        System.out.println("=".repeat(60));

        JsonNode prDetails = gh.getPRDetails(ownerRepo, prNumber);
        if (prDetails == null) {
            System.out.println("  Could not fetch PR details, skipping this run.");
            return WorkflowState.ReviewState.NEW;
        }

        String title = prDetails.path("title").asText("");
        String body = prDetails.path("body").asText("");
        String author = prDetails.path("author").path("login").asText("");
        String headBranch = prDetails.path("headRefName").asText("");
        String defaultBranch = gh.getDefaultBranch(ownerRepo);

        System.out.println("  Cloning for review...");
        Path repoDir = null;
        try {
            repoDir = gh.cloneForReview(ownerRepo, prNumber, headBranch, author);
            if (repoDir == null) {
                System.out.println("  Clone failed, skipping this run.");
                return WorkflowState.ReviewState.NEW;
            }

            String diff = gh.git(GitHubClient.Actor.USER, repoDir,
                    "diff", defaultBranch + "..." + headBranch);
            if (diff == null) diff = "";
            if (diff.length() > 15000) diff = diff.substring(0, 15000) + "\n... (truncated)";

            String prompt = """
                    You are reviewing a pull request for the repository %s.

                    PR #%d: %s
                    Author: %s

                    PR description:
                    %s

                    Changes (diff against %s):
                    %s

                    Provide a thorough code review covering:
                    1. Correctness and completeness of the changes
                    2. Test coverage — are there tests? Are they adequate?
                    3. Documentation — are docs/javadoc updated where needed?
                    4. Security implications
                    5. Code quality and maintainability
                    6. Quarkus-specific patterns — check CLAUDE.md if present for project conventions

                    Format your review as a structured comment.
                    For each finding, indicate severity: [CRITICAL], [SUGGESTION], or [NIT].
                    End with a one-line overall assessment.

                    Output ONLY the review body text, nothing else.
                    """.formatted(ownerRepo, prNumber, title, author, body, defaultBranch, diff);

            System.out.println("  Running review...");
            String reviewBody = claude.run(prompt, repoDir, 15);

            if (reviewBody == null || reviewBody.isEmpty()) {
                System.out.println("  Review generation failed, will retry next run.");
                return WorkflowState.ReviewState.NEW;
            }

            if (dryRun) {
                System.out.println("  [DRY RUN] Would post review:\n" + truncate(reviewBody, 500));
            } else {
                gh.postPRReview(ownerRepo, prNumber, reviewBody);
                System.out.println("  Review posted on " + ownerRepo + "#" + prNumber);
            }

            entry.lastUpdated = Instant.now();
            return WorkflowState.ReviewState.DONE;

        } catch (Exception e) {
            System.err.println("  Error during review: " + e.getMessage());
            return WorkflowState.ReviewState.NEW;
        } finally {
            if (repoDir != null) gh.cleanupWorktree(entry.ownerRepo, entry.prNumber);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
