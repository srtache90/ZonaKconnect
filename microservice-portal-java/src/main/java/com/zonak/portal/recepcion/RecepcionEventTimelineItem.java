package com.zonak.portal.recepcion;

import java.time.OffsetDateTime;

public record RecepcionEventTimelineItem(
        String code,
        String action,
        String label,
        OffsetDateTime at,
        String trackId,
        String cude,
        String detail,
        String source
) {
}
