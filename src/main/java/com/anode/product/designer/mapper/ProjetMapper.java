package com.anode.product.designer.mapper;

import com.anode.product.designer.repository.ProjetRepository.Projet;
import com.anode.product.designer.service.ProjetService.Cree;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface ProjetMapper {

    @Mapping(target = "sessionTraceId", source = "sessionId")
    Cree versCree(Projet projet, long sessionId);
}
