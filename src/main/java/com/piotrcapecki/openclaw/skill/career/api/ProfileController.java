package com.piotrcapecki.openclaw.skill.career.api;

import com.piotrcapecki.openclaw.skill.career.domain.UserProfile;
import com.piotrcapecki.openclaw.skill.career.dto.UserProfileDto;
import com.piotrcapecki.openclaw.skill.career.repository.UserProfileRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/career/profile")
@RequiredArgsConstructor
@SecurityRequirement(name = "X-API-Key")
@Tag(name = "Profile", description = "User profile — stack, level, locations, preferences")
public class ProfileController {

    private final UserProfileRepository userProfileRepository;

    @Operation(summary = "Get user profile")
    @GetMapping
    public ResponseEntity<UserProfileDto> getProfile() {
        return userProfileRepository.findFirstByOrderByIdAsc()
                .map(UserProfileDto::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Create or update user profile")
    @Transactional
    @PatchMapping
    public ResponseEntity<UserProfileDto> patchProfile(@RequestBody Map<String, Object> updates) {
        UserProfile profile = userProfileRepository.findFirstByOrderByIdAsc()
                .orElse(UserProfile.builder().build());

        if (updates.containsKey("stack"))       profile.setStack(castList(updates.get("stack")));
        if (updates.containsKey("level"))       profile.setLevel(castList(updates.get("level")));
        if (updates.containsKey("locations"))   profile.setLocations(castList(updates.get("locations")));
        if (updates.containsKey("preferences")) profile.setPreferences((String) updates.get("preferences"));

        profile.setUpdatedAt(LocalDateTime.now());
        return ResponseEntity.ok(UserProfileDto.from(userProfileRepository.save(profile)));
    }

    @SuppressWarnings("unchecked")
    private List<String> castList(Object value) {
        if (value == null) return List.of();
        return ((List<?>) value).stream().map(Object::toString).toList();
    }
}
