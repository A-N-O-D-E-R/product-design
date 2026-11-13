package com.anode.product.designer.mapper;

import java.util.List;

import com.anode.product.designer.repository.MapRepository.EnTete;
import com.anode.product.designer.repository.MapRepository.Ligne;
import com.anode.product.designer.repository.MapRepository.QuestionLigne;
import com.anode.product.designer.service.MapService.Carte;
import com.anode.product.designer.service.MapService.Question;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface MapMapper {

    @Mapping(target = "blockedBy", source = "blockedBy")
    @Mapping(target = "ouvrable", source = "ouvrable")
    Question versQuestion(QuestionLigne ligne, List<Integer> blockedBy, boolean ouvrable);

    @Mapping(target = "destination", source = "enTete.destination")
    @Mapping(target = "notes", source = "enTete.notes")
    @Mapping(target = "questions", source = "questions")
    @Mapping(target = "brouillard", source = "brouillard")
    @Mapping(target = "horsPerimetre", source = "horsPerimetre")
    Carte versCarte(EnTete enTete, List<Question> questions, List<Ligne> brouillard, List<Ligne> horsPerimetre);
}
