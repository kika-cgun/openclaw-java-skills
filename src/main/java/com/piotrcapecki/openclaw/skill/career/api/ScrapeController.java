package com.piotrcapecki.openclaw.skill.career.api;

import com.piotrcapecki.openclaw.skill.career.dto.ScrapeRunDto;
import com.piotrcapecki.openclaw.skill.career.repository.ScrapeRunRepository;
import com.piotrcapecki.openclaw.skill.career.scheduler.CareerScheduler;
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
public class ScrapeController {

    private final CareerScheduler careerScheduler;
    private final ScrapeRunRepository scrapeRunRepository;

    @PostMapping("/run")
    public ResponseEntity<Void> triggerScrape() {
        careerScheduler.runDailyPipeline();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/runs")
    public ResponseEntity<List<ScrapeRunDto>> getRuns() {
        return ResponseEntity.ok(scrapeRunRepository.findAllByOrderByStartedAtDesc()
                .stream().map(ScrapeRunDto::from).toList());
    }
}
