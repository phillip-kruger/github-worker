import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
    private final Map<String, String> nodeIdCache = new java.util.HashMap<>();
    private final Map<Long, String> commentNodeIdCache = new java.util.HashMap<>();
    private final Map<Long, String> reviewThreadIdCache = new java.util.HashMap<>();

    GitHubClient(Config config) {
        this.config = config;
    }

    // --- Core execution ---

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

    // --- GraphQL infrastructure ---

    JsonNode graphql(Actor actor, String query) {
        String result = ghText(actor, "api", "graphql", "-f", "query=" + query);
        if (result == null) return null;
        try {
            JsonNode root = mapper.readTree(result);
            JsonNode errors = root.path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                System.err.println("  GraphQL errors: " + errors);
            }
            JsonNode data = root.path("data");
            return data.isMissingNode() ? null : data;
        } catch (Exception e) {
            return null;
        }
    }

    JsonNode graphqlWithVars(Actor actor, String query, String... vars) {
        List<String> args = new ArrayList<>();
        args.add("api");
        args.add("graphql");
        args.add("-f");
        args.add("query=" + query);
        for (String var : vars) {
            args.add("-f");
            args.add(var);
        }
        String result = ghText(actor, args.toArray(new String[0]));
        if (result == null) return null;
        try {
            JsonNode root = mapper.readTree(result);
            JsonNode errors = root.path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                System.err.println("  GraphQL errors: " + errors);
            }
            JsonNode data = root.path("data");
            return data.isMissingNode() ? null : data;
        } catch (Exception e) {
            return null;
        }
    }

    private String[] splitOwnerRepo(String ownerRepo) {
        String[] parts = ownerRepo.split("/");
        return new String[]{parts[0], parts[1]};
    }

    private void cacheNodeId(String key, String nodeId) {
        if (nodeId != null && !nodeId.isEmpty()) nodeIdCache.put(key, nodeId);
    }

    private void cacheCommentNodeId(long databaseId, String nodeId) {
        if (nodeId != null && !nodeId.isEmpty()) commentNodeIdCache.put(databaseId, nodeId);
    }

    void ensureCommentNodeId(String ownerRepo, int number, long commentDatabaseId) {
        if (commentNodeIdCache.containsKey(commentDatabaseId)) return;
        String[] parts = splitOwnerRepo(ownerRepo);
        JsonNode data = graphql(Actor.USER, String.format("""
                { repository(owner: "%s", name: "%s") {
                    issueOrPullRequest(number: %d) {
                      ... on Issue {
                        comments(first: 100) {
                          nodes { databaseId id }
                        }
                      }
                      ... on PullRequest {
                        comments(first: 100) {
                          nodes { databaseId id }
                        }
                      }
                    }
                } }""", parts[0], parts[1], number));
        if (data == null) return;
        JsonNode comments = data.path("repository").path("issueOrPullRequest").path("comments").path("nodes");
        for (JsonNode c : comments) {
            cacheCommentNodeId(c.path("databaseId").asLong(), c.path("id").asText(null));
        }
    }

    private String getIssueOrPRNodeId(Actor actor, String ownerRepo, int number) {
        String key = "issueOrPR:" + ownerRepo + "#" + number;
        String cached = nodeIdCache.get(key);
        if (cached != null) return cached;
        cached = nodeIdCache.get("issue:" + ownerRepo + "#" + number);
        if (cached != null) return cached;
        cached = nodeIdCache.get("pr:" + ownerRepo + "#" + number);
        if (cached != null) return cached;

        String[] parts = splitOwnerRepo(ownerRepo);
        JsonNode data = graphql(actor, String.format("""
                { repository(owner: "%s", name: "%s") {
                    issueOrPullRequest(number: %d) {
                      ... on Issue { id }
                      ... on PullRequest { id }
                    }
                } }""", parts[0], parts[1], number));
        if (data == null) return null;
        String nodeId = data.path("repository").path("issueOrPullRequest").path("id").asText(null);
        cacheNodeId(key, nodeId);
        return nodeId;
    }

    private String getPRNodeId(Actor actor, String ownerRepo, int number) {
        String key = "pr:" + ownerRepo + "#" + number;
        String cached = nodeIdCache.get(key);
        if (cached != null) return cached;
        cached = nodeIdCache.get("issueOrPR:" + ownerRepo + "#" + number);
        if (cached != null) return cached;

        String[] parts = splitOwnerRepo(ownerRepo);
        JsonNode data = graphql(actor, String.format("""
                { repository(owner: "%s", name: "%s") { pullRequest(number: %d) { id } } }
                """, parts[0], parts[1], number));
        if (data == null) return null;
        String nodeId = data.path("repository").path("pullRequest").path("id").asText(null);
        cacheNodeId(key, nodeId);
        return nodeId;
    }

    private String getRepoNodeId(Actor actor, String ownerRepo) {
        String key = "repo:" + ownerRepo;
        String cached = nodeIdCache.get(key);
        if (cached != null) return cached;

        String[] parts = splitOwnerRepo(ownerRepo);
        JsonNode data = graphql(actor, String.format("""
                { repository(owner: "%s", name: "%s") { id } }
                """, parts[0], parts[1]));
        if (data == null) return null;
        String nodeId = data.path("repository").path("id").asText(null);
        cacheNodeId(key, nodeId);
        return nodeId;
    }

    private String getUserNodeId(Actor actor, String login) {
        String key = "user:" + login;
        String cached = nodeIdCache.get(key);
        if (cached != null) return cached;

        JsonNode data = graphql(actor, String.format("""
                { user(login: "%s") { id } }""", login));
        if (data == null) return null;
        String nodeId = data.path("user").path("id").asText(null);
        cacheNodeId(key, nodeId);
        return nodeId;
    }

    private boolean hasReactionInline(JsonNode node, String userLogin, String reactionContent) {
        JsonNode reactions = node.path("reactions").path("nodes");
        for (JsonNode r : reactions) {
            if (userLogin.equals(r.path("user").path("login").asText(""))
                    && reactionContent.equals(r.path("content").asText(""))) {
                return true;
            }
        }
        return false;
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
        String searchQuery = "assignee:" + config.githubUser + " is:open is:issue created:>=" + since;

        JsonNode data = graphqlWithVars(Actor.USER, """
                query($q: String!) {
                  search(query: $q, type: ISSUE, first: 50) {
                    nodes {
                      ... on Issue {
                        number title url createdAt updatedAt
                        repository { nameWithOwner }
                        labels(first: 20) { nodes { name } }
                      }
                    }
                  }
                }""", "q=" + searchQuery);
        if (data == null) return List.of();
        JsonNode nodes = data.path("search").path("nodes");
        if (!nodes.isArray()) return List.of();

        List<JsonNode> issues = new ArrayList<>();
        for (JsonNode n : nodes) {
            String org = n.path("repository").path("nameWithOwner").asText("").split("/")[0];
            if (!config.excludeOrgs.contains(org)) {
                ObjectNode item = mapper.createObjectNode();
                item.put("number", n.path("number").asInt());
                item.put("title", n.path("title").asText(""));
                item.put("url", n.path("url").asText(""));
                item.put("createdAt", n.path("createdAt").asText(""));
                item.put("updatedAt", n.path("updatedAt").asText(""));
                ObjectNode repo = mapper.createObjectNode();
                repo.put("nameWithOwner", n.path("repository").path("nameWithOwner").asText(""));
                item.set("repository", repo);
                item.set("labels", n.path("labels").path("nodes"));
                issues.add(item);
            }
        }
        return issues;
    }

    boolean wasSelfAssigned(String ownerRepo, int number) {
        String[] parts = splitOwnerRepo(ownerRepo);
        JsonNode data = graphql(Actor.USER, String.format("""
                { repository(owner: "%s", name: "%s") {
                    issue(number: %d) {
                      timelineItems(itemTypes: ASSIGNED_EVENT, last: 20) {
                        nodes {
                          ... on AssignedEvent {
                            actor { login }
                            assignee { ... on User { login } }
                          }
                        }
                      }
                    }
                } }""", parts[0], parts[1], number));
        if (data == null) return false;
        JsonNode items = data.path("repository").path("issue").path("timelineItems").path("nodes");
        String lastActorForUser = null;
        for (JsonNode item : items) {
            if (config.githubUser.equals(item.path("assignee").path("login").asText(""))) {
                lastActorForUser = item.path("actor").path("login").asText("");
            }
        }
        return config.githubUser.equals(lastActorForUser);
    }

    boolean wasEyesReactedByUser(String ownerRepo, int number) {
        String[] parts = splitOwnerRepo(ownerRepo);
        JsonNode data = graphql(Actor.USER, String.format("""
                { repository(owner: "%s", name: "%s") {
                    issueOrPullRequest(number: %d) {
                      ... on Issue {
                        reactions(content: EYES, first: 20) {
                          nodes { user { login } }
                        }
                      }
                      ... on PullRequest {
                        reactions(content: EYES, first: 20) {
                          nodes { user { login } }
                        }
                      }
                    }
                } }""", parts[0], parts[1], number));
        if (data == null) return false;
        JsonNode reactions = data.path("repository").path("issueOrPullRequest").path("reactions").path("nodes");
        for (JsonNode r : reactions) {
            if (config.githubUser.equals(r.path("user").path("login").asText(""))) {
                return true;
            }
        }
        return false;
    }

    boolean alreadyHasPR(String ownerRepo, int issueNumber) {
        String[] parts = splitOwnerRepo(ownerRepo);
        String branch = "fix/" + issueNumber;
        JsonNode data = graphql(Actor.BOT, String.format("""
                { repository(owner: "%s", name: "%s") {
                    pullRequests(headRefName: "%s", states: OPEN, first: 5) {
                      nodes { author { login } }
                    }
                } }""", parts[0], parts[1], branch));
        if (data == null) return false;
        JsonNode prs = data.path("repository").path("pullRequests").path("nodes");
        for (JsonNode pr : prs) {
            if (config.botUser.equals(pr.path("author").path("login").asText(""))) {
                return true;
            }
        }
        return false;
    }

    boolean hasBotReviewThumbsUp(String ownerRepo, int prNumber) {
        String[] parts = splitOwnerRepo(ownerRepo);
        JsonNode data = graphql(Actor.USER, String.format("""
                { repository(owner: "%s", name: "%s") {
                    issueOrPullRequest(number: %d) {
                      ... on Issue {
                        comments(first: 50) {
                          nodes {
                            databaseId
                            author { login }
                            reactions(content: THUMBS_UP, first: 10) {
                              nodes { user { login } }
                            }
                          }
                        }
                      }
                      ... on PullRequest {
                        comments(first: 50) {
                          nodes {
                            databaseId
                            author { login }
                            reactions(content: THUMBS_UP, first: 10) {
                              nodes { user { login } }
                            }
                          }
                        }
                      }
                    }
                } }""", parts[0], parts[1], prNumber));
        if (data == null) return false;
        JsonNode comments = data.path("repository").path("issueOrPullRequest").path("comments").path("nodes");
        for (JsonNode c : comments) {
            if (!config.botUser.equals(c.path("author").path("login").asText(""))) continue;
            JsonNode reactions = c.path("reactions").path("nodes");
            for (JsonNode r : reactions) {
                if (config.githubUser.equals(r.path("user").path("login").asText(""))) {
                    return true;
                }
            }
        }
        return false;
    }

    boolean isPRMerged(String ownerRepo, int prNumber) {
        return "MERGED".equals(getPRState(ownerRepo, prNumber));
    }

    boolean isBotReviewRequested(String ownerRepo, int prNumber) {
        String[] parts = splitOwnerRepo(ownerRepo);
        JsonNode data = graphql(Actor.USER, String.format("""
                { repository(owner: "%s", name: "%s") {
                    pullRequest(number: %d) {
                      reviewRequests(first: 20) {
                        nodes {
                          requestedReviewer {
                            ... on User { login }
                          }
                        }
                      }
                    }
                } }""", parts[0], parts[1], prNumber));
        if (data == null) return false;
        JsonNode requests = data.path("repository").path("pullRequest").path("reviewRequests").path("nodes");
        for (JsonNode r : requests) {
            if (config.botUser.equals(r.path("requestedReviewer").path("login").asText(""))) return true;
        }
        return false;
    }

    // --- Eyes reaction discovery (GraphQL) ---

    List<JsonNode> fetchEyesReactedItems() {
        List<JsonNode> results = new ArrayList<>();
        for (String scope : config.orgs) {
            if (scope.contains("/")) {
                fetchEyesForRepo(scope, results);
            }
        }
        return results;
    }

    private void fetchEyesForRepo(String ownerRepo, List<JsonNode> results) {
        for (String type : List.of("ISSUE", "PR")) {
            String searchType = "ISSUE".equals(type) ? "is:issue" : "is:pr";
            String searchQuery = "repo:" + ownerRepo + " is:open " + searchType;

            JsonNode data = graphqlWithVars(Actor.USER, """
                    query($q: String!) {
                      search(query: $q, type: ISSUE, first: 30) {
                        nodes {
                          ... on Issue {
                            number title url
                            reactions(content: EYES, first: 5) {
                              nodes { user { login } }
                            }
                          }
                          ... on PullRequest {
                            number title url
                            reactions(content: EYES, first: 5) {
                              nodes { user { login } }
                            }
                          }
                        }
                      }
                    }""", "q=" + searchQuery);
            if (data == null) continue;

            JsonNode nodes = data.path("search").path("nodes");
            for (JsonNode node : nodes) {
                JsonNode reactions = node.path("reactions").path("nodes");
                boolean hasUserEyes = false;
                for (JsonNode r : reactions) {
                    if (config.githubUser.equals(r.path("user").path("login").asText())) {
                        hasUserEyes = true;
                        break;
                    }
                }
                if (hasUserEyes) {
                    ObjectNode obj = mapper.createObjectNode();
                    obj.put("number", node.path("number").asInt());
                    obj.put("title", node.path("title").asText());
                    obj.put("url", node.path("url").asText());
                    obj.put("type", "ISSUE".equals(type) ? "issue" : "pr");
                    obj.put("ownerRepo", ownerRepo);
                    results.add(obj);
                }
            }
        }
    }

    // --- Review discovery ---

    List<JsonNode> fetchReviewRequests() {
        String since = LocalDate.now().minusDays(config.lookbackDays).format(DateTimeFormatter.ISO_DATE);
        String searchQuery = "review-requested:" + config.githubUser + " is:open is:pr created:>=" + since;

        JsonNode data = graphqlWithVars(Actor.USER, """
                query($q: String!) {
                  search(query: $q, type: ISSUE, first: 50) {
                    nodes {
                      ... on PullRequest {
                        number title url
                        repository { nameWithOwner }
                      }
                    }
                  }
                }""", "q=" + searchQuery);
        if (data == null) return List.of();
        JsonNode nodes = data.path("search").path("nodes");
        if (!nodes.isArray()) return List.of();

        List<JsonNode> prs = new ArrayList<>();
        for (JsonNode n : nodes) {
            String org = n.path("repository").path("nameWithOwner").asText("").split("/")[0];
            if (!config.excludeOrgs.contains(org)) {
                ObjectNode item = mapper.createObjectNode();
                item.put("number", n.path("number").asInt());
                item.put("title", n.path("title").asText(""));
                item.put("url", n.path("url").asText(""));
                ObjectNode repo = mapper.createObjectNode();
                repo.put("nameWithOwner", n.path("repository").path("nameWithOwner").asText(""));
                item.set("repository", repo);
                prs.add(item);
            }
        }
        return prs;
    }

    JsonNode fetchPRExtras(String ownerRepo, int prNumber) {
        String[] parts = splitOwnerRepo(ownerRepo);
        JsonNode data = graphql(Actor.USER, String.format("""
                { repository(owner: "%s", name: "%s") {
                    pullRequest(number: %d) {
                      id headRefName
                      author { login }
                    }
                } }""", parts[0], parts[1], prNumber));
        if (data == null) return null;
        JsonNode pr = data.path("repository").path("pullRequest");
        if (pr.isMissingNode()) return null;
        cacheNodeId("pr:" + ownerRepo + "#" + prNumber, pr.path("id").asText(null));

        ObjectNode result = mapper.createObjectNode();
        result.put("headRefName", pr.path("headRefName").asText(""));
        ObjectNode author = mapper.createObjectNode();
        author.put("login", pr.path("author").path("login").asText(""));
        result.set("author", author);
        return result;
    }

    boolean wasReviewSelfRequested(String ownerRepo, int prNumber) {
        String[] parts = splitOwnerRepo(ownerRepo);
        JsonNode data = graphql(Actor.USER, String.format("""
                { repository(owner: "%s", name: "%s") {
                    pullRequest(number: %d) {
                      timelineItems(itemTypes: REVIEW_REQUESTED_EVENT, last: 20) {
                        nodes {
                          ... on ReviewRequestedEvent {
                            actor { login }
                            requestedReviewer { ... on User { login } }
                          }
                        }
                      }
                    }
                } }""", parts[0], parts[1], prNumber));
        if (data == null) return false;
        JsonNode items = data.path("repository").path("pullRequest").path("timelineItems").path("nodes");
        String lastActorForUser = null;
        for (JsonNode item : items) {
            if (config.githubUser.equals(item.path("requestedReviewer").path("login").asText(""))) {
                lastActorForUser = item.path("actor").path("login").asText("");
            }
        }
        return config.githubUser.equals(lastActorForUser);
    }

    // --- Discovery ---

    List<WorkflowState.DiscoveryEntry> fetchDiscoveryItems(WorkflowState state) {
        String since = LocalDate.now().minusDays(config.lookbackDays).format(DateTimeFormatter.ISO_DATE);
        Map<String, WorkflowState.DiscoveryEntry> results = new LinkedHashMap<>();

        for (String scope : config.orgs) {
            String scopeQualifier = scope.contains("/") ? "repo:" + scope : "org:" + scope;

            // 1. Mentions (issues only)
            searchAndAddDiscoveries(results, state, "issue", "mention",
                    "mentions:" + config.githubUser + " created:>=" + since
                            + " is:open is:issue " + scopeQualifier, 30);

            // 2. Topics — issues without linked PRs
            for (String topic : config.topics) {
                searchAndAddDiscoveries(results, state, "issue", topic,
                        topic + " -linked:pr created:>=" + since
                                + " is:open is:issue " + scopeQualifier, 15);
            }

            // 3. Topics — PRs from others
            for (String topic : config.topics) {
                searchAndAddDiscoveries(results, state, "pr", topic,
                        topic + " -author:" + config.githubUser + " -author:" + config.botUser
                                + " created:>=" + since + " is:open is:pr " + scopeQualifier, 15);
            }

            // 4. PRs where user is CC'd by quarkus-bot
            searchAndAddDiscoveries(results, state, "pr", "cc'd",
                    "/cc @" + config.githubUser + " commenter:quarkus-bot[bot]"
                            + " created:>=" + since + " is:open is:pr " + scopeQualifier, 20);
        }

        return new ArrayList<>(results.values());
    }

    private void searchAndAddDiscoveries(Map<String, WorkflowState.DiscoveryEntry> results,
                                         WorkflowState state, String type, String source,
                                         String searchQuery, int limit) {
        JsonNode data = graphqlWithVars(Actor.USER, String.format("""
                query($q: String!) {
                  search(query: $q, type: ISSUE, first: %d) {
                    nodes {
                      ... on Issue {
                        number title url
                        repository { nameWithOwner }
                      }
                      ... on PullRequest {
                        number title url
                        repository { nameWithOwner }
                      }
                    }
                  }
                }""", limit), "q=" + searchQuery);
        if (data == null) return;
        JsonNode nodes = data.path("search").path("nodes");
        addDiscoveries(results, state, type, source, nodes);
    }

    private void addDiscoveries(Map<String, WorkflowState.DiscoveryEntry> results,
                                WorkflowState state, String type, String source, JsonNode searchResult) {
        if (searchResult == null || !searchResult.isArray()) return;
        for (JsonNode n : searchResult) {
            String ownerRepo = n.path("repository").path("nameWithOwner").asText("");
            int number = n.path("number").asInt();
            String key = ownerRepo + "#" + number;

            if (config.excludeOrgs.contains(ownerRepo.split("/")[0])) continue;
            if (!isInAllowedOrgs(ownerRepo)) continue;
            if (state.issues.containsKey(key)) continue;
            if (state.reviews.containsKey(key)) continue;
            if (results.containsKey(key)) continue;

            String title = n.path("title").asText("");
            if (title.startsWith("Bump quarkus") || title.startsWith("Bump io.quarkus")) continue;
            if (title.startsWith("Add AI skill")) continue;

            WorkflowState.DiscoveryEntry entry = new WorkflowState.DiscoveryEntry();
            entry.title = title;
            entry.ownerRepo = ownerRepo;
            entry.number = number;
            entry.url = n.path("url").asText("");
            entry.type = type;
            entry.source = source;
            entry.matchedTopic = "mention".equals(source) ? null : source;
            results.put(key, entry);
        }
    }

    private final Map<String, Boolean> orgMemberCache = new java.util.HashMap<>();

    boolean isOrgMember(String ownerRepo, String user) {
        String org = ownerRepo.split("/")[0];
        String cacheKey = org + "/" + user;
        return orgMemberCache.computeIfAbsent(cacheKey, k -> {
            JsonNode data = graphql(Actor.USER, String.format("""
                    { organization(login: "%s") {
                        membersWithRole(first: 1, query: "%s") {
                          nodes { login }
                        }
                    } }""", org, user));
            if (data == null) return false;
            JsonNode members = data.path("organization").path("membersWithRole").path("nodes");
            for (JsonNode m : members) {
                if (user.equals(m.path("login").asText(""))) return true;
            }
            return false;
        });
    }

    private boolean isInAllowedOrgs(String ownerRepo) {
        if (config.orgs.isEmpty()) return true;
        String org = ownerRepo.split("/")[0];
        for (String scope : config.orgs) {
            if (scope.contains("/")) {
                if (scope.equals(ownerRepo)) return true;
            } else {
                if (scope.equals(org)) return true;
            }
        }
        return false;
    }

    // --- Issue details ---

    JsonNode getIssueDetails(String ownerRepo, int number) {
        String[] parts = splitOwnerRepo(ownerRepo);
        JsonNode data = graphql(Actor.USER, String.format("""
                { repository(owner: "%s", name: "%s") {
                    issue(number: %d) {
                      id title body
                      labels(first: 20) { nodes { name } }
                      comments(first: 100) { nodes { author { login } body } }
                      assignees(first: 20) { nodes { login } }
                    }
                } }""", parts[0], parts[1], number));
        if (data == null) return null;
        JsonNode issue = data.path("repository").path("issue");
        if (issue.isMissingNode()) return null;
        cacheNodeId("issue:" + ownerRepo + "#" + number, issue.path("id").asText(null));

        ObjectNode result = mapper.createObjectNode();
        result.put("title", issue.path("title").asText(""));
        result.put("body", issue.path("body").asText(""));
        result.set("labels", issue.path("labels").path("nodes"));
        result.set("comments", issue.path("comments").path("nodes"));
        result.set("assignees", issue.path("assignees").path("nodes"));
        return result;
    }

    String getIssueOrPRState(String ownerRepo, int issueNumber, Integer prNumber) {
        if (prNumber != null && prNumber > 0) {
            return getPRState(ownerRepo, prNumber);
        }
        String[] parts = splitOwnerRepo(ownerRepo);
        JsonNode data = graphql(Actor.USER, String.format("""
                { repository(owner: "%s", name: "%s") {
                    issue(number: %d) { state }
                } }""", parts[0], parts[1], issueNumber));
        if (data == null) return "OPEN";
        String s = data.path("repository").path("issue").path("state").asText("OPEN");
        return "CLOSED".equalsIgnoreCase(s) ? "CLOSED" : "OPEN";
    }

    String getPRState(String ownerRepo, int prNumber) {
        String[] parts = splitOwnerRepo(ownerRepo);
        JsonNode data = graphql(Actor.USER, String.format("""
                { repository(owner: "%s", name: "%s") {
                    pullRequest(number: %d) { state }
                } }""", parts[0], parts[1], prNumber));
        if (data == null) return "OPEN";
        String s = data.path("repository").path("pullRequest").path("state").asText("OPEN");
        if ("MERGED".equalsIgnoreCase(s)) return "MERGED";
        if ("CLOSED".equalsIgnoreCase(s)) return "CLOSED";
        return "OPEN";
    }

    boolean hasMergeConflicts(String ownerRepo, int prNumber) {
        String[] parts = splitOwnerRepo(ownerRepo);
        JsonNode data = graphql(Actor.USER, String.format("""
                { repository(owner: "%s", name: "%s") {
                    pullRequest(number: %d) { mergeable }
                } }""", parts[0], parts[1], prNumber));
        if (data == null) return false;
        return "CONFLICTING".equalsIgnoreCase(
                data.path("repository").path("pullRequest").path("mergeable").asText(""));
    }

    JsonNode getPRDetails(String ownerRepo, int number) {
        String[] parts = splitOwnerRepo(ownerRepo);
        JsonNode data = graphql(Actor.USER, String.format("""
                { repository(owner: "%s", name: "%s") {
                    pullRequest(number: %d) {
                      id title body headRefName state mergeable
                      author { login }
                      comments(first: 100) { nodes { author { login } body } }
                      reviews(last: 50) { nodes { author { login } state body } }
                      commits(last: 1) {
                        nodes {
                          commit {
                            statusCheckRollup {
                              contexts(first: 100) {
                                nodes {
                                  __typename
                                  ... on CheckRun { name status conclusion detailsUrl }
                                  ... on StatusContext { context state targetUrl }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                } }""", parts[0], parts[1], number));
        if (data == null) return null;
        JsonNode pr = data.path("repository").path("pullRequest");
        if (pr.isMissingNode()) return null;
        cacheNodeId("pr:" + ownerRepo + "#" + number, pr.path("id").asText(null));

        ObjectNode result = mapper.createObjectNode();
        result.put("title", pr.path("title").asText(""));
        result.put("body", pr.path("body").asText(""));
        result.put("headRefName", pr.path("headRefName").asText(""));
        result.put("state", pr.path("state").asText(""));
        result.put("mergeable", pr.path("mergeable").asText(""));
        result.set("author", pr.path("author"));
        result.set("comments", pr.path("comments").path("nodes"));
        result.set("reviews", pr.path("reviews").path("nodes"));

        // Flatten statusCheckRollup from commits structure
        ArrayNode checks = mapper.createArrayNode();
        JsonNode commits = pr.path("commits").path("nodes");
        if (commits.isArray() && !commits.isEmpty()) {
            JsonNode contexts = commits.get(0).path("commit").path("statusCheckRollup")
                    .path("contexts").path("nodes");
            if (contexts.isArray()) {
                for (JsonNode ctx : contexts) {
                    ObjectNode check = mapper.createObjectNode();
                    String typename = ctx.path("__typename").asText("");
                    if ("CheckRun".equals(typename)) {
                        check.put("status", ctx.path("status").asText(""));
                        check.put("conclusion", ctx.path("conclusion").asText(""));
                        check.put("name", ctx.path("name").asText(""));
                        check.put("detailsUrl", ctx.path("detailsUrl").asText(""));
                    } else if ("StatusContext".equals(typename)) {
                        String state = ctx.path("state").asText("");
                        check.put("status", "SUCCESS".equalsIgnoreCase(state) || "FAILURE".equalsIgnoreCase(state)
                                || "ERROR".equalsIgnoreCase(state) ? "COMPLETED" : state);
                        check.put("conclusion", state);
                        check.put("name", ctx.path("context").asText(""));
                        check.put("detailsUrl", ctx.path("targetUrl").asText(""));
                    }
                    checks.add(check);
                }
            }
        }
        result.set("statusCheckRollup", checks);
        return result;
    }

    // --- Bot actions ---

    Long postComment(String ownerRepo, int number, String body) {
        String nodeId = getIssueOrPRNodeId(Actor.BOT, ownerRepo, number);
        if (nodeId == null) return null;

        JsonNode data = graphqlWithVars(Actor.BOT, String.format("""
                mutation($body: String!) {
                  addComment(input: {subjectId: "%s", body: $body}) {
                    commentEdge {
                      node { databaseId id }
                    }
                  }
                }""", nodeId), "body=" + body);
        if (data == null) return null;
        JsonNode comment = data.path("addComment").path("commentEdge").path("node");
        long dbId = comment.path("databaseId").asLong(0);
        cacheCommentNodeId(dbId, comment.path("id").asText(null));
        return dbId > 0 ? dbId : null;
    }

    void addReaction(String ownerRepo, long commentId, String reaction) {
        String nodeId = commentNodeIdCache.get(commentId);
        if (nodeId == null) return;

        String content = switch (reaction) {
            case "+1" -> "THUMBS_UP";
            case "-1" -> "THUMBS_DOWN";
            case "eyes" -> "EYES";
            case "heart" -> "HEART";
            case "rocket" -> "ROCKET";
            case "hooray" -> "HOORAY";
            case "laugh" -> "LAUGH";
            case "confused" -> "CONFUSED";
            default -> reaction;
        };
        graphql(Actor.BOT, String.format("""
                mutation {
                  addReaction(input: {subjectId: "%s", content: %s}) {
                    reaction { content }
                  }
                }""", nodeId, content));
    }

    // --- Comment reaction checks ---

    boolean hasThumbsUpFromUser(String ownerRepo, long commentId) {
        return hasReactionFromUser(ownerRepo, commentId, "THUMBS_UP");
    }

    boolean hasThumbsDownFromUser(String ownerRepo, long commentId) {
        return hasReactionFromUser(ownerRepo, commentId, "THUMBS_DOWN");
    }

    void minimizeComment(String ownerRepo, long commentId) {
        String nodeId = commentNodeIdCache.get(commentId);
        if (nodeId == null) return;
        graphql(Actor.BOT, String.format("""
                mutation {
                  minimizeComment(input: {subjectId: "%s", classifier: OUTDATED}) {
                    minimizedComment { isMinimized }
                  }
                }""", nodeId));
    }

    private boolean hasReactionFromUser(String ownerRepo, long commentId, String reactionContent) {
        String nodeId = commentNodeIdCache.get(commentId);
        if (nodeId == null) return false;

        JsonNode data = graphql(Actor.USER, String.format("""
                { node(id: "%s") {
                    ... on IssueComment {
                      reactions(content: %s, first: 20) {
                        nodes { user { login } }
                      }
                    }
                } }""", nodeId, reactionContent));
        if (data == null) return false;
        JsonNode reactions = data.path("node").path("reactions").path("nodes");
        for (JsonNode r : reactions) {
            if (config.githubUser.equals(r.path("user").path("login").asText(""))) {
                return true;
            }
        }
        return false;
    }

    String getUserCommentAfter(String ownerRepo, int number, long afterCommentId) {
        String[] parts = splitOwnerRepo(ownerRepo);
        JsonNode data = graphql(Actor.USER, String.format("""
                { repository(owner: "%s", name: "%s") {
                    issueOrPullRequest(number: %d) {
                      ... on Issue {
                        comments(first: 100) {
                          nodes { databaseId body author { login } }
                        }
                      }
                      ... on PullRequest {
                        comments(first: 100) {
                          nodes { databaseId body author { login } }
                        }
                      }
                    }
                } }""", parts[0], parts[1], number));
        if (data == null) return null;
        JsonNode comments = data.path("repository").path("issueOrPullRequest").path("comments").path("nodes");
        for (JsonNode c : comments) {
            if (config.githubUser.equals(c.path("author").path("login").asText(""))
                    && c.path("databaseId").asLong() > afterCommentId) {
                return c.path("body").asText();
            }
        }
        return null;
    }

    // --- PR operations ---

    String createDraftPR(String ownerRepo, String defaultBranch, int issueNumber, String title, String body) {
        String upstreamNodeId = getRepoNodeId(Actor.BOT, ownerRepo);
        if (upstreamNodeId == null) return null;

        String forkRepo = config.botUser + "/" + ownerRepo.split("/")[1];
        String forkNodeId = getRepoNodeId(Actor.BOT, forkRepo);
        if (forkNodeId == null) return null;

        String headRef = "fix/" + issueNumber;
        JsonNode data = graphqlWithVars(Actor.BOT, String.format("""
                mutation($title: String!, $body: String!) {
                  createPullRequest(input: {
                    repositoryId: "%s",
                    baseRefName: "%s",
                    headRefName: "%s",
                    headRepositoryId: "%s",
                    title: $title,
                    body: $body,
                    draft: true
                  }) {
                    pullRequest { url id number }
                  }
                }""", upstreamNodeId, defaultBranch, headRef, forkNodeId),
                "title=" + title, "body=" + body);
        if (data == null) return null;
        JsonNode pr = data.path("createPullRequest").path("pullRequest");
        String url = pr.path("url").asText(null);
        cacheNodeId("pr:" + ownerRepo + "#" + pr.path("number").asInt(), pr.path("id").asText(null));
        return url;
    }

    void markPRReady(String ownerRepo, int prNumber) {
        String prNodeId = getPRNodeId(Actor.BOT, ownerRepo, prNumber);
        if (prNodeId == null) return;
        graphql(Actor.BOT, String.format("""
                mutation {
                  markPullRequestReadyForReview(input: {pullRequestId: "%s"}) {
                    pullRequest { isDraft }
                  }
                }""", prNodeId));
    }

    void addReviewer(String ownerRepo, int prNumber, String reviewer) {
        String prNodeId = getPRNodeId(Actor.BOT, ownerRepo, prNumber);
        if (prNodeId == null) return;
        String userNodeId = getUserNodeId(Actor.BOT, reviewer);
        if (userNodeId == null) return;
        graphql(Actor.BOT, String.format("""
                mutation {
                  requestReviews(input: {pullRequestId: "%s", userIds: ["%s"]}) {
                    pullRequest { id }
                  }
                }""", prNodeId, userNodeId));
    }

    void requestReview(String ownerRepo, int prNumber, String reviewer) {
        String prNodeId = getPRNodeId(Actor.USER, ownerRepo, prNumber);
        if (prNodeId == null) return;
        String userNodeId = getUserNodeId(Actor.USER, reviewer);
        if (userNodeId == null) return;
        graphql(Actor.USER, String.format("""
                mutation {
                  requestReviews(input: {pullRequestId: "%s", userIds: ["%s"]}) {
                    pullRequest { id }
                  }
                }""", prNodeId, userNodeId));
    }

    void postPRReview(String ownerRepo, int prNumber, String body) {
        String prNodeId = getPRNodeId(Actor.BOT, ownerRepo, prNumber);
        if (prNodeId == null) return;
        graphqlWithVars(Actor.BOT, String.format("""
                mutation($body: String!) {
                  addPullRequestReview(input: {pullRequestId: "%s", body: $body, event: COMMENT}) {
                    pullRequestReview { id }
                  }
                }""", prNodeId), "body=" + body);
    }

    // --- PR status checks ---

    JsonNode getPRReviews(String ownerRepo, int prNumber) {
        String[] parts = splitOwnerRepo(ownerRepo);
        JsonNode data = graphql(Actor.USER, String.format("""
                { repository(owner: "%s", name: "%s") {
                    pullRequest(number: %d) {
                      reviews(last: 50) {
                        nodes { author { login } state body }
                      }
                    }
                } }""", parts[0], parts[1], prNumber));
        if (data == null) return null;
        ObjectNode result = mapper.createObjectNode();
        result.set("reviews", data.path("repository").path("pullRequest").path("reviews").path("nodes"));
        return result;
    }

    enum ReviewVerdict { APPROVED, CHANGES_REQUESTED, NONE }

    ReviewVerdict getUserReviewVerdict(String ownerRepo, int prNumber) {
        JsonNode data = getPRReviews(ownerRepo, prNumber);
        if (data == null) return ReviewVerdict.NONE;
        JsonNode reviews = data.path("reviews");
        if (!reviews.isArray()) return ReviewVerdict.NONE;

        String userLatest = null;
        for (JsonNode r : reviews) {
            if (config.githubUser.equals(r.path("author").path("login").asText())) {
                userLatest = r.path("state").asText();
            }
        }
        if ("APPROVED".equals(userLatest)) return ReviewVerdict.APPROVED;
        if ("CHANGES_REQUESTED".equals(userLatest)) return ReviewVerdict.CHANGES_REQUESTED;

        for (JsonNode r : reviews) {
            String reviewer = r.path("author").path("login").asText("");
            if (reviewer.equals(config.botUser)) continue;
            if (reviewer.equals(config.githubUser)) continue;
            String state = r.path("state").asText();
            if ("CHANGES_REQUESTED".equals(state) && isOrgMember(ownerRepo, reviewer)) {
                return ReviewVerdict.CHANGES_REQUESTED;
            }
        }

        return ReviewVerdict.NONE;
    }

    List<JsonNode> getUnprocessedPRComments(String ownerRepo, int prNumber) {
        String[] parts = splitOwnerRepo(ownerRepo);
        JsonNode data = graphql(Actor.USER, String.format("""
                { repository(owner: "%s", name: "%s") {
                    pullRequest(number: %d) {
                      comments(first: 100) {
                        nodes {
                          databaseId id body createdAt
                          author { login }
                          reactions(first: 30) {
                            nodes { user { login } content }
                          }
                        }
                      }
                      reviewThreads(first: 50) {
                        nodes {
                          id
                          comments(first: 20) {
                            nodes {
                              databaseId id body path createdAt
                              author { login }
                              reactions(first: 30) {
                                nodes { user { login } content }
                              }
                            }
                          }
                        }
                      }
                      reviews(last: 50) {
                        nodes {
                          databaseId id body state
                          author { login }
                        }
                      }
                    }
                } }""", parts[0], parts[1], prNumber));
        if (data == null) return List.of();
        JsonNode pr = data.path("repository").path("pullRequest");

        List<JsonNode> unprocessed = new ArrayList<>();

        // 1. Issue comments — from user OR thumbsup'd by user, not already reacted by bot
        for (JsonNode c : pr.path("comments").path("nodes")) {
            String author = c.path("author").path("login").asText("");
            if (author.equals(config.botUser)) continue;

            long dbId = c.path("databaseId").asLong();
            cacheCommentNodeId(dbId, c.path("id").asText(null));

            if (hasReactionInline(c, config.botUser, "THUMBS_UP")) continue;

            if (author.equals(config.githubUser) || hasReactionInline(c, config.githubUser, "THUMBS_UP")) {
                ObjectNode item = mapper.createObjectNode();
                item.put("id", dbId);
                item.put("body", c.path("body").asText(""));
                item.put("user", author);
                item.put("created_at", c.path("createdAt").asText(""));
                item.put("type", "issue");
                unprocessed.add(item);
            }
        }

        // 2. PR review comments — from user OR thumbsup'd by user
        for (JsonNode thread : pr.path("reviewThreads").path("nodes")) {
            for (JsonNode c : thread.path("comments").path("nodes")) {
                String author = c.path("author").path("login").asText("");
                if (author.equals(config.botUser)) continue;

                long dbId = c.path("databaseId").asLong();
                cacheCommentNodeId(dbId, c.path("id").asText(null));

                if (hasReactionInline(c, config.botUser, "THUMBS_UP")) continue;

                if (author.equals(config.githubUser) || hasReactionInline(c, config.githubUser, "THUMBS_UP")) {
                    ObjectNode item = mapper.createObjectNode();
                    item.put("id", dbId);
                    item.put("body", c.path("body").asText(""));
                    item.put("path", c.path("path").asText(""));
                    item.put("user", author);
                    item.put("created_at", c.path("createdAt").asText(""));
                    item.put("type", "review");
                    unprocessed.add(item);
                }
            }
        }

        // 3. PR review body text — from user OR trusted reviewer with CHANGES_REQUESTED
        for (JsonNode r : pr.path("reviews").path("nodes")) {
            String author = r.path("author").path("login").asText("");
            if (author.equals(config.botUser)) continue;
            String body = r.path("body").asText("");
            if (body.isEmpty()) continue;

            if (author.equals(config.githubUser)) {
                ObjectNode item = mapper.createObjectNode();
                item.put("id", r.path("databaseId").asLong());
                item.put("body", body);
                item.put("state", r.path("state").asText(""));
                item.put("user", author);
                unprocessed.add(item);
            } else if ("CHANGES_REQUESTED".equals(r.path("state").asText("")) && isOrgMember(ownerRepo, author)) {
                ObjectNode item = mapper.createObjectNode();
                item.put("id", r.path("databaseId").asLong());
                item.put("body", body);
                item.put("state", r.path("state").asText(""));
                item.put("user", author);
                unprocessed.add(item);
            }
        }

        return unprocessed;
    }

    List<JsonNode> getUnrepliedReviewComments(String ownerRepo, int prNumber) {
        String[] parts = splitOwnerRepo(ownerRepo);
        JsonNode data = graphql(Actor.USER, String.format("""
                { repository(owner: "%s", name: "%s") {
                    pullRequest(number: %d) {
                      reviewThreads(first: 50) {
                        nodes {
                          id
                          comments(first: 20) {
                            nodes {
                              databaseId id body
                              author { login }
                            }
                          }
                        }
                      }
                    }
                } }""", parts[0], parts[1], prNumber));
        if (data == null) return List.of();
        JsonNode pr = data.path("repository").path("pullRequest");

        List<JsonNode> unreplied = new ArrayList<>();
        for (JsonNode thread : pr.path("reviewThreads").path("nodes")) {
            String threadNodeId = thread.path("id").asText("");
            JsonNode comments = thread.path("comments").path("nodes");

            if (!comments.isArray() || comments.isEmpty()) continue;

            JsonNode firstComment = comments.get(0);
            String firstAuthor = firstComment.path("author").path("login").asText("");
            if (firstAuthor.equals(config.botUser)) continue;

            boolean botReplied = false;
            for (JsonNode c : comments) {
                if (config.botUser.equals(c.path("author").path("login").asText(""))) {
                    botReplied = true;
                    break;
                }
            }

            if (!botReplied) {
                long dbId = firstComment.path("databaseId").asLong();
                cacheCommentNodeId(dbId, firstComment.path("id").asText(null));
                reviewThreadIdCache.put(dbId, threadNodeId);

                ObjectNode item = mapper.createObjectNode();
                item.put("id", dbId);
                item.put("body", firstComment.path("body").asText(""));
                item.put("user", firstAuthor);
                unreplied.add(item);
            }
        }

        return unreplied;
    }

    void replyToPRComment(String ownerRepo, int prNumber, long commentId, String body) {
        String threadNodeId = reviewThreadIdCache.get(commentId);
        if (threadNodeId == null) return;
        graphqlWithVars(Actor.BOT, String.format("""
                mutation($body: String!) {
                  addPullRequestReviewThreadReply(input: {pullRequestReviewThreadId: "%s", body: $body}) {
                    comment { id }
                  }
                }""", threadNodeId), "body=" + body);
    }

    void reactToPRComment(String ownerRepo, long commentId) {
        String nodeId = commentNodeIdCache.get(commentId);
        if (nodeId == null) return;
        graphql(Actor.BOT, String.format("""
                mutation {
                  addReaction(input: {subjectId: "%s", content: THUMBS_UP}) {
                    reaction { content }
                  }
                }""", nodeId));
    }

    // --- CI checks ---

    enum CIStatus { PASS, FAIL, PENDING }

    CIStatus getCIStatus(String ownerRepo, int prNumber) {
        String[] parts = splitOwnerRepo(ownerRepo);
        JsonNode data = graphql(Actor.USER, String.format("""
                { repository(owner: "%s", name: "%s") {
                    pullRequest(number: %d) {
                      commits(last: 1) {
                        nodes {
                          commit {
                            statusCheckRollup {
                              contexts(first: 100) {
                                nodes {
                                  __typename
                                  ... on CheckRun { status conclusion }
                                  ... on StatusContext { state }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                } }""", parts[0], parts[1], prNumber));
        if (data == null) return CIStatus.PENDING;

        JsonNode commits = data.path("repository").path("pullRequest").path("commits").path("nodes");
        if (!commits.isArray() || commits.isEmpty()) return CIStatus.PENDING;
        JsonNode contexts = commits.get(0).path("commit").path("statusCheckRollup")
                .path("contexts").path("nodes");
        if (!contexts.isArray() || contexts.isEmpty()) return CIStatus.PENDING;

        boolean anyFailed = false;
        boolean anyPending = false;
        for (JsonNode check : contexts) {
            String typename = check.path("__typename").asText("");
            if ("CheckRun".equals(typename)) {
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
            } else if ("StatusContext".equals(typename)) {
                String state = check.path("state").asText("");
                if ("SUCCESS".equalsIgnoreCase(state)) {
                    // pass
                } else if ("PENDING".equalsIgnoreCase(state) || "EXPECTED".equalsIgnoreCase(state)) {
                    anyPending = true;
                } else {
                    anyFailed = true;
                }
            }
        }
        if (anyFailed) return CIStatus.FAIL;
        if (anyPending) return CIStatus.PENDING;
        return CIStatus.PASS;
    }

    String getCIFailureDetails(String ownerRepo, int prNumber) {
        String[] parts = splitOwnerRepo(ownerRepo);
        JsonNode data = graphql(Actor.USER, String.format("""
                { repository(owner: "%s", name: "%s") {
                    pullRequest(number: %d) {
                      commits(last: 1) {
                        nodes {
                          commit {
                            statusCheckRollup {
                              contexts(first: 100) {
                                nodes {
                                  __typename
                                  ... on CheckRun { name conclusion detailsUrl }
                                  ... on StatusContext { context state targetUrl }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                } }""", parts[0], parts[1], prNumber));
        if (data == null) return "Could not fetch CI details";

        JsonNode commits = data.path("repository").path("pullRequest").path("commits").path("nodes");
        if (!commits.isArray() || commits.isEmpty()) return "Could not fetch CI details";
        JsonNode contexts = commits.get(0).path("commit").path("statusCheckRollup")
                .path("contexts").path("nodes");

        StringBuilder sb = new StringBuilder();
        if (contexts.isArray()) {
            for (JsonNode check : contexts) {
                String typename = check.path("__typename").asText("");
                if ("CheckRun".equals(typename)) {
                    String conclusion = check.path("conclusion").asText("");
                    if ("FAILURE".equalsIgnoreCase(conclusion) || "ERROR".equalsIgnoreCase(conclusion)) {
                        sb.append("- ").append(check.path("name").asText("?"))
                                .append(": ").append(conclusion)
                                .append(" (").append(check.path("detailsUrl").asText("")).append(")\n");
                    }
                } else if ("StatusContext".equals(typename)) {
                    String state = check.path("state").asText("");
                    if ("FAILURE".equalsIgnoreCase(state) || "ERROR".equalsIgnoreCase(state)) {
                        sb.append("- ").append(check.path("context").asText("?"))
                                .append(": ").append(state)
                                .append(" (").append(check.path("targetUrl").asText("")).append(")\n");
                    }
                }
            }
        }
        return sb.length() > 0 ? sb.toString() : "CI failures detected but details unavailable";
    }

    // --- Repo operations (worktree-based) ---

    String getDefaultBranch(String ownerRepo) {
        String[] parts = splitOwnerRepo(ownerRepo);
        JsonNode data = graphql(Actor.USER, String.format("""
                { repository(owner: "%s", name: "%s") {
                    id
                    defaultBranchRef { name }
                } }""", parts[0], parts[1]));
        if (data == null) return "main";
        JsonNode repo = data.path("repository");
        cacheNodeId("repo:" + ownerRepo, repo.path("id").asText(null));
        String name = repo.path("defaultBranchRef").path("name").asText("");
        return name.isEmpty() ? "main" : name;
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

    private Path ensureMainClone(String ownerRepo) throws IOException {
        Path mainDir = mainCloneDir(ownerRepo);

        if (java.nio.file.Files.isDirectory(mainDir.resolve(".git"))) {
            String remotes = git(Actor.BOT, mainDir, "remote");
            if (remotes == null || !remotes.contains("upstream")) {
                System.out.println("  Main clone missing upstream remote, recreating...");
                deleteDir(mainDir);
            } else {
                String defaultBranch = getDefaultBranch(ownerRepo);
                git(Actor.BOT, mainDir, "fetch", "upstream", defaultBranch);
                git(Actor.BOT, mainDir, "fetch", "origin");
                git(Actor.BOT, mainDir, "checkout", defaultBranch);
                git(Actor.BOT, mainDir, "reset", "--hard", "upstream/" + defaultBranch);
                return mainDir;
            }
        }

        java.nio.file.Files.createDirectories(mainDir.getParent());

        ghText(Actor.BOT, "repo", "fork", ownerRepo, "--default-branch-only");

        String forkRepo = config.botUser + "/" + ownerRepo.split("/")[1];
        String httpsOrigin = "https://x-access-token:" + config.botToken + "@github.com/" + forkRepo + ".git";
        String httpsUpstream = "https://github.com/" + ownerRepo + ".git";

        if (ghExitCode(Actor.BOT, "repo", "clone", forkRepo, mainDir.toString()) != 0) {
            return null;
        }

        git(Actor.BOT, mainDir, "remote", "set-url", "origin", httpsOrigin);
        git(Actor.BOT, mainDir, "remote", "add", "upstream", httpsUpstream);
        disableGPGSigning(mainDir);

        String defaultBranch = getDefaultBranch(ownerRepo);
        git(Actor.BOT, mainDir, "fetch", "upstream", defaultBranch);
        git(Actor.BOT, mainDir, "checkout", defaultBranch);
        git(Actor.BOT, mainDir, "reset", "--hard", "upstream/" + defaultBranch);

        return mainDir;
    }

    private void disableGPGSigning(Path wtDir) {
        git(Actor.BOT, wtDir, "config", "commit.gpgsign", "false");
    }

    private void setupMavenIsolation(Path wtDir, String ownerRepo, int number) throws IOException {
        if (!java.nio.file.Files.exists(wtDir.resolve("pom.xml"))) return;

        Path m2Dir = worktreeM2(ownerRepo, number);
        java.nio.file.Files.createDirectories(m2Dir);

        Path mvnConfig = wtDir.resolve(".mvn").resolve("maven.config");
        String existing = java.nio.file.Files.exists(mvnConfig)
                ? java.nio.file.Files.readString(mvnConfig) : "";
        if (!existing.contains("-Dmaven.repo.local=")) {
            java.nio.file.Files.writeString(mvnConfig,
                    existing.stripTrailing() + "\n-Dmaven.repo.local=" + m2Dir.toAbsolutePath() + "\n");
            git(Actor.BOT, wtDir, "update-index", "--assume-unchanged", ".mvn/maven.config");
        }
    }

    Path cloneForIssue(String ownerRepo, int issueNumber) throws IOException {
        Path mainDir = ensureMainClone(ownerRepo);
        if (mainDir == null) return null;

        Path wtDir = worktreeDir(ownerRepo, issueNumber);
        removeWorktree(mainDir, wtDir);

        String defaultBranch = getDefaultBranch(ownerRepo);
        String branch = "fix/" + issueNumber;

        git(Actor.BOT, mainDir, "branch", "-D", branch);

        git(Actor.BOT, mainDir, "fetch", "origin", defaultBranch);
        java.nio.file.Files.createDirectories(wtDir.getParent());
        String result = git(Actor.BOT, mainDir,
                "worktree", "add", wtDir.toAbsolutePath().toString(),
                "-b", branch, "origin/" + defaultBranch);
        if (result == null) return null;

        disableGPGSigning(wtDir);
        setupMavenIsolation(wtDir, ownerRepo, issueNumber);
        return wtDir;
    }

    Path cloneForExistingPR(String ownerRepo, int issueNumber) throws IOException {
        Path mainDir = ensureMainClone(ownerRepo);
        if (mainDir == null) return null;

        Path wtDir = worktreeDir(ownerRepo, issueNumber);
        removeWorktree(mainDir, wtDir);

        String branch = "fix/" + issueNumber;
        String defaultBranch = getDefaultBranch(ownerRepo);

        git(Actor.BOT, mainDir, "fetch", "origin", defaultBranch);
        git(Actor.BOT, mainDir, "fetch", "origin", branch);

        java.nio.file.Files.createDirectories(wtDir.getParent());
        String result = git(Actor.BOT, mainDir,
                "worktree", "add", wtDir.toAbsolutePath().toString(),
                "-B", branch, "origin/" + branch);
        if (result == null) {
        }

        disableGPGSigning(wtDir);
        setupMavenIsolation(wtDir, ownerRepo, issueNumber);
        return wtDir;
    }

    Path cloneForReview(String ownerRepo, int prNumber, String headBranch, String author) throws IOException {
        Path wtDir = worktreeDir(ownerRepo, prNumber);

        Path mainDir = mainCloneDir(ownerRepo);
        if (!java.nio.file.Files.isDirectory(mainDir.resolve(".git"))) {
            java.nio.file.Files.createDirectories(mainDir.getParent());
            if (ghExitCode(Actor.USER, "repo", "clone", ownerRepo, mainDir.toString()) != 0) {
                return null;
            }
        }

        removeWorktree(mainDir, wtDir);

        git(Actor.USER, mainDir, "branch", "-D", "pr-" + prNumber);
        git(Actor.USER, mainDir, "fetch", "upstream", "pull/" + prNumber + "/head:pr-" + prNumber);

        java.nio.file.Files.createDirectories(wtDir.getParent());
        String result = git(Actor.USER, mainDir,
                "worktree", "add", wtDir.toAbsolutePath().toString(), "pr-" + prNumber);
        if (result == null) return null;

        disableGPGSigning(wtDir);
        setupMavenIsolation(wtDir, ownerRepo, prNumber);
        return wtDir;
    }

    boolean pushForceLease(String ownerRepo, int issueNumber, Path repoDir) {
        String branch = "fix/" + issueNumber;

        git(Actor.BOT, repoDir, "add", "-A");
        String status = git(Actor.BOT, repoDir, "status", "--porcelain");
        if (status != null && !status.isEmpty()) {
            git(Actor.BOT, repoDir, "commit", "-m", "WIP: stage changes before push");
        }

        String result = git(Actor.BOT, repoDir, "push", "--force-with-lease", "-u", "origin", branch);
        return result != null;
    }

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
