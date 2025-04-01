package com.bugbytz.prolink;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
class LoadRequest {
    private final int targetPlayer;
    private final int rekordboxId;
    private final int sourcePlayer;
    private final int sourceSlot;

    @JsonCreator
    public LoadRequest(
            @JsonProperty("targetPlayer") int targetPlayer,
            @JsonProperty("rekordboxId") int rekordboxId,
            @JsonProperty("sourcePlayer") int sourcePlayer,
            @JsonProperty("sourceSlot") int sourceSlot
    ) {
        this.targetPlayer = targetPlayer;
        this.rekordboxId = rekordboxId;
        this.sourcePlayer = sourcePlayer;
        this.sourceSlot = sourceSlot;
    }
}