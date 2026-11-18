package com.anode.productdesigner.mapper;

import java.util.List;

import com.anode.productdesigner.repository.RoadmapRepository.JalonLigne;
import com.anode.productdesigner.repository.RoadmapRepository.SpecLigne;
import com.anode.productdesigner.repository.RoadmapRepository.TicketLigne;
import com.anode.productdesigner.service.RoadmapService.Jalon;
import com.anode.productdesigner.service.RoadmapService.Roadmap;
import com.anode.productdesigner.service.RoadmapService.Ticket;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface RoadmapMapper {

    @Mapping(target = "blockedBy", source = "blockedBy")
    Ticket versTicket(TicketLigne ligne, List<Long> blockedBy);

    @Mapping(target = "tickets", source = "tickets")
    Jalon versJalon(JalonLigne ligne, List<Ticket> tickets);

    @Mapping(target = "specId", source = "specId")
    @Mapping(target = "jalons", source = "jalons")
    Roadmap versRoadmap(long specId, SpecLigne spec, List<Jalon> jalons);

    /** Exigences stockées « E-1, E-2 » : liste vide si rien. */
    default List<String> exigences(String stockees) {
        return stockees == null || stockees.isBlank() ? List.of() : List.of(stockees.split(",\\s*"));
    }
}
