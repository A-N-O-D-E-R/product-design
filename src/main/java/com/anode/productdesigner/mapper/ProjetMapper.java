package com.anode.productdesigner.mapper;

import com.anode.productdesigner.repository.ProjetRepository.Projet;
import com.anode.productdesigner.service.ProjetService.Cree;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface ProjetMapper {

    @Mapping(target = "sessionTraceId", source = "sessionId")
    Cree versCree(Projet projet, long sessionId);
}
