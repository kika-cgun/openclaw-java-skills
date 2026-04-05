package com.piotrcapecki.openclaw.skill.career.api;

import com.piotrcapecki.openclaw.skill.career.dto.ScrapeRunDto;
import com.piotrcapecki.openclaw.skill.career.repository.ScrapeRunRepository;
import com.piotrcapecki.openclaw.skill.career.scheduler.CareerScheduler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/career/scrape")
@RequiredArgsConstructor
@SecurityRequirement(name = "X-API-Key")
@Tag(name = "Scrape", description = "Trigger scraping pipeline and view run history")
public class ScrapeController {

    private final CareerScheduler careerScheduler;
    private final ScrapeRunRepository scrapeRunRepository;

    @Operation(summary = "Trigger daily pipeline", description = "Runs scraping and AI scoring, then optionally sends Telegram digest (if enabled). Synchronous — waits for completion.")
    @PostMapping("/run")
    public ResponseEntity<Void> triggerScrape() {
        careerScheduler.runDailyPipeline();
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "List scrape run history")
    @GetMapping("/runs")
    public ResponseEntity<List<ScrapeRunDto>> getRuns() {
        return ResponseEntity.ok(scrapeRunRepository.findAllByOrderByStartedAtDesc()
                .stream().map(ScrapeRunDto::from).toList());
    }
}
