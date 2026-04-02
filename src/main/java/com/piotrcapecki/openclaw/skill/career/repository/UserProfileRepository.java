package com.piotrcapecki.openclaw.skill.career.repository;

import com.piotrcapecki.openclaw.skill.career.domain.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {
    Optional<UserProfile> findFirstByOrderByIdAsc();
}
