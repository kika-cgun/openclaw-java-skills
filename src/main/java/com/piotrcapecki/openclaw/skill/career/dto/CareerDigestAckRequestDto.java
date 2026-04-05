package com.piotrcapecki.openclaw.skill.career.dto;

import java.util.List;
import java.util.UUID;

public record CareerDigestAckRequestDto(List<UUID> offerIds) {
}
