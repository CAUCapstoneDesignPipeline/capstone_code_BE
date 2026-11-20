package com.example.capstone.topic.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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

/**
 * 주제(폴더). 주제는 깊이 제한 없이 하위 주제를 가질 수 있다(#141).
 * 모든 변경은 사용자 행을 잠근 뒤 수행하므로 같은 사용자의 이동·삭제·정렬이 서로 섞이지 않는다.
 */
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
    public TopicResponse create(UUID userId,String rawName) { return create(userId,rawName,null); }
    @Transactional
    public TopicResponse create(UUID userId,String rawName,UUID parentId) {
        String name=TextValidation.title(rawName,"name","주제 이름",50);
        lock(userId);
        if(parentId!=null) owned(userId,parentId);
        Topic topic=new Topic(userId,parentId,name,topics.maxOrder(userId,parentId)+1,clock.instant());
        checkName(userId,parentId,name,topic.getId());
        save(topic);
        return response(topic,0);
    }
    @Transactional
    public TopicResponse rename(UUID userId,UUID id,String rawName) {
        String name=TextValidation.title(rawName,"name","주제 이름",50);
        lock(userId);
        Topic topic=owned(userId,id);
        checkName(userId,topic.getParentId(),name,id);
        topic.rename(name); save(topic);
        return response(topic,notes.countByUserIdAndTopicId(userId,id));
    }
    /** 부모를 바꾼다. 새 부모의 마지막 순서로 들어가고, 원래 부모의 형제 순서는 0부터 다시 매긴다. */
    @Transactional
    public TopicResponse move(UUID userId,UUID id,UUID parentId) {
        lock(userId);
        Topic topic=owned(userId,id);
        if(parentId!=null) {
            owned(userId,parentId);
            if(isSelfOrDescendant(userId,id,parentId)) {
                throw TextValidation.invalid("parentId","cycle","자기 자신이나 하위 주제 아래로 옮길 수 없습니다.");
            }
        }
        UUID previous=topic.getParentId();
        if(!Objects.equals(previous,parentId)) {
            checkName(userId,parentId,topic.getName(),id);
            topic.moveTo(parentId,topics.maxOrder(userId,parentId)+1);
            save(topic);
            renumber(userId,previous);
        }
        return response(topic,notes.countByUserIdAndTopicId(userId,id));
    }
    @Transactional
    public TopicListResponse reorder(UUID userId,List<UUID> ids) { return reorder(userId,null,ids); }
    /** 한 부모의 바로 아래 주제 순서를 저장한다. parentId가 null이면 최상위 주제다. */
    @Transactional
    public TopicListResponse reorder(UUID userId,UUID parentId,List<UUID> ids) {
        if(ids==null || ids.contains(null) || new HashSet<>(ids).size()!=ids.size()) {
            throw TextValidation.invalid("topicIds","invalid","주제 순서가 올바르지 않습니다.");
        }
        lock(userId);
        if(parentId!=null && topics.findByIdAndUserId(parentId,userId).isEmpty()) throw orderConflict(userId);
        var current=topics.children(userId,parentId).stream().collect(Collectors.toMap(Topic::getId,Function.identity()));
        if(!current.keySet().equals(new HashSet<>(ids))) throw orderConflict(userId);
        for(int order=0;order<ids.size();order++) { current.get(ids.get(order)).reorder(order); }
        topics.flush();
        return list(userId);
    }
    /**
     * 주제만 지운다. 바로 아래 하위 주제와 노트는 지운 주제의 부모로 한 단계 올라간다.
     * 최상위 주제를 지우면 하위 주제는 최상위가 되고 노트는 미분류가 된다(기존 동작과 같음).
     * 올라갈 곳에 같은 제목의 노트나 같은 이름의 주제가 있으면 아무것도 바꾸지 않고 409를 낸다.
     */
    @Transactional
    public void delete(UUID userId,UUID id) {
        lock(userId);
        Topic topic=owned(userId,id);
        UUID target=topic.getParentId();
        List<String> conflicts=notes.titleConflicts(userId,id,target);
        if(!conflicts.isEmpty()) {
            String place=target==null?"미분류":"상위 주제";
            throw new ApiException(ErrorCode.NOTE_TITLE_TAKEN,place+"에 같은 제목의 노트가 있어 주제를 삭제할 수 없습니다.",Map.of("titles",conflicts));
        }
        List<Topic> children=topics.children(userId,id);
        var siblingNames=new HashSet<String>();
        for(Topic sibling:topics.children(userId,target)) if(!sibling.getId().equals(id)) siblingNames.add(sibling.getName());
        List<String> taken=children.stream().map(Topic::getName).filter(siblingNames::contains).sorted().toList();
        if(!taken.isEmpty()) {
            throw new ApiException(ErrorCode.TOPIC_NAME_TAKEN,"상위에 같은 이름의 주제가 있어 주제를 삭제할 수 없습니다.",Map.of("names",taken));
        }
        // 하위 주제가 지울 주제와 같은 이름이면 주제를 지우기 전까지 잠시 이름이 겹친다.
        topics.deferNameCheck();
        int next=topics.maxOrder(userId,target)+1;
        for(Topic child:children) child.moveTo(target,next++);
        topics.flush();
        long count=notes.countByUserIdAndTopicId(userId,id);
        int moved=notes.reassignTopic(userId,id,target,clock.instant());
        if(moved!=count || topics.remove(userId,id)!=1) { throw new IllegalStateException("Topic deletion changed unexpectedly"); }
        renumber(userId,target);
    }
    @Transactional(readOnly=true)
    public Topic owned(UUID userId,UUID id) {
        return topics.findByIdAndUserId(id,userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,"삭제된 주제입니다."));
    }
    /** 최상위부터 id까지의 주제 경로(자기 자신 포함). */
    @Transactional(readOnly=true)
    public List<Topic> path(UUID userId,UUID id) {
        Map<UUID,Topic> all=topics.findByUserId(userId).stream().collect(Collectors.toMap(Topic::getId,Function.identity()));
        var path=new ArrayList<Topic>();
        for(Topic at=all.get(id);at!=null && path.size()<=all.size();at=at.getParentId()==null?null:all.get(at.getParentId())) path.addFirst(at);
        return path;
    }
    private boolean isSelfOrDescendant(UUID userId,UUID id,UUID candidate) {
        return path(userId,candidate).stream().anyMatch(topic -> topic.getId().equals(id));
    }
    private void renumber(UUID userId,UUID parentId) {
        int order=0;
        for(Topic topic:topics.children(userId,parentId)) topic.reorder(order++);
        topics.flush();
    }
    private ApiException orderConflict(UUID userId) {
        return new ApiException(ErrorCode.TOPIC_ORDER_CONFLICT,"다른 곳에서 주제 목록이 바뀌었습니다. 목록을 새로 불러왔으니 다시 시도하세요.",Map.of("current",list(userId)));
    }
    private void lock(UUID userId) {
        users.lockById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED,"로그인이 필요합니다."));
    }
    private void checkName(UUID userId,UUID parentId,String name,UUID except) {
        if(topics.nameExists(userId,parentId,name,except)) { throw duplicate(); }
    }
    private void save(Topic topic) {
        try { topics.saveAndFlush(topic); }
        catch(DataIntegrityViolationException exception) {
            for(Throwable cause=exception;cause!=null;cause=cause.getCause()) {
                if(cause instanceof ConstraintViolationException constraint && "topic_user_parent_name_unique".equals(constraint.getConstraintName())) { throw duplicate(); }
            }
            throw exception;
        }
    }
    private ApiException duplicate() { return new ApiException(ErrorCode.TOPIC_NAME_TAKEN,"같은 이름의 주제가 이미 있습니다."); }
    private TopicResponse response(Topic topic,long count) {
        return new TopicResponse(topic.getId(),topic.getParentId(),topic.getName(),topic.getSortOrder(),count,topic.getCreatedAt());
    }
}
