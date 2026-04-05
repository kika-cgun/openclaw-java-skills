package com.piotrcapecki.openclaw.skill.career.scheduler;

import com.piotrcapecki.openclaw.core.notification.TelegramClient;
import com.piotrcapecki.openclaw.skill.career.domain.JobOffer;
import com.piotrcapecki.openclaw.skill.career.domain.OfferScore;
import com.piotrcapecki.openclaw.skill.career.repository.JobOfferRepository;
import com.piotrcapecki.openclaw.skill.career.service.CareerScoringService;
import com.piotrcapecki.openclaw.skill.career.service.JobIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class CareerScheduler {

    private final JobIngestionService jobIngestionService;
    private final CareerScoringService careerScoringService;
    private final Optional<TelegramClient> telegramClient;
    private final JobOfferRepository jobOfferRepository;

    @Scheduled(cron = "0 0 8 * * *")
    public void runDailyPipeline() {
        log.info("[CareerAgent] Starting daily pipeline...");
        try {
            jobIngestionService.ingest();
            careerScoringService.scoreAllPending();
            sendDigest();
            log.info("[CareerAgent] Daily pipeline complete");
        } catch (Exception e) {
            log.error("[CareerAgent] Pipeline error", e);
        }
    }

    @Transactional
    void sendDigest() {
        if (telegramClient.isEmpty()) {
            log.info("[CareerAgent] Telegram disabled; skipping digest send");
            return;
        }

        List<JobOffer> strong = jobOfferRepository.findBySentAtIsNullAndScore(OfferScore.STRONG);
        List<JobOffer> medium = jobOfferRepository.findBySentAtIsNullAndScore(OfferScore.MEDIUM);

        if (strong.isEmpty() && medium.isEmpty()) {
            log.info("[CareerAgent] No unsent offers today");
            return;
        }

        String message = buildDigest(strong, medium);
        telegramClient.orElseThrow().send(message);

        LocalDateTime now = LocalDateTime.now();
        List<JobOffer> allSent = new ArrayList<>();
        allSent.addAll(strong);
        allSent.addAll(medium);
        allSent.forEach(o -> o.setSentAt(now));
        jobOfferRepository.saveAll(allSent);
    }

    private String buildDigest(List<JobOffer> strong, List<JobOffer> medium) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 <b>OpenClaw — Daily Job Report [")
                .append(LocalDate.now()).append("]</b>\n\n");

        if (!strong.isEmpty()) {
            sb.append("💚 <b>Mocne dopasowania (").append(strong.size()).append(")</b>\n");
            for (JobOffer o : strong)
                appendOffer(sb, o);
            sb.append("\n");
        }

        if (!medium.isEmpty()) {
            sb.append("🟡 <b>Średnie dopasowania (").append(medium.size()).append(")</b>\n");
            for (JobOffer o : medium)
                appendOffer(sb, o);
        }

        return sb.toString();
    }

    private void appendOffer(StringBuilder sb, JobOffer o) {
        sb.append("• ").append(escapeHtml(o.getTitle()))
                .append(" @ ").append(escapeHtml(o.getCompany()))
                .append(" — ").append(escapeHtml(o.getLocation())).append("\n")
                .append("  <a href=\"").append(o.getUrl() != null ? o.getUrl().replace("\"", "%22") : "#")
                .append("\">Zobacz ofertę</a>\n");
    }

    private String escapeHtml(String text) {
        if (text == null)
            return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
