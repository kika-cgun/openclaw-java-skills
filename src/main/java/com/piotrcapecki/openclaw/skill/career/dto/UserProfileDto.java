package com.piotrcapecki.openclaw.skill.career.dto;

import com.piotrcapecki.openclaw.skill.career.domain.UserProfile;

import java.util.List;

public record UserProfileDto(List<String> stack, List<String> level,
                              List<String> locations, String preferences) {
    public static UserProfileDto from(UserProfile p) {
        return new UserProfileDto(p.getStack(), p.getLevel(), p.getLocations(), p.getPreferences());
    }
}
