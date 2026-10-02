package com.example.capstone.topic.service;

import java.time.Clock;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;
import com.example.capstone.global.validation.TextValidation;
import com.example.capstone.topic.domain.Topic;
import com.example.capstone.topic.dto.response.TopicListResponse;
import com.example.capstone.topic.dto.response.TopicResponse;
import com.example.capstone.topic.repository.TopicRepository;
import com.example.capstone.note.repository.NoteRepository;
import com.example.capstone.user.repository.AppUserRepository;

@Service
public class TopicService {
    private final TopicRepository topics;
    private final NoteRepository notes;
    private final AppUserRepository users;
    private final Clock clock;
    public TopicService(TopicRepository topics,NoteRepository notes,AppUserRepository users,Clock clock) {
        this.topics=topics; this.notes=notes; this.users=users; this.clock=clock;
    }
    @Transactional(readOnly=true)
    public TopicListResponse list(UUID userId) {
        return new TopicListResponse(topics.list(userId),notes.countByUserIdAndTopicId(userId,null));
    }
    @Transactional
    public TopicResponse create(UUID userId,String rawName) {
        String name=TextValidation.title(rawName,"name","주제 이름",50);
        lock(userId);
        Topic topic=new Topic(userId,name,topics.maxOrder(userId)+1,clock.instant());
        checkName(userId,name,topic.getId());
        save(topic);
        return response(topic,0);
    }
    @Transactional
    public TopicResponse rename(UUID userId,UUID id,String rawName) {
        String name=TextValidation.title(rawName,"name","주제 이름",50);
        lock(userId);
        Topic topic=owned(userId,id);
        checkName(userId,name,id);
        topic.rename(name); save(topic);
        return response(topic,notes.countByUserIdAndTopicId(userId,id));
    }
    @Transactional(readOnly=true)
    public Topic owned(UUID userId,UUID id) {
        return topics.findByIdAndUserId(id,userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,"삭제된 주제입니다."));
    }
    private void lock(UUID userId) {
        users.lockById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED,"로그인이 필요합니다."));
    }
    private void checkName(UUID userId,String name,UUID except) {
        if(topics.existsByUserIdAndNameAndIdNot(userId,name,except)) { throw duplicate(); }
    }
    private void save(Topic topic) {
        try { topics.saveAndFlush(topic); }
        catch(DataIntegrityViolationException exception) {
            for(Throwable cause=exception;cause!=null;cause=cause.getCause()) {
                if(cause instanceof ConstraintViolationException constraint && "topic_user_name_unique".equals(constraint.getConstraintName())) { throw duplicate(); }
            }
            throw exception;
        }
    }
    private ApiException duplicate() { return new ApiException(ErrorCode.TOPIC_NAME_TAKEN,"같은 이름의 주제가 이미 있습니다."); }
    private TopicResponse response(Topic topic,long count) {
        return new TopicResponse(topic.getId(),topic.getName(),topic.getSortOrder(),count,topic.getCreatedAt());
    }
}
