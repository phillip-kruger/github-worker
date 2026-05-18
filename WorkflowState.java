import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowState {

    enum IssueState {
        NEW, AWAITING_APPROVAL, CODING, SELF_REVIEWING, FIXING_REVIEW,
        READY_FOR_REVIEW, ADDRESSING_FEEDBACK, SQUASHING, MONITORING_CI,
        FIXING_CI, DONE
    }

    enum ReviewState {
        NEW, REVIEW_POSTED, DONE
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class IssueEntry {
        public IssueState state = IssueState.NEW;
        public Long botCommentId;
        public Integer prNumber;
        public String prUrl;
        public Instant lastUpdated = Instant.now();
        public int attempts;
        public String feedbackText;
        public String title;
        public String ownerRepo;
        public int issueNumber;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ReviewEntry {
        public ReviewState state = ReviewState.NEW;
        public Instant lastUpdated = Instant.now();
        public String title;
        public String ownerRepo;
        public int prNumber;
        public String headBranch;
        public String author;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DiscoveryEntry {
        public String title;
        public String ownerRepo;
        public int number;
        public String url;
        public String type;
        public String source;
        public String matchedTopic;
    }

    public Map<String, IssueEntry> issues = new LinkedHashMap<>();
    public Map<String, ReviewEntry> reviews = new LinkedHashMap<>();
    public Map<String, DiscoveryEntry> discoveries = new LinkedHashMap<>();

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    static WorkflowState load(Path path) {
        if (!Files.exists(path)) {
            return new WorkflowState();
        }
        try {
            return MAPPER.readValue(path.toFile(), WorkflowState.class);
        } catch (IOException e) {
            System.err.println("Warning: Could not parse state file, backing up and starting fresh: " + e.getMessage());
            try {
                Path backup = path.resolveSibling("state.json.bak." + Instant.now().toEpochMilli());
                Files.copy(path, backup);
            } catch (IOException ignored) {
            }
            return new WorkflowState();
        }
    }

    void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        MAPPER.writeValue(tmp.toFile(), this);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    void prune(int lookbackDays) {
        Instant cutoff = Instant.now().minus(lookbackDays, ChronoUnit.DAYS);
        issues.entrySet().removeIf(e ->
                e.getValue().state == IssueState.DONE && e.getValue().lastUpdated.isBefore(cutoff));
        reviews.entrySet().removeIf(e ->
                e.getValue().state == ReviewState.DONE && e.getValue().lastUpdated.isBefore(cutoff));
    }

    static class Lock implements AutoCloseable {
        private final RandomAccessFile file;
        private final FileChannel channel;
        private final FileLock lock;

        private Lock(RandomAccessFile file, FileChannel channel, FileLock lock) {
            this.file = file;
            this.channel = channel;
            this.lock = lock;
        }

        static Lock tryAcquire(Path lockPath) throws IOException {
            Files.createDirectories(lockPath.getParent());
            RandomAccessFile raf = new RandomAccessFile(lockPath.toFile(), "rw");
            FileChannel ch = raf.getChannel();
            FileLock lk = ch.tryLock();
            if (lk == null) {
                ch.close();
                raf.close();
                return null;
            }
            return new Lock(raf, ch, lk);
        }

        @Override
        public void close() throws IOException {
            lock.release();
            channel.close();
            file.close();
        }
    }
}
