import jakarta.mail.*;
import jakarta.mail.internet.*;

import java.util.List;
import java.util.Properties;

public class Notifier {

    private final Config config;

    Notifier(Config config) {
        this.config = config;
    }

    record ProcessingResult(String repo, String issueOrPR, String title, String url, String prUrl,
                            String fromState, String toState) {
        String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(repo).append(" — ").append(issueOrPR).append(": ").append(title).append("\n");
            sb.append("  ").append(fromState).append(" → ").append(toState).append("\n");
            if (url != null) sb.append("  ").append(url).append("\n");
            if (prUrl != null) sb.append("  PR: ").append(prUrl).append("\n");
            return sb.toString();
        }
    }

    void sendSummary(List<ProcessingResult> results) {
        if (results.isEmpty()) return;

        StringBuilder body = new StringBuilder();
        body.append("Hi,\n\nThe GitHub worker processed the following items:\n\n");
        for (ProcessingResult r : results) {
            body.append(r.summary()).append("\n");
        }
        body.append("Please review at your convenience.\n");

        send("GitHub Worker — " + results.size() + " item(s) processed", body.toString());
    }

    void sendSecurityWarning(String ownerRepo, int number, String title, String url,
                             String triageResult) {
        String body = """
                Hi,

                The GitHub worker flagged a potentially suspicious issue and skipped it.

                %s — #%d: %s
                Issue: %s

                Security triage result:
                %s

                Please review the issue manually before proceeding.
                """.formatted(ownerRepo, number, title, url, triageResult);

        send("SECURITY WARNING — suspicious issue " + ownerRepo + "#" + number, body);
    }

    private void send(String subject, String body) {
        try {
            Properties props = new Properties();
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.host", "smtp.gmail.com");
            props.put("mail.smtp.port", "587");

            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(config.gmailAddress, config.gmailAppPassword);
                }
            });

            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(config.gmailAddress));

            for (String to : config.sendTo.split(",")) {
                msg.addRecipient(Message.RecipientType.TO, new InternetAddress(to.trim()));
            }

            msg.setSubject(subject);
            msg.setText(body, "utf-8");

            Transport.send(msg);
        } catch (Exception e) {
            System.err.println("Failed to send email: " + e.getMessage());
        }
    }
}
