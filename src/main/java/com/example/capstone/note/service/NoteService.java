package com.example.capstone.note.service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;
import com.example.capstone.global.validation.TextValidation;
import com.example.capstone.note.domain.Note;
import com.example.capstone.note.dto.request.NoteCreateRequest;
import com.example.capstone.note.dto.request.NoteUpdateRequest;
import com.example.capstone.note.dto.response.NoteListResponse;
import com.example.capstone.note.dto.response.NoteResponse;
import com.example.capstone.note.dto.response.NoteSummaryResponse;
import com.example.capstone.note.repository.NoteRepository;
import com.example.capstone.topic.service.TopicService;
import com.example.capstone.user.repository.AppUserRepository;

@Service
public class NoteService {
    private final NoteRepository notes;
    private final AppUserRepository users;
    private final TopicService topics;
    private final Clock clock;
    public NoteService(NoteRepository notes,AppUserRepository users,TopicService topics,Clock clock) {
        this.notes=notes; this.users=users; this.topics=topics; this.clock=clock;
    }
    @Transactional
    public NoteResponse create(UUID userId,NoteCreateRequest request) {
        String title=TextValidation.title(request.title(),"title","제목",200);
        String body=TextValidation.body(request.body()==null?"":request.body());
        lock(userId);
        if(request.topicId()!=null) { topics.owned(userId,request.topicId()); }
        var note=new Note(userId,request.topicId(),title,body,clock.instant());
        checkTitle(userId,note.getTopicId(),title,note.getId());
        note=save(note);
        return NoteResponse.from(note);
    }
    @Transactional
    public NoteResponse update(UUID userId,UUID id,NoteUpdateRequest request) {
        String title=TextValidation.title(request.title(),"title","제목",200);
        String body=TextValidation.body(request.body());
        lock(userId);
        Note note=owned(userId,id,true);
        if(note.getVersion()!=request.version()) {
            throw new ApiException(ErrorCode.NOTE_CONFLICT,"다른 곳에서 이 노트가 먼저 수정되었습니다.",Map.of("current",NoteResponse.from(note)));
        }
        checkTitle(userId,note.getTopicId(),title,id);
        note.edit(title,body,clock.instant());
        return NoteResponse.from(save(note));
    }
    @Transactional(readOnly=true)
    public NoteResponse get(UUID userId,UUID id) { return NoteResponse.from(owned(userId,id,false)); }
    @Transactional(readOnly=true)
    public NoteListResponse list(UUID userId,String filter,String sort,String q) {
        if(q!=null && !q.trim().isEmpty()) { throw TextValidation.invalid("q","invalid","검색 기능은 아직 제공되지 않습니다."); }
        boolean all=filter==null,unassigned="none".equals(filter);
        UUID topicId=null;
        if(!all && !unassigned) {
            try {
                topicId=UUID.fromString(filter);
                if(!topicId.toString().equalsIgnoreCase(filter)) { throw new IllegalArgumentException(); }
            } catch(IllegalArgumentException exception) { throw TextValidation.invalid("topicId","invalid","주제 ID가 올바르지 않습니다."); }
            topics.owned(userId,topicId);
        }
        Sort order;
        if("title".equals(sort)) { order=Sort.by("title").ascending().and(Sort.by("id")); }
        else if("updated".equals(sort)) { order=Sort.by("updatedAt").descending().and(Sort.by("id")); }
        else { throw TextValidation.invalid("sort","invalid","정렬 기준이 올바르지 않습니다."); }
        return new NoteListResponse(notes.list(userId,all,unassigned,topicId,order).stream().map(this::summary).toList());
    }
    private Note owned(UUID userId,UUID id,boolean saving) {
        return notes.findByIdAndUserId(id,userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                saving?"다른 곳에서 삭제된 노트입니다":"삭제된 노트입니다"));
    }
    private void lock(UUID userId) {
        users.lockById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED,"로그인이 필요합니다."));
    }
    private void checkTitle(UUID userId,UUID topicId,String title,UUID except) {
        if(notes.titleExists(userId,topicId,title,except)) { throw duplicate(topicId,title); }
    }
    private Note save(Note note) {
        try { return notes.saveAndFlush(note); }
        catch(DataIntegrityViolationException exception) {
            for(Throwable cause=exception;cause!=null;cause=cause.getCause()) {
                if(cause instanceof ConstraintViolationException constraint && "note_topic_title_unique".equals(constraint.getConstraintName())) {
                    throw duplicate(note.getTopicId(),note.getTitle());
                }
            }
            throw exception;
        }
    }
    private ApiException duplicate(UUID topicId,String title) {
        return new ApiException(ErrorCode.NOTE_TITLE_TAKEN,topicId==null?"미분류에 같은 제목의 노트가 있습니다.":"같은 주제에 같은 제목의 노트가 있습니다.",Map.of("titles",List.of(title)));
    }
    private NoteSummaryResponse summary(Note note) {
        String body=note.getBody();
        int length=Math.min(80,body.codePointCount(0,body.length()));
        String snippet=body.substring(0,body.offsetByCodePoints(0,length));
        return new NoteSummaryResponse(note.getId(),note.getTopicId(),note.getTitle(),snippet,note.getVersion(),note.getUpdatedAt());
    }
}
